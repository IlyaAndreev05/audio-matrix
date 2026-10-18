package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Applies explicitly requested Audio Matrix routing by owning PipeWire loopback nodes and links. */

abstract class PipeWireRoutePart05 extends PipeWireRoutePart04 {
    protected void updateLiveLevels(Plan previous, Plan plan) throws Exception {
        for (BusPlan bus : plan.buses().values()) {
            int id = findNodeId(bus.nodeName());
            run(List.of("wpctl", "set-volume", Integer.toString(id), Double.toString(bus.volume())), COMMAND_TIMEOUT);
            run(List.of("wpctl", "set-mute", Integer.toString(id), bus.muted() ? "1" : "0"), COMMAND_TIMEOUT);
        }
        for (RoutePlan route : routeLevelUpdateOrder(previous, plan)) {
            int id = findNodeId(route.playbackNodeName());
            double volume = effectiveRoutePlaybackVolume(plan, route);
            boolean muted = effectiveRoutePlaybackMuted(plan, route);
            run(List.of("wpctl", "set-volume", Integer.toString(id), Double.toString(muted ? 0.0 : volume),
                    "--limit", Double.toString(MixLevelMath.maxRouteVolume())), COMMAND_TIMEOUT);
            run(List.of("wpctl", "set-mute", Integer.toString(id), muted ? "1" : "0"), COMMAND_TIMEOUT);
        }
    }

    protected List<Map<String, Object>> routeFilters(Map<String, Object> route, Map<String, Object> config) throws IOException {
        List<Map<String, Object>> filters = new ArrayList<>();
        Map<String, Object> dsp = object(config.get("dsp"));

        String sourceBusId = text(route.get("from"));
        Map<String, Object> sourceBus = maps(config.get("buses")).stream()
                .filter(bus -> sourceBusId.equals(text(bus.get("id")))).findFirst().orElse(Map.of());
        String busFiltersRef = text(sourceBus.get("filters_ref"));
        appendReferencedFilters(filters, dsp, busFiltersRef, text(route.get("id")), "bus " + sourceBusId, limiterEnabled(config));

        String filtersRef = text(route.get("filters_ref"));
        if (!filtersRef.equals(busFiltersRef)) appendReferencedFilters(filters, dsp, filtersRef, text(route.get("id")), "route", limiterEnabled(config));

        String polarity = text(route.get("polarity"), "normal");
        if ("invert".equals(polarity)) filters.add(Map.of("type", "phase_invert", "enabled", true));
        else if ("invert_left".equals(polarity)) filters.add(Map.of("type", "phase_invert", "enabled", true, "channels", List.of("FL")));
        else if ("invert_right".equals(polarity)) filters.add(Map.of("type", "phase_invert", "enabled", true, "channels", List.of("FR")));
        return List.copyOf(filters);
    }

    protected List<Map<String, Object>> outputFilters(AudioNode sink, List<Map<String, Object>> devices,
                                            Map<String, Object> config) throws IOException {
        List<Map<String, Object>> filters = new ArrayList<>();
        Map<String, Object> dsp = object(config.get("dsp"));
        Map<String, Object> globalEq = object(dsp.get("global_eq"));
        if (!Boolean.TRUE.equals(globalEq.get("enabled"))) globalEq = object(object(config.get("mixer")).get("global_eq"));
        appendEnabledFilters(filters, globalEq, "global EQ", limiterEnabled(config));

        String deviceId = null;
        Map<String, Object> outputDevice = Map.of();
        for (Map<String, Object> device : devices) {
            if (DeviceSelectorMatcher.matches(sink, object(device.get("selector")))) {
                deviceId = text(device.get("id"));
                outputDevice = device;
                break;
            }
        }
        if (deviceId != null) {
            appendEnabledFilters(filters, object(object(dsp.get("devices")).get(deviceId)), "output correction " + deviceId, limiterEnabled(config));
            appendReferencedFilters(filters, dsp, text(outputDevice.get("filters_ref"), ""),
                    "output " + deviceId, "physical output", limiterEnabled(config));
        }
        return List.copyOf(filters);
    }

    protected List<Map<String, Object>> outputLimiterFilters(Map<String, Object> config) {
        return outputLimiterFilters(config, "");
    }

