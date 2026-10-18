package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;

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

abstract class PipeWireRoutePart04 extends PipeWireRoutePart03 {
    private static boolean isTransientShellSound(AudioNode stream) {
        String app = stream.applicationName() == null ? "" : stream.applicationName();
        String process = stream.processName() == null ? "" : stream.processName();
        return "gnome-shell".equalsIgnoreCase(app) || "gnome-shell".equalsIgnoreCase(process)
                || process.startsWith("gsd-") || "xdg-desktop-portal".equalsIgnoreCase(app);
    }

    protected Plan plan(Map<String, Object> config, PipeWireBackend.Snapshot snapshot) throws IOException {
        Map<String, Object> profile = maps(config.get("profiles")).stream()
                .filter(item -> String.valueOf(config.get("active_profile")).equals(item.get("id")))
                .findFirst().orElseGet(() -> maps(config.get("profiles")).stream().findFirst().orElse(Map.of()));
        String profileMode = text(profile.get("mode"), "hybrid");
        boolean profileOff = "off".equals(profileMode);
        if (profileOff) return new Plan(Map.of(), List.of(), List.of(), Map.of(), List.of(), true, 1.0, false);
        Set<String> activeBuses = new HashSet<>(strings(profile.get("active_buses")));
        Set<String> activeRoutes = new HashSet<>(strings(profile.get("active_routes")));
        Map<String, Map<String, Object>> busConfig = new LinkedHashMap<>();
        for (Map<String, Object> bus : maps(config.get("buses"))) busConfig.put(text(bus.get("id")), bus);
        List<AudioNode> physicalSinks = snapshot.sinks().stream()
                .filter(node -> !node.name().startsWith("audiomatrix_")).toList();
        List<Map<String, Object>> devices = maps(config.get("devices"));
        List<String> warnings = new ArrayList<>();
        unavailableOutputs = 0;
        conflictingOutputs = 0;
        Set<Integer> vibroSinkIds = new HashSet<>(), ordinarySinkIds = new HashSet<>();
        List<RoutePlan> routes = new ArrayList<>();
        Map<String, Set<String>> sourceRolesByBus = new HashMap<>();
        for (String busId : activeBuses) {
            sourceRolesByBus.put(busId, sourceRootRolesForBus(busId, busConfig, maps(config.get("routes")),
                    activeBuses, activeRoutes, new HashSet<>()));
        }
        for (Map<String, Object> route : maps(config.get("routes"))) {
            String id = text(route.get("id"));
            if (!Boolean.TRUE.equals(route.get("enabled")) || !activeRoutes.contains(id)) continue;
            String from = text(route.get("from")), to = text(route.get("to"));
            if (!activeBuses.contains(from)) continue;
            Map<String, Object> bus = busConfig.get(from);
            if (bus == null) { warnings.add("route " + id + " references a missing source bus"); continue; }
            String targetBus = busConfig.containsKey(to) ? to : null;
            AudioNode sink = null;
            if (targetBus != null) {
                if (!activeBuses.contains(targetBus)) { warnings.add("route " + id + " targets an inactive bus"); continue; }
            } else {
                sink = resolveSink(to, profile, physicalSinks, devices, snapshot.defaultSinkId());
                if (sink == null && route.get("fallback") != null) {
                    String fallback = text(route.get("fallback"));
                    sink = resolveSink(fallback, profile, physicalSinks, devices, snapshot.defaultSinkId());
                    if (sink != null) warnings.add("route " + id + " is using fallback output " + fallback);
                }
                if (sink == null) {
                    unavailableOutputs++;
                    warnings.add("route " + id + " has no available output or fallback");
                    continue;
                }
                String outputRole = configuredOutputRole(sink, devices);
                String sourceRole = text(bus.get("role"), ProfileRoutePolicy.builtInBusRole(from));
                ProfileRoutePolicy.Decision policy = ProfileRoutePolicy.evaluate(profileMode, sourceRole,
                        sourceRolesByBus.getOrDefault(from, Set.of()), outputRole, id);
                if (!policy.allowed()) { warnings.add(policy.warning()); continue; }
            }
            double gain = number(route.get("gain_db"), 0);
            if (gain > 0 && !limiterEnabled(config)) throw new IOException("Route '" + id + "' requests positive gain; enable the limiter DSP before applying it");
            String captureNode = routeNodeName("capture", id);
            String playbackNode = routeNodeName("playback", id);
            ChannelMatrix channelMatrix = null;
            if (route.containsKey("channel_matrix")) {
                try { channelMatrix = ChannelMatrix.parse(route.get("channel_matrix")); }
                catch (IllegalArgumentException error) { throw new IOException("Invalid channel matrix for route " + id + ": " + error.getMessage(), error); }
                List<String> sourcePositions = positions(text(bus.get("channels"), "stereo"));
                if (!channelMatrix.inputs().equals(sourcePositions))
                    throw new IOException("Channel matrix input positions do not match source bus " + from);
                List<String> targetPositions = targetBus != null
                        ? positions(text(busConfig.get(targetBus).get("channels"), "stereo"))
                        : streamPositions(sink.channels());
                if (!targetPositions.containsAll(channelMatrix.outputs())) {
                    warnings.add("route " + id + " channel matrix targets channels unavailable on "
                            + (targetBus != null ? "bus " + targetBus : sink.description()));
                    continue;
                }
                if (channelMatrix.maximumRowGain() > 1.0 + 1e-9 && !limiterEnabled(config))
                    throw new IOException("Route '" + id + "' channel matrix can increase signal level; enable the output limiter before applying it");
            }
            List<String> positions = channelMatrix == null
                    ? routePositions(text(route.get("channel_map"), "stereo")) : channelMatrix.outputs();
            List<Map<String, Object>> filters = routeFilters(route, config);
            if (!filters.isEmpty()) PipeWireFilterChainConfig.render("Audio Matrix route " + id,
                    routeNodeName("dsp_input", id), routeNodeName("dsp_output", id), positions, filters, limiterEnabled(config));
            if (channelMatrix != null) PipeWireFilterChainConfig.renderMatrix("Audio Matrix channel matrix " + id,
                    routeNodeName("matrix_input", id), routeNodeName("matrix_output", id), channelMatrix);
            if (sink != null) {
                Set<String> roots = sourceRolesByBus.getOrDefault(from, Set.of());
                boolean vibro = "vibro".equals(route.get("role")) || "octine-vibro-output".equals(to)
                        || "haptic_output".equals(configuredOutputRole(sink, devices)) || roots.contains("effects");
                if (vibro) vibroSinkIds.add(sink.id()); else ordinarySinkIds.add(sink.id());
            }
            routes.add(new RoutePlan(id, from, targetBus, sink, text(route.get("channel_map"), "stereo"), positions, channelMatrix,
                    number(route.get("volume"), 1), gain, number(route.get("delay_ms"), 0),
                    Boolean.TRUE.equals(route.get("mute")), (int) number(route.get("priority"), 0),
                    filters, captureNode, playbackNode, routeNodeName("dsp_input", id), routeNodeName("dsp_output", id),
                    routeNodeName("matrix_input", id), routeNodeName("matrix_output", id)));
        }
        vibroSinkIds.retainAll(ordinarySinkIds);
        conflictingOutputs = vibroSinkIds.size();
        routes.removeIf(route -> {
            if (route.sink() == null || !vibroSinkIds.contains(route.sink().id())) return false;
            warnings.add("route " + route.id() + " skipped: Vibro and ordinary audio share physical output; select separate outputs");
            return true;
        });
        routes.sort(Comparator.comparingInt(RoutePlan::priority).reversed());
        Map<String, List<RoutePlan>> outgoing = new LinkedHashMap<>();
        for (RoutePlan route : routes) outgoing.computeIfAbsent(route.fromBusId(), ignored -> new ArrayList<>()).add(route);
        Set<String> reachable = new HashSet<>();
        for (String busId : busConfig.keySet()) if (hasOutputPath(busId, outgoing, new HashSet<>())) reachable.add(busId);
        routes.removeIf(route -> !reachable.contains(route.fromBusId()) || (route.targetBusId() != null && !reachable.contains(route.targetBusId())));
        outgoing.replaceAll((busId, branches) -> branches.stream().filter(routes::contains).toList());
        if ("subwoofer".equals(profileMode)) {
            Set<String> incomingBuses = routes.stream().map(RoutePlan::targetBusId).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            List<String> roots = activeBuses.stream().filter(busConfig::containsKey).filter(busId -> !incomingBuses.contains(busId)).toList();
            if (roots.isEmpty()) roots = activeBuses.stream().filter(busConfig::containsKey).toList();
            Set<String> warnedSinks = new HashSet<>();
            for (RoutePlan route : routes) {
                if (route.sink() == null || !warnedSinks.add(route.sink().name())) continue;
                if (hasLowPass(outputFilters(route.sink(), devices, config))) continue;
                boolean unfilteredPath = roots.stream().anyMatch(root -> hasUnfilteredPathToSink(root, route.sink().name(),
                        outgoing, false, new HashSet<>()));
                if (unfilteredPath) warnings.add("profile mode subwoofer selects " + route.sink().description()
                        + " but one or more active paths have no low_pass; crossover is not generated automatically");
            }
        }
        if ("multi-output".equals(profileMode) && routes.stream().filter(route -> route.sink() != null)
                .map(route -> route.sink().name()).distinct().count() < 2) {
            warnings.add("profile mode multi-output needs routes to at least two distinct outputs");
        }
        Map<String, OutputPlan> outputPlans = new LinkedHashMap<>();
        for (RoutePlan route : routes) {
            if (route.sink() == null || outputPlans.containsKey(route.sink().name())) continue;
            List<Map<String, Object>> filters = outputFilters(route.sink(), devices, config);
            List<Map<String, Object>> limiterFilters = outputLimiterFilters(config, configuredOutputDeviceId(route.sink(), devices));
            if (filters.isEmpty() && limiterFilters.isEmpty()) continue;
            List<String> positions = streamPositions(route.sink().channels());
            String input = outputNodeName("dsp_input", route.sink().name());
            String output = outputNodeName("dsp_output", route.sink().name());
            if (!filters.isEmpty()) PipeWireFilterChainConfig.render("Audio Matrix physical output EQ " + route.sink().description(),
                    input, output, positions, filters, limiterEnabled(config));
            String limiterInput = outputNodeName("limiter_input", route.sink().name());
            String limiterOutput = outputNodeName("limiter_output", route.sink().name());
            if (!limiterFilters.isEmpty()) PipeWireFilterChainConfig.render("Audio Matrix physical output limiter " + route.sink().description(),
                    limiterInput, limiterOutput, positions, limiterFilters, true);
            outputPlans.put(route.sink().name(), new OutputPlan(route.sink(), positions, filters, limiterFilters,
                    input, output, limiterInput, limiterOutput));
        }
        Set<String> neededBusIds = new LinkedHashSet<>();
        routes.forEach(route -> { neededBusIds.add(route.fromBusId()); if (route.targetBusId() != null) neededBusIds.add(route.targetBusId()); });
        Map<String, Object> mixer = object(config.get("mixer"));
        double masterVolume = number(mixer.get("global_volume"), 1.0);
        boolean masterMuted = Boolean.TRUE.equals(mixer.get("global_mute"));
        Map<String, BusPlan> buses = new LinkedHashMap<>();
        for (String id : neededBusIds) {
            Map<String, Object> bus = busConfig.get(id);
            String layout = text(bus.get("channels"), "stereo");
            buses.put(id, new BusPlan(id, busNodeName(id), layout, positions(layout),
                    number(bus.get("volume"), 1), Boolean.TRUE.equals(bus.get("mute"))));
        }
        List<StreamPlan> streams = new ArrayList<>();
        if (!profileOff && !routes.isEmpty()) {
            List<String> eventInputBuses = EventInputStreamPlanner.append(config, buses, streams, this::applicationFilters);
            boolean builtin = GraphCommonVibro.isBuiltinRoute(text(config.get("active_route")));
            if (!builtin) for (AudioNode stream : snapshot.streams()) {
                if (isTransientShellSound(stream)) continue;
                boolean eventStream = isEventSoundStudioStream(stream);
                if (eventStream && !eventInputBuses.isEmpty()) continue;
                Map<String, Object> rule = eventStream
                        ? explicitEventApplicationRule(maps(config.get("applications")))
                        : ApplicationRuleMatcher.find(maps(config.get("applications")), stream);
                if (rule == null && !eventStream) {
                    String defaultBus = text(config.get("graph_default_bus"));
                    if (!defaultBus.isBlank() && busConfig.containsKey(defaultBus)) rule = Map.of("bus", defaultBus);
                }
                if (rule == null) continue;
                String busId = text(rule.get("bus"));
                if (!buses.containsKey(busId)) continue;
                List<Map<String, Object>> filters = applicationFilters(rule, config);
                List<String> positions = streamPositions(stream.channels());
                String key = "application:" + stream.name();
                String input = applicationNodeName("dsp_input", stream.name());
                String output = applicationNodeName("dsp_output", stream.name());
                if (!filters.isEmpty()) PipeWireFilterChainConfig.render("Audio Matrix application EQ " + stream.applicationName(),
                        input, output, positions, filters, limiterEnabled(config));
                streams.add(new StreamPlan(stream, busId, filters, positions, key, input, output));
            }
        }
        return new Plan(buses, routes, streams, outputPlans, warnings, profileOff, masterVolume, masterMuted);
    }
}
