package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceSelectorMatcher;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
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
    public static final int API_VERSION=3;
    private static final java.util.Set<String> SUPPORTED_COMMANDS=java.util.Set.of("open","select_output","toggle_mute",
            "set_output_volume","toggle_output_mute","set_app_volume","toggle_app_mute","set_stream_volume",
            "toggle_stream_mute","set_quick_control","toggle_quick_control","set_quick_control_choice",
            "set_quick_group_collapsed","select_route","set_mode","set_channel_map");
    private final PipeWireBackend backend;
    private final Function<Command,CompletableFuture<Void>> commands;
    private final ObjectMapper json=new ObjectMapper();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicBoolean started=new AtomicBoolean();
    private final ScheduledExecutorService emitter=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"audio-matrix-dbus-status");t.setDaemon(true);return t;});
    private volatile Process monitor;
    private volatile String lastState="";
    public GnomeControlBridge(PipeWireBackend backend,Function<Command,CompletableFuture<Void>> commands){this.backend=backend;this.commands=commands==null?ignored->CompletableFuture.completedFuture(null):commands;}
    public void start(){if(!started.compareAndSet(false,true))return;Thread.ofVirtual().name("audio-matrix-dbus-monitor").start(this::monitorLoop);emitter.scheduleAtFixedRate(this::reemitLatestState,4,4,TimeUnit.SECONDS);}
    public void publishState(PipeWireBackend.Snapshot snapshot,Map<String,Object> config,long configRevision){
        Map<String,Object> profile=activeProfile(config);String configured=String.valueOf(profile.getOrDefault("default_output","system_default"));
        int defaultId=snapshot.defaultSinkId();List<Map<String,Object>> devices=maps(config.get("devices"));
        List<Map<String,Object>> outputs=GnomeOutputState.devices(snapshot,devices);
        AudioNode active=DeviceSelectorMatcher.resolve(snapshot.sinks(),configured,devices);if(active!=null&&active.name().startsWith("audiomatrix_"))active=null;if(active==null)active=snapshot.sinks().stream().filter(n->n.id()==defaultId&&!n.name().startsWith("audiomatrix_")).findFirst().orElse(null);
        Map<String,Object> state=new LinkedHashMap<>();state.put("apiVersion",API_VERSION);state.put("revision",Long.toString(configRevision));state.put("connected",snapshot.connected());state.put("language",language(config));state.put("outputs",outputs);state.put("selectedOutput",active==null?"system_default":active.name());state.put("muted",active!=null&&active.muted());state.put("mode",profile.getOrDefault("mode","off"));state.put("profile",profile.getOrDefault("name",""));state.put("activeRoute",activeRouteName(config));state.put("activeRouteId",config.getOrDefault("active_route",""));state.put("routes",GnomeQuickSettingsState.routes(config));state.put("quickGroups",GnomeQuickSettingsState.groups(config));state.put("quickControls",quickControls(config));state.put("channelRoutes",channelRoutes(snapshot,config,profile,active,defaultId,devices));
        Map<String,Map<String,Object>> groups=new LinkedHashMap<>();
        for(AudioNode stream:snapshot.streams()){
            String appId=stream.applicationId()==null?"":stream.applicationId();String process=stream.processName()==null?"":stream.processName();
            String key=!appId.isBlank()?"app:"+appId:!process.isBlank()?"process:"+process:"stream:"+stream.name();
            Map<String,Object> group=groups.computeIfAbsent(key,ignored->{Map<String,Object> created=new LinkedHashMap<>();created.put("id",key);created.put("name",stream.applicationName().isBlank()?stream.description():stream.applicationName());created.put("application_id",appId);created.put("process",process);created.put("streams",new ArrayList<Map<String,Object>>());created.put("volume",Double.isFinite(stream.volume())?Math.max(0,Math.min(1,stream.volume())):0);created.put("muted",stream.muted());return created;});
            @SuppressWarnings("unchecked") List<Map<String,Object>> children=(List<Map<String,Object>>)group.get("streams");Map<String,Object> child=new LinkedHashMap<>();child.put("id",stream.id());child.put("name",stream.name());child.put("description",stream.description());child.put("volume",Double.isFinite(stream.volume())?Math.max(0,Math.min(1,stream.volume())):0);child.put("muted",stream.muted());children.add(child);
        }
        state.put("applications",List.copyOf(groups.values()));
        try{String payload=json.writeValueAsString(state);if(payload.equals(lastState))return;lastState=payload;emitter.execute(()->emit("State",payload));}catch(Exception ignored){}
    }
    static List<Map<String,Object>> outputDevices(PipeWireBackend.Snapshot snapshot){
        return GnomeOutputState.devices(snapshot,List.of());
    }
    private static String activeRouteName(Map<String,Object> config){String id=String.valueOf(config.getOrDefault("active_route",""));if(id.isBlank())return "";for(Map<String,Object> route:maps(config.get("route_library")))if(id.equals(route.get("id")))return String.valueOf(route.getOrDefault("name",id));return id;}
    static List<Map<String,Object>> quickControls(Map<String,Object> config){
        String active=String.valueOf(config.getOrDefault("active_route",""));if(active.isBlank())return List.of();
        Map<String,Object> route=maps(config.get("route_library")).stream().filter(item->active.equals(item.get("id"))).findFirst().orElse(null);if(route==null)return List.of();
        AudioGraph graph;try{graph=GraphCodec.fromMap(route);}catch(RuntimeException invalid){return List.of();}
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> control:maps(config.get("quick_controls"))){if(!active.equals(String.valueOf(control.getOrDefault("route_id",active))))continue;
            String nodeId=String.valueOf(control.getOrDefault("node_id","")),parameter=String.valueOf(control.getOrDefault("parameter_id",""));GraphNode node=graph.nodes().stream().filter(candidate->candidate.id().equals(nodeId)).findFirst().orElse(null);if(node==null)continue;
            String kind=controlKind(node.type(),parameter);if(kind.isBlank())continue;Object value=node.parameters().get(parameter);if(value==null)continue;
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",control.get("id"));item.put("nodeId",nodeId);item.put("parameterId",parameter);item.put("label",control.getOrDefault("label",node.name()));item.put("section",control.getOrDefault("section","Audio Matrix"));item.put("order",control.getOrDefault("order",0));item.put("kind",kind);item.put("value",value);
            item.put("groupId",control.getOrDefault("group_id",control.getOrDefault("section","routing")));
            if("gain".equals(node.type())&&"gain".equals(parameter))item.put("muted",Boolean.TRUE.equals(node.parameters().get("muted")));
            if("links".equals(kind)&&node instanceof RouterNode router){
                List<String> wiredInputs=graph.connections().stream().filter(edge->edge.enabled()&&edge.toNode().equals(router.id())).map(edge->edge.toPort()).distinct().toList();
                List<String> wiredOutputs=graph.connections().stream().filter(edge->edge.enabled()&&edge.fromNode().equals(router.id())).map(edge->edge.fromPort()).distinct().toList();
                List<Map<String,Object>> options=new ArrayList<>();for(var input:router.inputs())if(wiredInputs.contains(input.id()))for(var output:router.outputs())if(wiredOutputs.contains(output.id())){String link=input.id()+"->"+output.id();options.add(Map.of("id",link,"label",input.id()+" → "+output.id()));}item.put("options",options);item.put("multiple",router.multiple());
            }
            result.add(item);
        }
        result.sort(java.util.Comparator.comparingInt(item->item.get("order") instanceof Number number?number.intValue():0));return List.copyOf(result);
    }
    static String controlKind(String nodeType,String parameter){if("gain".equals(nodeType)&&"gain".equals(parameter))return "slider";if("gain".equals(nodeType)&&"muted".equals(parameter))return "toggle";if("gate".equals(nodeType)&&"open".equals(parameter))return "toggle";if("filter".equals(nodeType)&&"enabled".equals(parameter))return "toggle";if(List.of("switch","multi_switch","selector","multi_selector","matrix").contains(nodeType)&&"active_links".equals(parameter))return "links";return "";}
    private void reemitLatestState(){String payload=lastState;if(!closed.get()&&!payload.isBlank())emit("State",payload);}
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
        try{monitor=new ProcessBuilder("gdbus","monitor","--session","--dest","org.gnome.Shell","--object-path",OBJECT_PATH).redirectErrorStream(true).start();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(monitor.getInputStream()))){String line;List<String> arguments=null;while(!closed.get()&&(line=reader.readLine())!=null){
                if(line.contains("member=Command")||line.contains(INTERFACE+".Command")){
                    Command command=parseCommandLine(line);if(command!=null){dispatch(command);arguments=null;}else arguments=new ArrayList<>();continue;
                }
                if(arguments==null)continue;
                if(line.contains("member=")){arguments=null;continue;}
                Command command=parseCommandLine(line);if(command!=null){dispatch(command);arguments=null;continue;}
                java.util.regex.Matcher number=java.util.regex.Pattern.compile("(?:uint32\\s+)?(\\d+)").matcher(line);
                if(arguments.isEmpty()&&number.find())arguments.add(number.group(1));
                java.util.regex.Matcher string=java.util.regex.Pattern.compile("'((?:\\\\.|[^'])*)'").matcher(line);
                while(string.find()&&arguments.size()<5)arguments.add(unescapeGVariantString(string.group(1)));
                Command split=parseCommandArguments(arguments);if(split!=null){dispatch(split);arguments=null;}
            }}
            if(!closed.get())Thread.sleep(1200);
        }catch(Exception e){if(!closed.get())try{Thread.sleep(2500);}catch(InterruptedException ignored){Thread.currentThread().interrupt();return;}}
    }}
    static Command parseCommandArguments(List<String> arguments){
        if(arguments==null||arguments.size()<4)return null;
        try{return arguments.size()>=5
                ? new Command(Integer.parseInt(arguments.get(0)),arguments.get(1),arguments.get(2),arguments.get(3),arguments.get(4))
                : new Command(Integer.parseInt(arguments.get(0)),arguments.get(1),"",arguments.get(2),arguments.get(3));}
        catch(NumberFormatException invalid){return null;}
    }
    void dispatch(Command command){
        execute(command,commands).thenAccept(result->emitResult(command,result.status(),result.message()));
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
        java.util.regex.Matcher v3=java.util.regex.Pattern.compile("\\((?:uint32\\s+)?(\\d+),\\s*'((?:\\\\.|[^'])*)',\\s*'((?:\\\\.|[^'])*)',\\s*'((?:\\\\.|[^'])*)',\\s*'((?:\\\\.|[^'])*)'\\)").matcher(line.trim());
        if(v3.find())return new Command(Integer.parseInt(v3.group(1)),unescapeGVariantString(v3.group(2)),unescapeGVariantString(v3.group(3)),unescapeGVariantString(v3.group(4)),unescapeGVariantString(v3.group(5)));
        java.util.regex.Matcher v2=java.util.regex.Pattern.compile("\\((?:uint32\\s+)?(\\d+),\\s*'((?:\\\\.|[^'])*)',\\s*'((?:\\\\.|[^'])*)',\\s*'((?:\\\\.|[^'])*)'\\)").matcher(line.trim());
        if(v2.find())return new Command(Integer.parseInt(v2.group(1)),unescapeGVariantString(v2.group(2)),"",unescapeGVariantString(v2.group(3)),unescapeGVariantString(v2.group(4)));
        java.util.regex.Matcher legacy=java.util.regex.Pattern.compile("\\('((?:\\\\.|[^'])*)'(?:,\\s*'((?:\\\\.|[^'])*)')?\\)").matcher(line.trim());
        if(!legacy.find())return null;return new Command(1,"",unescapeGVariantString(legacy.group(1)),legacy.group(2)==null?"":unescapeGVariantString(legacy.group(2)));
    }
    private static String unescapeGVariantString(String value){StringBuilder decoded=new StringBuilder(value.length());for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c!='\\'||i+1>=value.length()){decoded.append(c);continue;}char escaped=value.charAt(++i);decoded.append(switch(escaped){case 'a'->'\u0007';case 'b'->'\b';case 'f'->'\f';case 'n'->'\n';case 'r'->'\r';case 't'->'\t';case 'v'->'\u000b';case '\\'->'\\';case '\''->'\'';case '"'->'"';default->escaped;});}return decoded.toString();}
    private void emit(String signal,String value){try{run(List.of("busctl","--user","emit",OBJECT_PATH,INTERFACE,signal,"s",value),Duration.ofSeconds(2));}catch(Exception ignored){}}
    private void emitResult(Command command,String status,String message){if(command.requestId().isBlank())return;try{run(List.of("busctl","--user","emit",OBJECT_PATH,INTERFACE,"Result","usss",Integer.toString(API_VERSION),command.requestId(),status,message),Duration.ofSeconds(2));}catch(Exception ignored){}}
    private static String safeMessage(Throwable error){Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();String message=cause.getMessage();return message==null||message.isBlank()?cause.getClass().getSimpleName():message;}
    private static String run(List<String>command,Duration timeout)throws Exception{Path out=Files.createTempFile("audio-matrix-dbus-",".out");try{Process p=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile()).start();if(!p.waitFor(timeout.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS)){p.destroyForcibly();throw new IOException("gdbus timed out");}if(p.exitValue()!=0)throw new IOException(Files.readString(out));return Files.readString(out);}finally{Files.deleteIfExists(out);}}
    private static Map<String,Object> activeProfile(Map<String,Object> config){String id=String.valueOf(config.get("active_profile"));Object list=config.get("profiles");if(list instanceof List<?> profiles)for(Object item:profiles)if(item instanceof Map<?,?> map&&id.equals(map.get("id"))){Map<String,Object>copy=new LinkedHashMap<>();map.forEach((k,v)->copy.put(String.valueOf(k),v));return copy;}return Map.of();}
    @Override public void close(){closed.set(true);if(monitor!=null)monitor.destroyForcibly();emitter.shutdownNow();}
    public record Command(int apiVersion,String requestId,String revision,String name,String value){
        public Command(int apiVersion,String requestId,String name,String value){this(apiVersion,requestId,"",name,value);}
        public Command(String name,String value){this(1,"","",name,value);}
    }
    record CommandResult(String status,String message){}
}