    protected List<Map<String, Object>> outputLimiterFilters(Map<String, Object> config, String outputDeviceId) {
        Map<String, Object> dsp = object(config.get("dsp"));
        Map<String, Object> outputLimiters = object(dsp.get("output_limiters"));
        Map<String, Object> outputLimiter = object(outputLimiters.get(outputDeviceId));
        Map<String, Object> limiter = Boolean.TRUE.equals(outputLimiter.get("enabled"))
                ? outputLimiter : object(dsp.get("limiter"));
        if (!Boolean.TRUE.equals(limiter.get("enabled"))) return List.of();
        return List.of(Map.of("type", "limiter", "enabled", true,
                "ceiling_db", number(limiter.get("ceiling_db"), -1),
                "lookahead_ms", number(limiter.get("lookahead_ms"), 5),
                "release_ms", number(limiter.get("release_ms"), 50)));
    }

    protected List<Map<String, Object>> applicationFilters(Map<String, Object> rule, Map<String, Object> config) throws IOException {
        String filtersRef = text(rule.get("filters_ref"));
        if (filtersRef.isBlank()) return List.of();
        String prefix = "dsp.route_chains.";
        if (!filtersRef.startsWith(prefix) || filtersRef.length() == prefix.length())
            throw new IOException("Application rule has an unsupported filters_ref: " + filtersRef);
        String chainId = filtersRef.substring(prefix.length());
        Map<String, Object> chains = object(object(config.get("dsp")).get("route_chains"));
        if (!chains.containsKey(chainId)) throw new IOException("Application rule references a missing DSP chain " + chainId);
        List<Map<String, Object>> filters = new ArrayList<>();
        appendEnabledFilters(filters, object(chains.get(chainId)), "application EQ chain " + chainId, limiterEnabled(config));
        return List.copyOf(filters);
    }

    protected AudioNode resolveSink(String ref, Map<String, Object> profile, List<AudioNode> sinks,
                                  List<Map<String, Object>> devices, int defaultSinkId) {
        if ("system_default".equals(ref)) return sinks.stream().filter(node -> node.id() == defaultSinkId).findFirst().orElse(null);
        return DeviceSelectorMatcher.resolve(sinks, ref, devices);
    }

    protected Set<String> sourceRootRolesForBus(String busId, Map<String, Map<String, Object>> busConfig,
                                              List<Map<String, Object>> configuredRoutes, Set<String> activeBuses,
                                              Set<String> activeRoutes, Set<String> visiting) {
        if (!activeBuses.contains(busId) || !visiting.add(busId)) return Set.of();
        List<Map<String, Object>> incoming = configuredRoutes.stream().filter(route -> activeRoutes.contains(text(route.get("id")))
                && Boolean.TRUE.equals(route.get("enabled")) && !Boolean.TRUE.equals(route.get("mute"))
                && number(route.get("volume"), 1) > 0 && busConfig.containsKey(text(route.get("from")))
                && activeBuses.contains(text(route.get("from"))) && busId.equals(route.get("to"))).toList();
        if (incoming.isEmpty()) {
            Map<String, Object> bus = busConfig.get(busId);
            return Set.of(text(bus.get("role"), ProfileRoutePolicy.builtInBusRole(busId)));
        }
        Set<String> roots = new LinkedHashSet<>();
        for (Map<String, Object> route : incoming) roots.addAll(sourceRootRolesForBus(text(route.get("from")), busConfig,
                configuredRoutes, activeBuses, activeRoutes, new HashSet<>(visiting)));
        return Set.copyOf(roots);
    }

