package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceSelectorMatcher;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.RouterNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** D-Bus signal bridge between the GNOME extension and Java-owned audio actions/state. */
public final class GnomeControlBridge implements AutoCloseable {
    public static final String OBJECT_PATH="/org/ilya/AudioMatrix";
    public static final String INTERFACE="org.ilya.AudioMatrix";
    public static final int API_VERSION=5;
    private static final java.util.Set<String> SUPPORTED_COMMANDS=java.util.Set.of("open",
            "set_output_volume","toggle_output_mute","set_source_volume","toggle_source_mute","set_app_volume","toggle_app_mute","set_stream_volume",
            "set_graph_output_volume","toggle_graph_output_mute","set_graph_gain","toggle_graph_gain_mute","toggle_stream_mute","set_mixer_input_volume","toggle_mixer_input_mute","set_quick_control","toggle_quick_control","set_quick_control_choice",
            "set_quick_group_collapsed","reorder_quick_group","set_quick_pin_view","set_quick_matrix_view","select_route","select_profile","set_channel_map",
            "setNodeVolume","setNodeMuted","setRouteEnabled");
    private final Function<Command,CompletableFuture<Void>> commands;
    private final ObjectMapper json=new ObjectMapper();
    private final List<BrowserAdapter> browserAdapters;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicBoolean started=new AtomicBoolean();
    private final GnomeBridgeStateSequencer stateSequencer=new GnomeBridgeStateSequencer(json);
    private CompletableFuture<Void> commandTail=CompletableFuture.completedFuture(null);
    private final ScheduledExecutorService emitter=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"audio-matrix-dbus-status");t.setDaemon(true);return t;});
    private volatile Process monitor;
    public void publishState(PipeWireBackend.Snapshot snapshot,Map<String,Object> config,long configRevision,
                             DynamicInstanceRegistry.ReconcileResult graphState){
        emitObservedState(stateProjection(snapshot,config,configRevision,browserAdapters,graphState));
    }
    public static Map<String,Object> stateProjection(PipeWireBackend.Snapshot snapshot,Map<String,Object> config,
            long configRevision,List<BrowserAdapter> browserAdapters,
            DynamicInstanceRegistry.ReconcileResult graphState){
        return GnomeBridgeStateBuilder.build(snapshot,config,configRevision,browserAdapters,graphState);
    }
    static List<Map<String,Object>> outputDevices(PipeWireBackend.Snapshot snapshot){
        return GnomeOutputState.devices(snapshot,List.of());
    }
    public GnomeControlBridge(Function<Command,CompletableFuture<Void>> commands,
                              List<BrowserAdapter> browserAdapters){
        this.commands=commands==null?ignored->CompletableFuture.completedFuture(null):commands;
        this.browserAdapters=List.copyOf(browserAdapters==null?List.of():browserAdapters);
    }
    public void start(){if(!started.compareAndSet(false,true))return;Thread.ofVirtual().name("audio-matrix-dbus-monitor").start(this::monitorLoop);emitter.scheduleAtFixedRate(this::reemitLatestState,4,4,TimeUnit.SECONDS);}
    static List<Map<String,Object>> quickControls(Map<String,Object> config){
        String active=String.valueOf(config.getOrDefault("active_route",""));
        Map<String,Object> route=maps(config.get("route_library")).stream().filter(item->active.equals(item.get("id"))).findFirst().orElse(null);
        AudioGraph graph=null;if(route!=null)try{graph=GraphCodec.fromMap(route);}catch(RuntimeException invalid){/* Common controls remain available. */}
        AudioGraph commonGraph=null;try{commonGraph=GraphCommonVibro.graph(config);}catch(RuntimeException invalid){/* Invalid common state is not published. */}
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> control:maps(config.get("quick_controls"))){
            String controlRoute=String.valueOf(control.getOrDefault("route_id",active));
            boolean common=GraphCommonVibro.COMMON_ROUTE_ID.equals(controlRoute);
            if(!common&&!active.equals(controlRoute))continue;
            AudioGraph controlGraph=common?commonGraph:graph;
            if(controlGraph==null)continue;
            String nodeId=String.valueOf(control.getOrDefault("node_id","")),parameter=String.valueOf(control.getOrDefault("parameter_id",""));GraphNode node=controlGraph.nodes().stream().filter(candidate->candidate.id().equals(nodeId)).findFirst().orElse(null);if(node==null)continue;
            Map<String,Object> metadata=GnomeQuickControlParameter.describe(node,parameter,language(config));if(metadata==null)continue;String kind=String.valueOf(metadata.get("kind"));
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",control.get("id"));item.put("nodeId",nodeId);item.put("nodeType",node.type());item.put("parameterId",parameter);item.put("label",control.getOrDefault("label",node.name()));item.put("section",control.getOrDefault("section","Audio Matrix"));item.put("order",control.getOrDefault("order",0));item.putAll(metadata);
            item.put("routeId",controlRoute);item.put("pinned",common);
            item.put("view",control.getOrDefault("view","MATRIX"));
            item.put("groupId",control.getOrDefault("group_id",control.getOrDefault("section","routing")));
            if("gain".equals(node.type())&&"gain".equals(parameter))item.put("muted",Boolean.TRUE.equals(node.parameters().get("muted")));
            if("links".equals(kind)&&node instanceof RouterNode router){
                List<String> wiredInputs=controlGraph.connections().stream().filter(edge->edge.enabled()&&edge.toNode().equals(router.id())).map(edge->edge.toPort()).distinct().toList();
                List<String> wiredOutputs=controlGraph.connections().stream().filter(edge->edge.enabled()&&edge.fromNode().equals(router.id())).map(edge->edge.fromPort()).distinct().toList();
                List<Map<String,Object>> options=new ArrayList<>();for(var input:router.inputs())if(wiredInputs.contains(input.id()))for(var output:router.outputs())if(wiredOutputs.contains(output.id())){String link=input.id()+"->"+output.id();options.add(Map.of("id",link,"label",input.id()+" → "+output.id()));}item.put("options",options);item.put("multiple",router.multiple());
            }
            result.add(item);
        }
        result.sort(java.util.Comparator.comparingInt(item->item.get("order") instanceof Number number?number.intValue():0));return List.copyOf(result);
    }
    static String controlKind(String nodeType,String parameter){if("gain".equals(nodeType)&&"gain".equals(parameter))return "slider";if("gain".equals(nodeType)&&"muted".equals(parameter))return "toggle";if("gate".equals(nodeType)&&"open".equals(parameter))return "toggle";if("filter".equals(nodeType)&&"enabled".equals(parameter))return "toggle";if("filter".equals(nodeType)&&parameter.endsWith("_hz"))return "range";if("filter".equals(nodeType)&&parameter.endsWith("_ms"))return "range";if("filter".equals(nodeType)&&parameter.endsWith("_db"))return "range";if("filter".equals(nodeType)&&("slope_db_oct".equals(parameter)||parameter.endsWith(".type")))return "choice";if(List.of("switch","selector","multi_selector","matrix").contains(nodeType)&&"active_links".equals(parameter))return "links";return "";}
    private void reemitLatestState(){String payload=stateSequencer.latest();if(!closed.get()&&!payload.isBlank())emit("State",payload);}
    static List<Map<String,Object>> channelRoutes(PipeWireBackend.Snapshot snapshot,Map<String,Object> config,Map<String,Object> profile,AudioNode selected,int defaultId,List<Map<String,Object>> devices){
        if(selected==null)return List.of();
        List<String> activeIds=stringList(profile.get("active_routes"));List<Map<String,Object>> buses=maps(config.get("buses"));List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> route:maps(config.get("routes"))){
            String id=String.valueOf(route.getOrDefault("id",""));if(id.isBlank()||!activeIds.contains(id)||Boolean.FALSE.equals(route.get("enabled")))continue;
            String target=String.valueOf(route.getOrDefault("to",""));if(buses.stream().anyMatch(bus->target.equals(bus.get("id"))))continue;
            AudioNode output="system_default".equals(target)?snapshot.sinks().stream().filter(node->node.id()==defaultId).findFirst().orElse(null):DeviceSelectorMatcher.resolve(snapshot.sinks(),target,devices);
            if(output==null||output.id()!=selected.id())continue;
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",id);item.put("name",route.getOrDefault("name",id));item.put("channel_map",route.getOrDefault("channel_map","stereo"));result.add(item);
        }
        return List.copyOf(result);
    }
    private static String language(Map<String,Object> config){Object ui=config.get("ui");if(ui instanceof Map<?,?> preferences&&"en".equals(preferences.get("language")))return "en";return "ru";}
    private static List<Map<String,Object>> maps(Object value){if(!(value instanceof List<?> list))return List.of();List<Map<String,Object>>result=new ArrayList<>();for(Object item:list)if(item instanceof Map<?,?> raw){Map<String,Object>copy=new LinkedHashMap<>();raw.forEach((key,entry)->copy.put(String.valueOf(key),entry));result.add(copy);}return result;}
    private static List<String> stringList(Object value){if(!(value instanceof List<?> list))return List.of();return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();}
    private void monitorLoop(){while(!closed.get()){
        try{monitor=new ProcessBuilder("dbus-monitor","--session","type='signal',interface='"+INTERFACE+"',member='Command'").redirectErrorStream(true).start();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(monitor.getInputStream()))){String line;List<String> arguments=null;while(!closed.get()&&(line=reader.readLine())!=null){
                if(line.contains("member=Command")||line.contains(INTERFACE+".Command")){
                    Command command=parseCommandLine(line);if(command!=null){dispatch(command);arguments=null;}else arguments=new ArrayList<>();continue;
                }
                if(arguments==null)continue;
                if(line.contains("member=")){arguments=null;continue;}
                Command command=parseCommandLine(line);if(command!=null){dispatch(command);arguments=null;continue;}
                if(!collectMonitorArgument(line,arguments))continue;
                Command split=parseCommandArguments(arguments);if(split!=null){dispatch(split);arguments=null;}
            }}
            if(!closed.get())Thread.sleep(1200);
        }catch(Exception e){if(!closed.get())try{Thread.sleep(2500);}catch(InterruptedException ignored){Thread.currentThread().interrupt();return;}}
    }}
    /** Collects one typed argument line of a {@code dbus-monitor} signal dump. */
    static boolean collectMonitorArgument(String line,List<String> arguments){
        if(arguments==null||arguments.size()>=5)return false;
        String trimmed=line.trim();
        if(trimmed.startsWith("uint32 ")){arguments.add(trimmed.substring("uint32 ".length()).trim());return true;}
        if(trimmed.startsWith("string ")){arguments.add(unquoteGVariantString(trimmed.substring("string ".length()).trim()));return true;}
        return false;
    }
    static String unquoteGVariantString(String value){
        if(value.length()>=2&&value.startsWith("\"")&&value.endsWith("\""))return unescapeGVariantString(value.substring(1,value.length()-1));
        return value;
    }
    static Command parseCommandArguments(List<String> arguments){
        if(arguments==null||arguments.size()!=5)return null;
        try{
            int version=Integer.parseInt(arguments.get(0));
            if(version!=API_VERSION)return null;
            return new Command(version,arguments.get(1),arguments.get(2),arguments.get(3),arguments.get(4));
        }
        catch(NumberFormatException invalid){return null;}
    }
    void dispatch(Command command){
        CompletableFuture<CommandResult> execution;
        synchronized(this){
            execution=commandTail.thenCompose(ignored->execute(command,commands));
            commandTail=execution.handle((result,error)->(Void)null);
        }
        execution.whenComplete((result,error)->{
            CommandResult confirmed=error==null&&result!=null?result:new CommandResult("error",error==null?"Command handler returned no result":safeMessage(error));
            emitAcknowledgment(command,confirmed);
            emitResult(command,confirmed.status(),confirmed.message());
        });
    }
    private synchronized void emitObservedState(Map<String,Object> state){
        String payload=stateSequencer.observe(state);
        if(!payload.isBlank())emitter.execute(()->emit("State",payload));
    }
    private synchronized void emitAcknowledgment(Command command,CommandResult result){
        if(command.requestId().isBlank())return;
        String payload=stateSequencer.acknowledge(GnomeBridgeCommandAck.create(command,result,json));
        if(!payload.isBlank())emitter.execute(()->emit("State",payload));
    }
    static CompletableFuture<CommandResult> execute(Command command,Function<Command,CompletableFuture<Void>> handler){
        if(command.apiVersion()!=API_VERSION)return CompletableFuture.completedFuture(new CommandResult("error","Unsupported Audio Matrix D-Bus API version "+command.apiVersion()));
        if(!SUPPORTED_COMMANDS.contains(command.name()))return CompletableFuture.completedFuture(new CommandResult("error","Unsupported command: "+command.name()));
        try{
            CompletableFuture<Void> action=handler.apply(command);
            if(action==null)return CompletableFuture.completedFuture(new CommandResult("error","Command handler returned no completion result"));
            return action.handle((ignored,error)->error==null?new CommandResult("ok","Applied"):new CommandResult("error",safeMessage(error)));
        }catch(RuntimeException error){return CompletableFuture.completedFuture(new CommandResult("error",safeMessage(error)));}
    }
    static Command parseCommandLine(String line){
        java.util.regex.Matcher versioned=java.util.regex.Pattern.compile("\\((?:uint32\\s+)?(\\d+),\\s*'((?:\\\\.|[^'])*)',\\s*'((?:\\\\.|[^'])*)',\\s*'((?:\\\\.|[^'])*)',\\s*'((?:\\\\.|[^'])*)'\\)").matcher(line.trim());
        if(!versioned.find())return null;
        int version=Integer.parseInt(versioned.group(1));
        if(version!=API_VERSION)return null;
        return new Command(version,unescapeGVariantString(versioned.group(2)),
                unescapeGVariantString(versioned.group(3)),unescapeGVariantString(versioned.group(4)),
                unescapeGVariantString(versioned.group(5)));
    }
    private static String unescapeGVariantString(String value){StringBuilder decoded=new StringBuilder(value.length());for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c!='\\'||i+1>=value.length()){decoded.append(c);continue;}char escaped=value.charAt(++i);decoded.append(switch(escaped){case 'a'->'\u0007';case 'b'->'\b';case 'f'->'\f';case 'n'->'\n';case 'r'->'\r';case 't'->'\t';case 'v'->'\u000b';case '\\'->'\\';case '\''->'\'';case '"'->'"';default->escaped;});}return decoded.toString();}
    private void emit(String signal,String value){try{run(List.of("busctl","--user","emit",OBJECT_PATH,INTERFACE,signal,"s",value),Duration.ofSeconds(2));}catch(Exception ignored){}}
    private void emitResult(Command command,String status,String message){if(command.requestId().isBlank())return;try{run(List.of("busctl","--user","emit",OBJECT_PATH,INTERFACE,"Result","usss",Integer.toString(API_VERSION),command.requestId(),status,message),Duration.ofSeconds(2));}catch(Exception ignored){}}
    private static String safeMessage(Throwable error){Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();String message=cause.getMessage();return message==null||message.isBlank()?cause.getClass().getSimpleName():message;}
    private static String run(List<String>command,Duration timeout)throws Exception{Path out=Files.createTempFile("audio-matrix-dbus-",".out");try{Process p=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile()).start();if(!p.waitFor(timeout.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS)){p.destroyForcibly();throw new IOException("gdbus timed out");}if(p.exitValue()!=0)throw new IOException(Files.readString(out));return Files.readString(out);}finally{Files.deleteIfExists(out);}}
    @Override public void close(){closed.set(true);if(monitor!=null)monitor.destroyForcibly();emitter.shutdownNow();}
    public record Command(int apiVersion,String requestId,String revision,String name,String value){
        public Command(int apiVersion,String requestId,String name,String value){this(apiVersion,requestId,"",name,value);}
        public Command(String name,String value){this(API_VERSION,"","",name,value);}
    }
    record CommandResult(String status,String message){}
}
