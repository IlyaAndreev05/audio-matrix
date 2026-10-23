package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.RouterNode;

import java.io.IOException;
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
            "setNodeVolume","setNodeMuted","setRouteEnabled","setBrowserTabMuted");
    private final Function<Command,CompletableFuture<Void>> commands;
    private final Function<Command,CompletableFuture<Void>> confirmState;
    private final SignalEmitter signalEmitter;
    private final ObjectMapper json=new ObjectMapper();
    private final List<BrowserAdapter> browserAdapters;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicBoolean started=new AtomicBoolean();
    private final GnomeBridgeStateSequencer stateSequencer=new GnomeBridgeStateSequencer(json);
    private CompletableFuture<Void> commandTail=CompletableFuture.completedFuture(null);
    private final ScheduledExecutorService emitter=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"audio-matrix-dbus-status");t.setDaemon(true);return t;});
    private final Object stateSignalLock=new Object();
    private final GnomeBridgeCommandChannel commandChannel;
    private final boolean requireCommandListener;
    public void publishState(PipeWireBackend.Snapshot snapshot,Map<String,Object> config,long configRevision,
                             DynamicInstanceRegistry.ReconcileResult graphState){
        emitObservedState(stateProjection(snapshot,config,configRevision,browserAdapters,graphState));
    }
    public static Map<String,Object> stateProjection(PipeWireBackend.Snapshot snapshot,Map<String,Object> config,
            long configRevision,List<BrowserAdapter> browserAdapters,
            DynamicInstanceRegistry.ReconcileResult graphState){
        return GnomeBridgeStateBuilder.build(snapshot,config,configRevision,browserAdapters,graphState);
    }
    public GnomeControlBridge(Function<Command,CompletableFuture<Void>> commands,
                              List<BrowserAdapter> browserAdapters){
        this(commands,browserAdapters,ignored->CompletableFuture.completedFuture(null));
    }
    public GnomeControlBridge(Function<Command,CompletableFuture<Void>> commands,
                              List<BrowserAdapter> browserAdapters,
                              Function<Command,CompletableFuture<Void>> confirmState){
        this(commands,browserAdapters,confirmState,GnomeControlBridge::emitOnBus,true);
    }
    // The injected sink is ungated until start, keeping command-order unit tests bus-independent.
    GnomeControlBridge(Function<Command,CompletableFuture<Void>> commands,
                       List<BrowserAdapter> browserAdapters,
                       Function<Command,CompletableFuture<Void>> confirmState,SignalEmitter signalEmitter){
        this(commands,browserAdapters,confirmState,signalEmitter,false);
    }
    GnomeControlBridge(Function<Command,CompletableFuture<Void>> commands,
                       List<BrowserAdapter> browserAdapters,
                       Function<Command,CompletableFuture<Void>> confirmState,SignalEmitter signalEmitter,
                       boolean requireCommandListener){
        this.commands=commands==null?ignored->CompletableFuture.completedFuture(null):commands;
        this.confirmState=java.util.Objects.requireNonNull(confirmState);
        this.signalEmitter=java.util.Objects.requireNonNull(signalEmitter);
        this.browserAdapters=List.copyOf(browserAdapters==null?List.of():browserAdapters);
        this.requireCommandListener=requireCommandListener;
        this.commandChannel=new GnomeBridgeCommandChannel(this::dispatch,this::reemitLatestState);
    }
    @FunctionalInterface
    interface SignalEmitter {
        void emit(String signal,String signature,List<String> arguments);
    }
    public void start(){if(!started.compareAndSet(false,true))return;commandChannel.start();emitter.scheduleAtFixedRate(this::reemitLatestState,4,4,TimeUnit.SECONDS);}
    CompletableFuture<Void> commandListenerReady(){return commandChannel.readyFuture();}
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
    private void reemitLatestState(){
        synchronized(stateSignalLock){String payload=stateSequencer.latest();if(!payload.isBlank())emitStateWhenReady(payload);}
    }
    private static String language(Map<String,Object> config){Object ui=config.get("ui");if(ui instanceof Map<?,?> preferences&&"en".equals(preferences.get("language")))return "en";return "ru";}
    private static List<Map<String,Object>> maps(Object value){if(!(value instanceof List<?> list))return List.of();List<Map<String,Object>>result=new ArrayList<>();for(Object item:list)if(item instanceof Map<?,?> raw){Map<String,Object>copy=new LinkedHashMap<>();raw.forEach((key,entry)->copy.put(String.valueOf(key),entry));result.add(copy);}return result;}
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
    synchronized CompletableFuture<Void> dispatch(Command command){
        CompletableFuture<CommandResult> execution=commandTail.thenCompose(ignored->execute(command,commands))
                .thenCompose(result->confirmCommandState(command,result));
        commandTail=execution.handle((result,error)->{
            CommandResult confirmed=error==null&&result!=null?result:new CommandResult("error",error==null?"Command handler returned no result":safeMessage(error));
            emitAcknowledgment(command,confirmed);
            if(!closed.get())emitter.execute(()->emitResult(command,confirmed.status(),confirmed.message()));
            return null;
        });
        return commandTail;
    }
    private CompletableFuture<CommandResult> confirmCommandState(Command command,CommandResult result){
        try{
            CompletableFuture<Void> confirmed=confirmState.apply(command);
            if(confirmed==null)throw new IllegalStateException("State publisher returned no completion result");
            return confirmed.handle((ignored,error)->error==null?result:
                    new CommandResult("error","Could not publish confirmed state: "+safeMessage(error)));
        }catch(RuntimeException error){
            return CompletableFuture.completedFuture(new CommandResult("error","Could not publish confirmed state: "+safeMessage(error)));
        }
    }
    synchronized void emitObservedState(Map<String,Object> state){
        synchronized(stateSignalLock){String payload=stateSequencer.observe(state);if(!payload.isBlank())emitStateWhenReady(payload);}
    }
    private void emitAcknowledgment(Command command,CommandResult result){
        if(command.requestId().isBlank())return;
        synchronized(stateSignalLock){String payload=stateSequencer.acknowledge(GnomeBridgeCommandAck.create(command,result,json));if(!payload.isBlank())emitStateWhenReady(payload);}
    }
    private boolean stateSignalListenerReady(){
        return (!requireCommandListener&&!started.get())||commandChannel.isReady();
    }
    private void emitStateWhenReady(String payload){
        if(closed.get()||!stateSignalListenerReady())return;
        emitter.execute(()->{if(!closed.get()&&stateSignalListenerReady())emit("State",payload);});
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
    private void emit(String signal,String value){signalEmitter.emit(signal,"s",List.of(value));}
    private void emitResult(Command command,String status,String message){
        if(command.requestId().isBlank())return;
        signalEmitter.emit("Result","usss",List.of(Integer.toString(API_VERSION),command.requestId(),status,message));
    }
    private static void emitOnBus(String signal,String signature,List<String> arguments){
        List<String> command=new ArrayList<>(List.of("busctl","--user","emit",OBJECT_PATH,INTERFACE,signal,signature));
        command.addAll(arguments);
        try{run(command,Duration.ofSeconds(2));}catch(Exception ignored){}
    }
    private static String safeMessage(Throwable error){Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();String message=cause.getMessage();return message==null||message.isBlank()?cause.getClass().getSimpleName():message;}
    private static String run(List<String>command,Duration timeout)throws Exception{Path out=Files.createTempFile("audio-matrix-dbus-",".out");try{Process p=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile()).start();if(!p.waitFor(timeout.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS)){p.destroyForcibly();throw new IOException("gdbus timed out");}if(p.exitValue()!=0)throw new IOException(Files.readString(out));return Files.readString(out);}finally{Files.deleteIfExists(out);}}
    @Override public void close(){closed.set(true);commandChannel.close();emitter.shutdownNow();}
    public record Command(int apiVersion,String requestId,String revision,String name,String value){
        public Command(int apiVersion,String requestId,String name,String value){this(apiVersion,requestId,"",name,value);}
        public Command(String name,String value){this(API_VERSION,"","",name,value);}
    }
    record CommandResult(String status,String message){}
}
