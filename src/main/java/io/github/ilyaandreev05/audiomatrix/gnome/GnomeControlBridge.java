package io.github.ilyaandreev05.audiomatrix.gnome;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceSelectorMatcher;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;

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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** D-Bus signal bridge between the GNOME extension and Java-owned audio actions/state. */
public final class GnomeControlBridge implements AutoCloseable {
    public static final String OBJECT_PATH="/org/ilya/AudioMatrix";
    public static final String INTERFACE="org.ilya.AudioMatrix";
    private final PipeWireBackend backend;
    private final Consumer<Command> commands;
    private final ObjectMapper json=new ObjectMapper();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicBoolean started=new AtomicBoolean();
    private final ScheduledExecutorService emitter=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"audio-matrix-dbus-status");t.setDaemon(true);return t;});
    private volatile Process monitor;
    private volatile String lastState="";
    public GnomeControlBridge(PipeWireBackend backend,Consumer<Command> commands){this.backend=backend;this.commands=commands==null?ignored->{}:commands;}
    public void start(){if(!started.compareAndSet(false,true))return;Thread.ofVirtual().name("audio-matrix-dbus-monitor").start(this::monitorLoop);emitter.scheduleAtFixedRate(this::reemitLatestState,4,4,TimeUnit.SECONDS);}
    public void publishState(PipeWireBackend.Snapshot snapshot,Map<String,Object> config){
        Map<String,Object> profile=activeProfile(config);String configured=String.valueOf(profile.getOrDefault("default_output","system_default"));
        int defaultId=snapshot.defaultSinkId();List<Map<String,Object>> outputs=new ArrayList<>();
        for(AudioNode sink:snapshot.sinks())outputs.add(Map.of("name",sink.name(),"description",sink.description(),"channels",sink.channels(),"default",sink.id()==defaultId,"muted",sink.muted()));
        List<Map<String,Object>> devices=maps(config.get("devices"));
        AudioNode active=DeviceSelectorMatcher.resolve(snapshot.sinks(),configured,devices);if(active==null)active=snapshot.sinks().stream().filter(n->n.id()==defaultId).findFirst().orElse(null);
        Map<String,Object> state=new LinkedHashMap<>();state.put("connected",snapshot.connected());state.put("language",language(config));state.put("outputs",outputs);state.put("selectedOutput",active==null?"system_default":active.name());state.put("muted",active!=null&&active.muted());state.put("mode",profile.getOrDefault("mode","off"));state.put("profile",profile.getOrDefault("name",""));state.put("channelRoutes",channelRoutes(snapshot,config,profile,active,defaultId,devices));
        try{String payload=json.writeValueAsString(state);if(payload.equals(lastState))return;lastState=payload;emitter.execute(()->emit("State",payload));}catch(Exception ignored){}
    }
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
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(monitor.getInputStream()))){String line;boolean expect=false;while(!closed.get()&&(line=reader.readLine())!=null){if(line.contains("member=Command")){expect=true;continue;}if(!expect&&!line.contains(INTERFACE+".Command"))continue;Command command=parseCommandLine(line);if(command!=null){commands.accept(command);expect=false;}}}
            if(!closed.get())Thread.sleep(1200);
        }catch(Exception e){if(!closed.get())try{Thread.sleep(2500);}catch(InterruptedException ignored){Thread.currentThread().interrupt();return;}}
    }}
    static Command parseCommandLine(String line){java.util.regex.Matcher args=java.util.regex.Pattern.compile("\\('((?:\\\\.|[^'])*)'(?:,\\s*'((?:\\\\.|[^'])*)')?\\)").matcher(line.trim());if(!args.find())return null;return new Command(unescapeGVariantString(args.group(1)),args.group(2)==null?"":unescapeGVariantString(args.group(2)));}
    private static String unescapeGVariantString(String value){StringBuilder decoded=new StringBuilder(value.length());for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c!='\\'||i+1>=value.length()){decoded.append(c);continue;}char escaped=value.charAt(++i);decoded.append(switch(escaped){case 'a'->'\u0007';case 'b'->'\b';case 'f'->'\f';case 'n'->'\n';case 'r'->'\r';case 't'->'\t';case 'v'->'\u000b';case '\\'->'\\';case '\''->'\'';case '"'->'"';default->escaped;});}return decoded.toString();}
    private void emit(String signal,String value){try{run(List.of("busctl","--user","emit",OBJECT_PATH,INTERFACE,signal,"s",value),Duration.ofSeconds(2));}catch(Exception ignored){}}
    private static String run(List<String>command,Duration timeout)throws Exception{Path out=Files.createTempFile("audio-matrix-dbus-",".out");try{Process p=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile()).start();if(!p.waitFor(timeout.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS)){p.destroyForcibly();throw new IOException("gdbus timed out");}if(p.exitValue()!=0)throw new IOException(Files.readString(out));return Files.readString(out);}finally{Files.deleteIfExists(out);}}
    private static Map<String,Object> activeProfile(Map<String,Object> config){String id=String.valueOf(config.get("active_profile"));Object list=config.get("profiles");if(list instanceof List<?> profiles)for(Object item:profiles)if(item instanceof Map<?,?> map&&id.equals(map.get("id"))){Map<String,Object>copy=new LinkedHashMap<>();map.forEach((k,v)->copy.put(String.valueOf(k),v));return copy;}return Map.of();}
    @Override public void close(){closed.set(true);if(monitor!=null)monitor.destroyForcibly();emitter.shutdownNow();}
    public record Command(String name,String value){}
}