    protected void startBus(BusPlan bus) throws Exception {
        if (busProcesses.containsKey(bus.id()) && busProcesses.get(bus.id()).process().isAlive()) return;
        String propsIn = json.writeValueAsString(Map.of("node.name", bus.nodeName(), "node.description", "Audio Matrix bus " + bus.id(),
                "media.class", "Audio/Sink", "audio.position", bus.positions(), "node.virtual", true));
        String playbackName = bus.nodeName() + "_discard";
        String propsOut = json.writeValueAsString(Map.of("node.name", playbackName, "node.description", "Audio Matrix bus internal output",
                "node.autoconnect", false, "node.passive", true, "stream.dont-remix", true, "audio.position", bus.positions()));
        List<String> command = List.of("pw-loopback", "-n", bus.nodeName(), "-l", "10", "-c", Integer.toString(bus.positions().size()), "-m", positionArg(bus.positions()), "-i", propsIn, "-o", propsOut);
        ManagedProcess process = startProcess(command, bus.nodeName());
        busProcesses.put(bus.id(), process);
        awaitPorts(bus.nodeName(), "-i", process.process());
        awaitPorts(bus.nodeName(), "-o", process.process());
    }

    protected void startRoute(RoutePlan route) throws Exception {
        if (routeProcesses.containsKey(route.id()) && routeProcesses.get(route.id()).process().isAlive()) return;
        List<String> routePositions = route.positions();
        String inProps = json.writeValueAsString(Map.of("node.name", route.captureNodeName(), "node.description", "Audio Matrix route capture " + route.id(),
                "node.autoconnect", false, "node.passive", true, "audio.position", routePositions));
        String outProps = json.writeValueAsString(Map.of("node.name", route.playbackNodeName(), "node.description", "Audio Matrix route playback " + route.id(),
                "node.autoconnect", false, "node.passive", true, "stream.dont-remix", true, "audio.position", routePositions));
        List<String> command = new ArrayList<>(List.of("pw-loopback", "-n", route.captureNodeName(), "-l", "10", "-c", Integer.toString(routePositions.size()), "-m", positionArg(routePositions), "-d", Double.toString(route.delayMs() / 1000.0), "-i", inProps, "-o", outProps));
        ManagedProcess process = startProcess(command, route.captureNodeName());
        routeProcesses.put(route.id(), process);
        awaitPorts(route.captureNodeName(), "-i", process.process());
        awaitPorts(route.playbackNodeName(), "-o", process.process());
        findNodeId(route.playbackNodeName());
    }

    protected void startFilterChain(RoutePlan route) throws Exception {
        startFilterChain("route:" + route.id(), "Audio Matrix route " + route.id(), route.filterInputNodeName(),
                route.filterOutputNodeName(), route.positions(), route.filters());
    }

    protected void startChannelMatrix(RoutePlan route) throws Exception {
        String processKey = "route-matrix:" + route.id();
        ManagedProcess existing = filterChainProcesses.get(processKey);
        if (existing != null && existing.process().isAlive()) return;
        String config = PipeWireFilterChainConfig.renderMatrix("Audio Matrix channel matrix " + route.id(),
                route.matrixInputNodeName(), route.matrixOutputNodeName(), route.channelMatrix());
        Path configFile = Files.createTempFile("audio-matrix-matrix-", ".conf");
        try {
            Files.writeString(configFile, config);
            ManagedProcess process = startProcess(List.of("pipewire", "-c", configFile.toString()), route.matrixInputNodeName(), configFile, null);
            filterChainProcesses.put(processKey, process);
            awaitPorts(route.matrixInputNodeName(), "-i", process.process());
            awaitPorts(route.matrixOutputNodeName(), "-o", process.process());
        } catch (Exception error) {
            if (!filterChainProcesses.containsKey(processKey)) Files.deleteIfExists(configFile);
            throw error;
        }
    }

    protected void startFilterChain(StreamPlan stream) throws Exception {
        startFilterChain(stream.filterProcessKey(), "Audio Matrix application EQ " + stream.node().applicationName(),
                stream.filterInputNodeName(), stream.filterOutputNodeName(), stream.positions(), stream.filters());
    }

    protected void startFilterChain(OutputPlan output) throws Exception {
        startFilterChain("output:" + output.sink().name(), "Audio Matrix physical output EQ " + output.sink().description(),
                output.filterInputNodeName(), output.filterOutputNodeName(), output.positions(), output.filters());
    }

    protected void startOutputLimiter(OutputPlan output) throws Exception {
        startFilterChain("output-limiter:" + output.sink().name(), "Audio Matrix physical output limiter " + output.sink().description(),
                output.limiterInputNodeName(), output.limiterOutputNodeName(), output.positions(), output.limiterFilters());
    }
}
