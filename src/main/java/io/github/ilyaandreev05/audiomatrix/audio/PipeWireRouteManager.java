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
public final class PipeWireRouteManager implements AutoCloseable {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);
    private static final int START_ATTEMPTS = 30;
    private static final long START_WAIT_MS = 100;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audio-matrix-route-manager"); t.setDaemon(true); return t;
    });
    private final Map<String, ManagedProcess> busProcesses = new LinkedHashMap<>();
    private final Map<String, ManagedProcess> routeProcesses = new LinkedHashMap<>();
    private final Map<String, ManagedProcess> filterChainProcesses = new LinkedHashMap<>();
    private final Map<String, List<Link>> originalStreamLinks = new LinkedHashMap<>();
    private final Set<Link> ownedLinks = new LinkedHashSet<>();
    private volatile boolean active;
    private volatile boolean closed;
    private volatile String status = "Routes are not applied";
    private volatile String appliedSignature = "";
    private volatile Plan appliedPlan;
    private volatile int activeTransitionMs = 120;
    private volatile ApplyReport lastReport = new ApplyReport(0, 0, 0, List.of());
    private final List<String> transitionWarnings = new ArrayList<>();
    private Path bundledLadspaDirectory;
    private CompletableFuture<Void> closeFuture;

    public CompletableFuture<ApplyReport> apply(Map<String, Object> config, PipeWireBackend.Snapshot snapshot) {
        return apply(config, snapshot, false);
    }

    public CompletableFuture<ApplyReport> reapplyAfterReconnect(Map<String, Object> config, PipeWireBackend.Snapshot snapshot) {
        return apply(config, snapshot, true);
    }

    private CompletableFuture<ApplyReport> apply(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                                  boolean reconnect) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Route manager is closed"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (reconnect) active = false;
                return applyNow(config, snapshot);
            }
            catch (Exception e) { status = "Route apply failed: " + message(e); throw new CompletionException(e); }
        }, worker);
    }

    public CompletableFuture<Void> clear() {
        if (closed) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try { clearNow(true); }
            catch (Exception e) { throw new CompletionException(e); }
        }, worker);
    }

    public boolean active() { return active; }
    public String status() { return status; }
    public ApplyReport lastReport() { return lastReport; }

    /** Estimates the maximum configured digital peak from a full-scale routed input. */
    public HeadroomEstimate estimateHeadroom(Map<String, Object> config, PipeWireBackend.Snapshot snapshot, AudioNode sink) throws IOException {
        if (snapshot == null || !snapshot.connected() || sink == null)
            return HeadroomEstimate.unavailable("Choose an available output to calculate configured headroom");
        Plan plan = plan(config, snapshot);
        if (plan.profileOff() || plan.routes().isEmpty())
            return HeadroomEstimate.unavailable("The active profile has no applied route to estimate");
        OutputPlan output = plan.outputs().get(sink.name());
        List<Map<String, Object>> outputFilters = output == null ? List.of() : output.filters();
        Map<String, List<RoutePlan>> outgoing = new LinkedHashMap<>();
        Set<String> targetBuses = new HashSet<>();
        for (RoutePlan route : plan.routes()) {
            outgoing.computeIfAbsent(route.fromBusId(), ignored -> new ArrayList<>()).add(route);
            if (route.targetBusId() != null) targetBuses.add(route.targetBusId());
        }
        double branchGain = 0;
        if (!plan.streams().isEmpty()) {
            for (StreamPlan stream : plan.streams()) {
                Map<String, Object> rule = ApplicationRuleMatcher.find(maps(config.get("applications")), stream.node());
                if (rule == null || Boolean.TRUE.equals(rule.get("mute"))) continue;
                double streamVolume = number(rule.get("volume"), 1.0);
                branchGain += streamVolume * dbToLinear(positiveEqGain(stream.filters()))
                        * pathGain(stream.busId(), sink.name(), plan.buses(), outgoing, new HashSet<>());
            }
        } else {
            for (String busId : plan.buses().keySet()) if (!targetBuses.contains(busId))
                branchGain += pathGain(busId, sink.name(), plan.buses(), outgoing, new HashSet<>());
        }
        if (branchGain <= 0) return HeadroomEstimate.unavailable("No active route reaches the selected output");
        branchGain *= dbToLinear(positiveEqGain(outputFilters));
        double preLimiter = AudioMeterMath.dbfs(branchGain);
        Map<String, Object> limiter = object(object(config.get("dsp")).get("limiter"));
        boolean enabled = Boolean.TRUE.equals(limiter.get("enabled"));
        double ceiling = enabled ? number(limiter.get("ceiling_db"), -1.0) : 0.0;
        double postLimiter = enabled ? Math.min(preLimiter, ceiling) : preLimiter;
        double reduction = enabled ? Math.max(0, preLimiter - ceiling) : 0;
        return new HeadroomEstimate(true, preLimiter, ceiling, postLimiter, reduction,
                "Full-scale input; positive EQ gains and channel-matrix sums modeled coherently; concurrent routes assumed phase-aligned");
    }

    /** Returns a conservative positive EQ gain bound for an EQ-bypassed reference comparison. */
    public double maximumEqBoostDb(Map<String, Object> config, PipeWireBackend.Snapshot snapshot, AudioNode sink) throws IOException {
        if (snapshot == null || !snapshot.connected() || sink == null) return 0;
        Plan plan = plan(config, snapshot);
        if (plan.profileOff() || plan.routes().isEmpty()) return 0;
        OutputPlan output = plan.outputs().get(sink.name());
        double outputBoost = output == null ? 0 : positiveEqGain(output.filters());
        Map<String, List<RoutePlan>> outgoing = new LinkedHashMap<>();
        for (RoutePlan route : plan.routes()) outgoing.computeIfAbsent(route.fromBusId(), ignored -> new ArrayList<>()).add(route);
        double pathBoost = 0;
        for (String busId : plan.buses().keySet())
            pathBoost = Math.max(pathBoost, pathEqGain(busId, sink.name(), plan.buses(), outgoing, new HashSet<>()));
        double appBoost = 0;
        for (StreamPlan stream : plan.streams()) {
            Map<String, Object> rule = ApplicationRuleMatcher.find(maps(config.get("applications")), stream.node());
            if (rule == null || Boolean.TRUE.equals(rule.get("mute"))) continue;
            double appPath = positiveEqGain(stream.filters())
                    + pathEqGain(stream.busId(), sink.name(), plan.buses(), outgoing, new HashSet<>());
            appBoost = Math.max(appBoost, appPath);
        }
        return outputBoost + Math.max(pathBoost, appBoost);
    }

    private static double pathEqGain(String busId, String sinkName, Map<String, BusPlan> buses,
                                     Map<String, List<RoutePlan>> outgoing, Set<String> visiting) {
        BusPlan bus = buses.get(busId);
        if (bus == null || bus.muted() || bus.volume() <= 0 || !visiting.add(busId)) return Double.NEGATIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (RoutePlan route : outgoing.getOrDefault(busId, List.of())) {
            if (route.muted()) continue;
            double downstream = route.targetBusId() == null
                    ? (route.sink() != null && sinkName.equals(route.sink().name()) ? 0 : Double.NEGATIVE_INFINITY)
                    : pathEqGain(route.targetBusId(), sinkName, buses, outgoing, new HashSet<>(visiting));
            if (Double.isFinite(downstream)) maximum = Math.max(maximum, positiveEqGain(route.filters()) + downstream);
        }
        return maximum;
    }

    private static double pathGain(String busId, String sinkName, Map<String, BusPlan> buses,
                                   Map<String, List<RoutePlan>> outgoing, Set<String> visiting) {
        BusPlan bus = buses.get(busId);
        if (bus == null || bus.muted() || bus.volume() <= 0 || !visiting.add(busId)) return 0;
        double branches = 0;
        for (RoutePlan route : outgoing.getOrDefault(busId, List.of())) {
            if (route.muted()) continue;
            int mixedChannels = channelMapSourceCount(route.channelMap(), bus);
            if (mixedChannels == 0) continue;
            double matrixGain = route.channelMatrix() == null ? mixedChannels : route.channelMatrix().maximumRowGain();
            double routeGain = MixLevelMath.effectiveRouteVolume(route.volume(), route.gainDb())
                    * matrixGain * dbToLinear(positiveEqGain(route.filters()));
            if (route.targetBusId() != null)
                branches += routeGain * pathGain(route.targetBusId(), sinkName, buses, outgoing, new HashSet<>(visiting));
            else if (route.sink() != null && sinkName.equals(route.sink().name())) branches += routeGain;
        }
        return bus.volume() * branches;
    }

    private static int channelMapSourceCount(String channelMap, BusPlan bus) {
        List<String> positions = bus.positions();
        return switch (channelMap) {
            case "left" -> positions.contains("FL") ? 1 : positions.contains("MONO") ? 1 : 0;
            case "right" -> positions.contains("FR") ? 1 : positions.contains("MONO") ? 1 : 0;
            case "center" -> positions.contains("FC") ? 1 : 0;
            case "lfe" -> positions.contains("LFE") ? 1 : 0;
            case "rear_left" -> positions.contains("RL") ? 1 : 0;
            case "rear_right" -> positions.contains("RR") ? 1 : 0;
            case "side_left" -> positions.contains("SL") ? 1 : 0;
            case "side_right" -> positions.contains("SR") ? 1 : 0;
            case "mono", "mono_sum" -> (int) positions.stream().filter(Set.of("FL", "FR", "MONO")::contains).count();
            case "rear_sum" -> (int) positions.stream().filter(Set.of("RL", "RR")::contains).count();
            case "side_sum" -> (int) positions.stream().filter(Set.of("SL", "SR")::contains).count();
            case "surround_sum" -> (int) positions.stream().filter(Set.of("RL", "RR", "SL", "SR")::contains).count();
            case "all_sum" -> positions.size();
            default -> 1;
        };
    }

    private static double positiveEqGain(List<Map<String, Object>> filters) {
        double gain = 0;
        for (Map<String, Object> filter : filters) gain += Math.max(0, number(filter.get("gain_db"), 0));
        return gain;
    }

    private static double dbToLinear(double decibels) { return Math.pow(10.0, decibels / 20.0); }

    private ApplyReport applyNow(Map<String, Object> config, PipeWireBackend.Snapshot snapshot) throws Exception {
        if (snapshot == null || !snapshot.connected()) {
            if (active || !busProcesses.isEmpty() || !routeProcesses.isEmpty() || !filterChainProcesses.isEmpty()) clearNow(false);
            originalStreamLinks.clear();
            status = "PipeWire is unavailable; Audio Matrix routes are stopped";
            throw new IOException("PipeWire is not connected");
        }
        requireCommands("pw-loopback", "pw-link", "pw-dump", "wpctl");
        Plan plan = plan(config, snapshot);
        if (plan.routes().stream().anyMatch(route -> !route.filters().isEmpty())
                || plan.routes().stream().anyMatch(route -> route.channelMatrix() != null)
                || plan.outputs().values().stream().anyMatch(output -> !output.filters().isEmpty() || !output.limiterFilters().isEmpty())
                || plan.streams().stream().anyMatch(stream -> !stream.filters().isEmpty())) requireCommands("pipewire");
        if (plan.routes().isEmpty() || plan.profileOff()) {
            clearNow(true);
            List<String> warnings = new ArrayList<>(plan.warnings());
            warnings.addAll(transitionWarnings);
            transitionWarnings.clear();
            lastReport = new ApplyReport(0, 0, 0, warnings);
            status = warnings.isEmpty() ? "No active route reaches an output" : String.join("; ", warnings);
            return lastReport;
        }
        for (RoutePlan route : plan.routes()) {
            if (route.gainDb() > 0 && !limiterEnabled(config))
                throw new IOException("Route '" + route.id() + "' requests positive gain; enable the limiter DSP before applying it");
        }
        String signature = signature(plan, snapshot);
        if (active && signature.equals(appliedSignature) && processesAlive()) {
            activeTransitionMs = transitionMillis(config);
            return reportAppliedPlan(plan);
        }
        int transitionMs = transitionMillis(config);
        int fadeOutMs = active && appliedPlan != null ? transitionMs / 2 : 0;
        int fadeInMs = active && appliedPlan != null ? transitionMs - fadeOutMs : transitionMs;

        Map<String, List<Link>> originalBeforeApply = deepLinks(originalStreamLinks);
        try {
            if (active && appliedPlan != null) fadeRoutePlan(appliedPlan, true, fadeOutMs);
            // Preserve each stream's pre-Audio-Matrix destinations before replacing any links.
            String linkListing = run(List.of("pw-link", "-l"), COMMAND_TIMEOUT);
            for (StreamPlan stream : plan.streams())
                originalStreamLinks.computeIfAbsent(stream.node().name(), ignored -> linksFromNode(linkListing, stream.node().name()));

            stopOwnedProcesses();
            disconnectOwnedLinks();

            Set<String> requiredBusIds = new LinkedHashSet<>();
            plan.routes().forEach(route -> {
                requiredBusIds.add(route.fromBusId());
                if (route.targetBusId() != null) requiredBusIds.add(route.targetBusId());
            });
            for (String busId : requiredBusIds) {
                BusPlan bus = plan.buses().get(busId);
                if (bus == null) throw new IOException("Missing bus " + busId + " in active route plan");
                startBus(bus);
            }
            for (RoutePlan route : plan.routes()) {
                startRoute(route);
                if (route.channelMatrix() != null) startChannelMatrix(route);
                if (!route.filters().isEmpty()) startFilterChain(route);
            }
            for (OutputPlan output : plan.outputs().values()) {
                if (!output.filters().isEmpty()) startFilterChain(output);
                if (!output.limiterFilters().isEmpty()) startOutputLimiter(output);
            }
            for (StreamPlan stream : plan.streams()) if (!stream.filters().isEmpty()) startFilterChain(stream);

            // Apply independent level and mute controls to the bus and route nodes.
            for (String busId : requiredBusIds) {
                BusPlan bus = plan.buses().get(busId);
                int nodeId = findNodeId(bus.nodeName());
                run(List.of("wpctl", "set-volume", Integer.toString(nodeId), Double.toString(bus.volume())), COMMAND_TIMEOUT);
                run(List.of("wpctl", "set-mute", Integer.toString(nodeId), bus.muted() ? "1" : "0"), COMMAND_TIMEOUT);
            }
            for (RoutePlan route : plan.routes()) {
                int nodeId = findNodeId(route.playbackNodeName());
                double volume = MixLevelMath.effectiveRouteVolume(route.volume(), route.gainDb());
                double initialVolume = fadeInMs > 0 || route.muted() ? 0.0 : volume;
                run(List.of("wpctl", "set-volume", Integer.toString(nodeId), Double.toString(initialVolume),
                        "--limit", Double.toString(MixLevelMath.maxRouteVolume())), COMMAND_TIMEOUT);
                run(List.of("wpctl", "set-mute", Integer.toString(nodeId), route.muted() ? "1" : "0"), COMMAND_TIMEOUT);
            }

            Map<String, List<String>> inputs = ports("-i");
            Map<String, List<String>> outputs = ports("-o");
            Map<String, List<RoutePlan>> routesByBus = new LinkedHashMap<>();
            for (RoutePlan route : plan.routes()) routesByBus.computeIfAbsent(route.fromBusId(), ignored -> new ArrayList<>()).add(route);
            for (RoutePlan route : plan.routes()) {
                BusPlan sourceBus = plan.buses().get(route.fromBusId());
                String sourceTarget = route.filters().isEmpty() ? route.captureNodeName() : route.filterInputNodeName();
                if (route.channelMatrix() != null) {
                    List<Link> intoMatrix = positionLinks(outputs.getOrDefault(sourceBus.nodeName(), List.of()),
                            inputs.getOrDefault(route.matrixInputNodeName(), List.of()));
                    if (intoMatrix.size() != route.channelMatrix().inputs().size())
                        throw new IOException("Channel matrix input ports are incomplete for route " + route.id());
                    connectAll(intoMatrix);
                    List<Link> fromMatrix = positionLinks(outputs.getOrDefault(route.matrixOutputNodeName(), List.of()),
                            inputs.getOrDefault(sourceTarget, List.of()));
                    if (fromMatrix.size() != route.positions().size())
                        throw new IOException("Channel matrix output ports are incomplete for route " + route.id());
                    connectAll(fromMatrix);
                } else {
                    List<Link> intoRoute = channelLinks(
                            outputs.getOrDefault(sourceBus.nodeName(), List.of()),
                            inputs.getOrDefault(sourceTarget, List.of()),
                            route.channelMap(), true);
                    connectAll(intoRoute);
                }
                if (!route.filters().isEmpty()) {
                    List<Link> intoDelay = route.channelMatrix() == null
                            ? channelLinks(outputs.getOrDefault(route.filterOutputNodeName(), List.of()),
                                    inputs.getOrDefault(route.captureNodeName(), List.of()), route.channelMap(), false)
                            : positionLinks(outputs.getOrDefault(route.filterOutputNodeName(), List.of()),
                                    inputs.getOrDefault(route.captureNodeName(), List.of()));
                    connectAll(intoDelay);
                }

                List<String> destinationPorts;
                if (route.targetBusId() != null) {
                    BusPlan targetBus = plan.buses().get(route.targetBusId());
                    destinationPorts = inputs.getOrDefault(targetBus.nodeName(), List.of());
                } else {
                    OutputPlan output = plan.outputs().get(route.sink().name());
                    destinationPorts = output == null ? inputs.getOrDefault(route.sink().name(), List.of())
                            : inputs.getOrDefault(output.firstInputNodeName(), List.of());
                }
                List<Link> outOfRoute = route.channelMatrix() == null
                        ? channelLinks(outputs.getOrDefault(route.playbackNodeName(), List.of()), destinationPorts,
                                route.channelMap(), true)
                        : positionLinks(outputs.getOrDefault(route.playbackNodeName(), List.of()), destinationPorts);
                if (outOfRoute.isEmpty()) throw new IOException("No compatible PipeWire ports from route " + route.id() + " to its destination");
                connectAll(outOfRoute);
            }

            for (OutputPlan output : plan.outputs().values()) {
                if (!output.filters().isEmpty()) {
                    String nextInput = output.limiterFilters().isEmpty() ? output.sink().name() : output.limiterInputNodeName();
                    connectAll(channelLinks(outputs.getOrDefault(output.filterOutputNodeName(), List.of()),
                            inputs.getOrDefault(nextInput, List.of()), "stereo", false));
                }
                if (!output.limiterFilters().isEmpty()) connectAll(channelLinks(
                        outputs.getOrDefault(output.limiterOutputNodeName(), List.of()),
                        inputs.getOrDefault(output.sink().name(), List.of()), "stereo", false));
            }

            for (StreamPlan stream : plan.streams()) {
                BusPlan targetBus = plan.buses().get(stream.busId());
                List<Link> previous = originalStreamLinks.getOrDefault(stream.node().name(), List.of());
                disconnectAll(previous);
                String firstTarget = stream.filters().isEmpty() ? targetBus.nodeName() : stream.filterInputNodeName();
                List<Link> intoBus = channelLinks(
                        outputs.getOrDefault(stream.node().name(), List.of()),
                        inputs.getOrDefault(firstTarget, List.of()),
                        stream.filters().isEmpty() ? targetBus.channelLayout() : "stereo", true);
                if (intoBus.isEmpty()) throw new IOException("No compatible PipeWire ports between stream " + stream.node().name() + " and bus " + stream.busId());
                connectAll(intoBus);
                if (!stream.filters().isEmpty()) {
                    List<Link> fromFilter = channelLinks(
                            outputs.getOrDefault(stream.filterOutputNodeName(), List.of()),
                            inputs.getOrDefault(targetBus.nodeName(), List.of()),
                            targetBus.channelLayout(), true);
                    if (fromFilter.isEmpty()) throw new IOException("No compatible PipeWire ports between application EQ and bus " + stream.busId());
                    connectAll(fromFilter);
                }
            }

            Set<String> activeStreamNames = new HashSet<>();
            plan.streams().forEach(stream -> activeStreamNames.add(stream.node().name()));
            for (String name : new ArrayList<>(originalStreamLinks.keySet())) {
                if (activeStreamNames.contains(name)) continue;
                restoreOriginal(name);
                originalStreamLinks.remove(name);
            }
            if (fadeInMs > 0) fadeRoutePlan(plan, false, fadeInMs);
            active = true;
            appliedSignature = signature;
            appliedPlan = plan;
            activeTransitionMs = transitionMs;
            return reportAppliedPlan(plan);
        } catch (Exception error) {
            disconnectOwnedLinks();
            stopOwnedProcesses();
            originalStreamLinks.clear();
            originalStreamLinks.putAll(originalBeforeApply);
            restoreAllOriginal();
            active = false;
            appliedSignature = "";
            appliedPlan = null;
            status = "Route apply failed; previous stream links were restored: " + message(error);
            throw error;
        }
    }

    ApplyReport reportAppliedPlan(Plan plan) {
        List<String> reportedWarnings = new ArrayList<>(plan.warnings());
        reportedWarnings.addAll(transitionWarnings);
        transitionWarnings.clear();
        lastReport = new ApplyReport(plan.buses().size(), plan.routes().size(), plan.streams().size(), reportedWarnings);
        int filterCount = plan.routes().stream().mapToInt(route -> route.filters().size()).sum()
                + plan.outputs().values().stream().mapToInt(output -> output.filters().size() + output.limiterFilters().size()).sum()
                + plan.streams().stream().mapToInt(stream -> stream.filters().size()).sum();
        status = "Applied " + plan.streams().size() + " streams through " + plan.buses().size()
                + " buses and " + plan.routes().size() + " routes";
        if (filterCount > 0) status += " with " + filterCount + " PipeWire filters";
        if (!reportedWarnings.isEmpty()) status += " · " + String.join("; ", reportedWarnings);
        return lastReport;
    }

    private static int transitionMillis(Map<String, Object> config) {
        return Math.max(0, Math.min(1000, (int) number(object(config.get("engine")).get("transition_ms"), 120)));
    }

    private void fadeRoutePlan(Plan plan, boolean fadeOut, int durationMs) {
        if (plan == null || plan.routes().isEmpty() || durationMs <= 0) return;
        int steps = RouteTransitionMath.stepsFor(durationMs, plan.routes().size());
        if (steps <= 0) return;
        record FadeNode(int nodeId, double from, double to) {}
        List<FadeNode> nodes = new ArrayList<>();
        try {
            for (RoutePlan route : plan.routes()) {
                int nodeId = findNodeId(route.playbackNodeName());
                double configured = MixLevelMath.effectiveRouteVolume(route.volume(), route.gainDb());
                double full = route.muted() ? 0.0 : configured;
                nodes.add(new FadeNode(nodeId, fadeOut ? full : 0.0, fadeOut ? 0.0 : full));
            }
            long startNanos = System.nanoTime();
            for (int step = 1; step <= steps; step++) {
                sleepUntil(RouteTransitionMath.deadlineNanos(startNanos, durationMs, step, steps));
                for (FadeNode node : nodes) {
                    double volume = RouteTransitionMath.interpolate(node.from(), node.to(), step, steps);
                    run(List.of("wpctl", "set-volume", Integer.toString(node.nodeId()), Double.toString(volume),
                            "--limit", Double.toString(MixLevelMath.maxRouteVolume())), COMMAND_TIMEOUT);
                }
            }
        } catch (Exception error) {
            transitionWarnings.add("route transition fade failed; graph update continued abruptly: " + message(error));
        }
    }

    private static void sleepUntil(long deadlineNanos) throws InterruptedException {
        long remaining;
        while ((remaining = deadlineNanos - System.nanoTime()) > 0)
            TimeUnit.NANOSECONDS.sleep(remaining);
    }

    Plan plan(Map<String, Object> config, PipeWireBackend.Snapshot snapshot) throws IOException {
        Map<String, Object> profile = maps(config.get("profiles")).stream()
                .filter(item -> String.valueOf(config.get("active_profile")).equals(item.get("id")))
                .findFirst().orElseGet(() -> maps(config.get("profiles")).stream().findFirst().orElse(Map.of()));
        String profileMode = text(profile.get("mode"), "hybrid");
        boolean profileOff = "off".equals(profileMode);
        if (profileOff) return new Plan(Map.of(), List.of(), List.of(), Map.of(), List.of(), true);
        Set<String> activeBuses = new HashSet<>(strings(profile.get("active_buses")));
        Set<String> activeRoutes = new HashSet<>(strings(profile.get("active_routes")));
        Map<String, Map<String, Object>> busConfig = new LinkedHashMap<>();
        for (Map<String, Object> bus : maps(config.get("buses"))) busConfig.put(text(bus.get("id")), bus);
        List<AudioNode> physicalSinks = snapshot.sinks().stream().filter(node ->
                !node.name().startsWith("audiomatrix_bus_") && !node.name().startsWith("audiomatrix_route_")).toList();
        List<Map<String, Object>> devices = maps(config.get("devices"));
        List<String> warnings = new ArrayList<>();
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
                if (sink == null) { warnings.add("route " + id + " has no available output or fallback"); continue; }
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
            routes.add(new RoutePlan(id, from, targetBus, sink, text(route.get("channel_map"), "stereo"), positions, channelMatrix,
                    number(route.get("volume"), 1), gain, number(route.get("delay_ms"), 0),
                    Boolean.TRUE.equals(route.get("mute")), (int) number(route.get("priority"), 0),
                    filters, captureNode, playbackNode, routeNodeName("dsp_input", id), routeNodeName("dsp_output", id),
                    routeNodeName("matrix_input", id), routeNodeName("matrix_output", id)));
        }
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
            List<Map<String, Object>> limiterFilters = outputLimiterFilters(config);
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
                    MixLevelMath.effectiveBusVolume(number(bus.get("volume"), 1), masterVolume),
                    MixLevelMath.effectiveBusMute(Boolean.TRUE.equals(bus.get("mute")), masterMuted)));
        }
        List<StreamPlan> streams = new ArrayList<>();
        if (!profileOff && !routes.isEmpty()) {
            for (AudioNode stream : snapshot.streams()) {
                Map<String, Object> rule = ApplicationRuleMatcher.find(maps(config.get("applications")), stream);
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
        return new Plan(buses, routes, streams, outputPlans, warnings, profileOff);
    }

    List<Map<String, Object>> routeFilters(Map<String, Object> route, Map<String, Object> config) throws IOException {
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

    List<Map<String, Object>> outputFilters(AudioNode sink, List<Map<String, Object>> devices,
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

    List<Map<String, Object>> outputLimiterFilters(Map<String, Object> config) {
        Map<String, Object> limiter = object(object(config.get("dsp")).get("limiter"));
        if (!Boolean.TRUE.equals(limiter.get("enabled"))) return List.of();
        return List.of(Map.of("type", "limiter", "enabled", true,
                "ceiling_db", number(limiter.get("ceiling_db"), -1),
                "lookahead_ms", number(limiter.get("lookahead_ms"), 5),
                "release_ms", number(limiter.get("release_ms"), 50)));
    }

    List<Map<String, Object>> applicationFilters(Map<String, Object> rule, Map<String, Object> config) throws IOException {
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

    private static void appendReferencedFilters(List<Map<String, Object>> filters, Map<String, Object> dsp,
                                                String filtersRef, String routeId, String ownerLabel,
                                                boolean limiterEnabled) throws IOException {
        if (filtersRef.isBlank()) return;
        String prefix = "dsp.route_chains.";
        if (!filtersRef.startsWith(prefix) || filtersRef.length() == prefix.length())
            throw new IOException("Route '" + routeId + "' has an unsupported " + ownerLabel + " filters_ref: " + filtersRef);
        String chainId = filtersRef.substring(prefix.length());
        Map<String, Object> chains = object(dsp.get("route_chains"));
        if (!chains.containsKey(chainId))
            throw new IOException("Route '" + routeId + "' references a missing DSP chain " + chainId);
        appendEnabledFilters(filters, object(chains.get(chainId)), ownerLabel + " EQ chain " + chainId, limiterEnabled);
    }

    private static void appendEnabledFilters(List<Map<String, Object>> target, Map<String, Object> eq, String label,
                                             boolean limiterEnabled) throws IOException {
        if (!Boolean.TRUE.equals(eq.get("enabled"))) return;
        for (Map<String, Object> filter : maps(eq.get("filters"))) {
            if (Boolean.FALSE.equals(filter.get("enabled"))) continue;
            if (number(filter.get("gain_db"), 0) > 0 && !limiterEnabled)
                throw new IOException("Positive EQ gain in " + label + " requires an active limiter");
            target.add(new LinkedHashMap<>(filter));
        }
    }

    private static boolean hasOutputPath(String busId, Map<String, List<RoutePlan>> outgoing, Set<String> visiting) {
        if (!visiting.add(busId)) return false;
        for (RoutePlan route : outgoing.getOrDefault(busId, List.of())) {
            if (route.sink() != null) { visiting.remove(busId); return true; }
            if (route.targetBusId() != null && hasOutputPath(route.targetBusId(), outgoing, visiting)) { visiting.remove(busId); return true; }
        }
        visiting.remove(busId); return false;
    }

    private AudioNode resolveSink(String ref, Map<String, Object> profile, List<AudioNode> sinks,
                                  List<Map<String, Object>> devices, int defaultSinkId) {
        if ("system_default".equals(ref)) return sinks.stream().filter(node -> node.id() == defaultSinkId).findFirst().orElse(null);
        return DeviceSelectorMatcher.resolve(sinks, ref, devices);
    }

    private static String configuredOutputRole(AudioNode sink, List<Map<String, Object>> devices) {
        List<Map<String, Object>> matching = devices.stream()
                .filter(device -> DeviceSelectorMatcher.matches(sink, object(device.get("selector")))).toList();
        if (matching.size() != 1) return matching.isEmpty() ? "" : "ambiguous";
        return text(matching.getFirst().get("role"), "");
    }

    private static boolean hasLowPass(List<Map<String, Object>> filters) {
        return filters.stream().anyMatch(filter -> "low_pass".equals(filter.get("type"))
                && !Boolean.FALSE.equals(filter.get("enabled")));
    }

    private Set<String> sourceRootRolesForBus(String busId, Map<String, Map<String, Object>> busConfig,
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

    private static boolean hasUnfilteredPathToSink(String busId, String sinkName,
                                                    Map<String, List<RoutePlan>> outgoing,
                                                    boolean lowPassSeen, Set<String> visiting) {
        if (!visiting.add(busId)) return false;
        for (RoutePlan route : outgoing.getOrDefault(busId, List.of())) {
            if (route.muted() || route.volume() <= 0) continue;
            boolean filtered = lowPassSeen || hasLowPass(route.filters());
            if (route.sink() != null && sinkName.equals(route.sink().name()) && !filtered) return true;
            if (route.targetBusId() != null && hasUnfilteredPathToSink(route.targetBusId(), sinkName,
                    outgoing, filtered, new HashSet<>(visiting))) return true;
        }
        return false;
    }

    private void startBus(BusPlan bus) throws Exception {
        if (busProcesses.containsKey(bus.id()) && busProcesses.get(bus.id()).process().isAlive()) return;
        String propsIn = json.writeValueAsString(Map.of("node.name", bus.nodeName(), "node.description", "Audio Matrix bus " + bus.id(),
                "media.class", "Audio/Sink", "audio.position", bus.positions(), "node.virtual", true));
        String playbackName = bus.nodeName() + "_discard";
        String propsOut = json.writeValueAsString(Map.of("node.name", playbackName, "node.description", "Audio Matrix bus internal output",
                "node.autoconnect", false, "node.passive", true, "stream.dont-remix", true, "audio.position", bus.positions()));
        List<String> command = List.of("pw-loopback", "-n", bus.nodeName(), "-c", Integer.toString(bus.positions().size()), "-m", positionArg(bus.positions()), "-i", propsIn, "-o", propsOut);
        ManagedProcess process = startProcess(command, bus.nodeName());
        busProcesses.put(bus.id(), process);
        awaitPorts(bus.nodeName(), "-i", process.process());
        awaitPorts(bus.nodeName(), "-o", process.process());
    }

    private void startRoute(RoutePlan route) throws Exception {
        if (routeProcesses.containsKey(route.id()) && routeProcesses.get(route.id()).process().isAlive()) return;
        List<String> routePositions = route.positions();
        String inProps = json.writeValueAsString(Map.of("node.name", route.captureNodeName(), "node.description", "Audio Matrix route capture " + route.id(),
                "node.autoconnect", false, "node.passive", true, "audio.position", routePositions));
        String outProps = json.writeValueAsString(Map.of("node.name", route.playbackNodeName(), "node.description", "Audio Matrix route playback " + route.id(),
                "node.autoconnect", false, "node.passive", true, "stream.dont-remix", true, "audio.position", routePositions));
        List<String> command = new ArrayList<>(List.of("pw-loopback", "-n", route.captureNodeName(), "-c", Integer.toString(routePositions.size()), "-m", positionArg(routePositions), "-d", Double.toString(route.delayMs() / 1000.0), "-i", inProps, "-o", outProps));
        ManagedProcess process = startProcess(command, route.captureNodeName());
        routeProcesses.put(route.id(), process);
        awaitPorts(route.captureNodeName(), "-i", process.process());
        awaitPorts(route.playbackNodeName(), "-o", process.process());
        findNodeId(route.playbackNodeName());
    }

    private void startFilterChain(RoutePlan route) throws Exception {
        startFilterChain("route:" + route.id(), "Audio Matrix route " + route.id(), route.filterInputNodeName(),
                route.filterOutputNodeName(), route.positions(), route.filters());
    }

    private void startChannelMatrix(RoutePlan route) throws Exception {
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

    private void startFilterChain(StreamPlan stream) throws Exception {
        startFilterChain(stream.filterProcessKey(), "Audio Matrix application EQ " + stream.node().applicationName(),
                stream.filterInputNodeName(), stream.filterOutputNodeName(), stream.positions(), stream.filters());
    }

    private void startFilterChain(OutputPlan output) throws Exception {
        startFilterChain("output:" + output.sink().name(), "Audio Matrix physical output EQ " + output.sink().description(),
                output.filterInputNodeName(), output.filterOutputNodeName(), output.positions(), output.filters());
    }

    private void startOutputLimiter(OutputPlan output) throws Exception {
        startFilterChain("output-limiter:" + output.sink().name(), "Audio Matrix physical output limiter " + output.sink().description(),
                output.limiterInputNodeName(), output.limiterOutputNodeName(), output.positions(), output.limiterFilters());
    }

    private void startFilterChain(String processKey, String description, String inputNodeName, String outputNodeName,
                                  List<String> positions, List<Map<String, Object>> filters) throws Exception {
        ManagedProcess existing = filterChainProcesses.get(processKey);
        if (existing != null && existing.process().isAlive()) return;
        String config = PipeWireFilterChainConfig.render(description, inputNodeName, outputNodeName, positions, filters, true);
        Path configFile = Files.createTempFile("audio-matrix-filter-", ".conf");
        try {
            Files.writeString(configFile, config);
            Path ladspaDirectory = filters.stream().anyMatch(filter -> "limiter".equals(filter.get("type")))
                    ? bundledLadspaDirectory() : null;
            ManagedProcess process = startProcess(List.of("pipewire", "-c", configFile.toString()), inputNodeName, configFile, ladspaDirectory);
            filterChainProcesses.put(processKey, process);
            awaitPorts(inputNodeName, "-i", process.process());
            awaitPorts(outputNodeName, "-o", process.process());
        } catch (Exception error) {
            if (!filterChainProcesses.containsKey(processKey)) Files.deleteIfExists(configFile);
            throw error;
        }
    }

    private ManagedProcess startProcess(List<String> command, String key) throws IOException {
        return startProcess(command, key, null);
    }

    private ManagedProcess startProcess(List<String> command, String key, Path configFile) throws IOException {
        return startProcess(command, key, configFile, null);
    }

    private ManagedProcess startProcess(List<String> command, String key, Path configFile, Path ladspaDirectory) throws IOException {
        Path log = Files.createTempFile("audio-matrix-pw-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            if (ladspaDirectory != null) {
                String systemPath = builder.environment().getOrDefault("LADSPA_PATH", "");
                builder.environment().put("LADSPA_PATH", ladspaDirectory + (systemPath.isBlank() ? "" : java.io.File.pathSeparator + systemPath));
            }
            Process process = builder.start();
            return new ManagedProcess(process, log, List.copyOf(command), configFile);
        } catch (IOException error) {
            Files.deleteIfExists(log);
            throw error;
        }
    }

    private void awaitPorts(String nodeName, String direction, Process process) throws Exception {
        for (int i = 0; i < START_ATTEMPTS; i++) {
            if (!process.isAlive()) throw new IOException("pw-loopback exited while creating " + nodeName + ": " + processLog(process, nodeName));
            if (!ports(direction).getOrDefault(nodeName, List.of()).isEmpty()) return;
            Thread.sleep(START_WAIT_MS);
        }
        throw new IOException("PipeWire ports did not appear for " + nodeName + ": " + processLog(process, nodeName));
    }

    private Map<String, List<String>> ports(String direction) throws Exception {
        String listing = run(List.of("pw-link", direction), COMMAND_TIMEOUT);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String line : listing.split("\\R")) {
            String port = line.trim(); int colon = port.lastIndexOf(':');
            if (colon <= 0 || colon == port.length() - 1) continue;
            result.computeIfAbsent(port.substring(0, colon), ignored -> new ArrayList<>()).add(port);
        }
        return result;
    }

    private int findNodeId(String nodeName) throws Exception {
        JsonNode root = json.readTree(run(List.of("pw-dump", "-N"), Duration.ofSeconds(4)));
        if (!root.isArray()) throw new IOException("pw-dump returned an unexpected document");
        for (JsonNode object : root) {
            if (nodeName.equals(object.path("info").path("props").path("node.name").asText())) return object.path("id").asInt(-1);
        }
        throw new IOException("PipeWire node was not found: " + nodeName);
    }

    private void connectAll(List<Link> links) throws Exception {
        if (links.isEmpty()) throw new IOException("No compatible PipeWire channel ports were found");
        for (Link link : links) {
            run(List.of("pw-link", "--wait", link.output(), link.input()), Duration.ofSeconds(3));
            ownedLinks.add(link);
        }
    }

    private void disconnectAll(List<Link> links) {
        for (Link link : links) try { run(List.of("pw-link", "-d", link.output(), link.input()), COMMAND_TIMEOUT); }
        catch (Exception ignored) {}
    }

    private void disconnectOwnedLinks() {
        disconnectAll(new ArrayList<>(ownedLinks)); ownedLinks.clear();
    }

    private void stopOwnedProcesses() {
        stopProcesses(filterChainProcesses); stopProcesses(routeProcesses); stopProcesses(busProcesses);
    }

    private void stopProcesses(Map<String, ManagedProcess> processes) {
        for (ManagedProcess managed : new ArrayList<>(processes.values())) {
            Process process = managed.process();
            process.destroy();
            try { if (!process.waitFor(700, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); process.waitFor(700, TimeUnit.MILLISECONDS); } }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            try { Files.deleteIfExists(managed.log()); } catch (IOException ignored) {}
            if (managed.configFile() != null) try { Files.deleteIfExists(managed.configFile()); } catch (IOException ignored) {}
        }
        processes.clear();
    }

    private Path bundledLadspaDirectory() throws IOException {
        if (bundledLadspaDirectory != null && Files.isRegularFile(bundledLadspaDirectory.resolve("libaudiomatrix_limiter.so")))
            return bundledLadspaDirectory;
        Path directory = Files.createTempDirectory("audio-matrix-ladspa-");
        try (InputStream plugin = PipeWireRouteManager.class.getResourceAsStream("/native/libaudiomatrix_limiter.so")) {
            if (plugin == null) throw new IOException("The bundled Audio Matrix limiter plugin is missing");
            Files.copy(plugin, directory.resolve("libaudiomatrix_limiter.so"));
            bundledLadspaDirectory = directory;
            return directory;
        } catch (Exception error) {
            try { Files.deleteIfExists(directory.resolve("libaudiomatrix_limiter.so")); Files.deleteIfExists(directory); }
            catch (IOException ignored) {}
            if (error instanceof IOException io) throw io;
            throw new IOException("Could not prepare the bundled Audio Matrix limiter plugin", error);
        }
    }

    private void removeBundledLadspaDirectory() {
        Path directory = bundledLadspaDirectory;
        bundledLadspaDirectory = null;
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }

    private String processLog(Process process, String nodeName) {
        ManagedProcess managed = busProcesses.values().stream().filter(item -> item.process() == process).findFirst()
                .orElseGet(() -> routeProcesses.values().stream().filter(item -> item.process() == process).findFirst()
                        .orElseGet(() -> filterChainProcesses.values().stream().filter(item -> item.process() == process).findFirst().orElse(null)));
        if (managed == null) return "no process log for " + nodeName;
        try { return Files.readString(managed.log()).trim(); } catch (IOException e) { return "no process log for " + nodeName; }
    }

    private void clearNow(boolean restoreStreams) throws Exception {
        if (appliedPlan != null) fadeRoutePlan(appliedPlan, true, activeTransitionMs);
        disconnectOwnedLinks();
        stopOwnedProcesses();
        if (restoreStreams) restoreAllOriginal();
        originalStreamLinks.clear(); active = false; appliedSignature = ""; appliedPlan = null;
        status = transitionWarnings.isEmpty() ? "Routes are not applied"
                : "Routes are not applied · " + String.join("; ", transitionWarnings);
        transitionWarnings.clear();
    }

    private void restoreAllOriginal() {
        for (String stream : new ArrayList<>(originalStreamLinks.keySet())) restoreOriginal(stream);
    }

    private void restoreOriginal(String streamNode) {
        List<Link> original = originalStreamLinks.getOrDefault(streamNode, List.of());
        Set<String> outputPorts;
        Set<String> inputPorts;
        try {
            outputPorts = new HashSet<>(ports("-o").getOrDefault(streamNode, List.of()));
            inputPorts = new HashSet<>(); ports("-i").values().forEach(inputPorts::addAll);
        } catch (Exception e) { return; }
        for (Link link : original) if (outputPorts.contains(link.output()) && inputPorts.contains(link.input()))
            try { run(List.of("pw-link", "--wait", link.output(), link.input()), COMMAND_TIMEOUT); } catch (Exception ignored) {}
    }

    private static List<Link> linksFromNode(String listing, String nodeName) {
        List<Link> links = new ArrayList<>(); String current = "";
        for (String line : listing.split("\\R")) {
            if (!line.startsWith(" ") && !line.startsWith("\t") && line.contains(":")) current = line.trim();
            else if (line.trim().startsWith("|->")) {
                String destination = line.trim().substring(3).trim();
                if (current.startsWith(nodeName + ":")) links.add(new Link(current, destination));
            }
        }
        return List.copyOf(links);
    }

    static List<Link> channelLinks(List<String> outputs, List<String> inputs, String channelMap, boolean toCapture) {
        Map<String, String> outByChannel = channelPorts(outputs), inByChannel = channelPorts(inputs);
        List<Link> links = new ArrayList<>();
        boolean mono = monoChannelMap(channelMap);
        if (mono && inByChannel.containsKey("MONO")) {
            List<String> selected = switch (channelMap) {
                case "left" -> List.of(outByChannel.getOrDefault("FL", outByChannel.getOrDefault("MONO", "")));
                case "right" -> List.of(outByChannel.getOrDefault("FR", outByChannel.getOrDefault("MONO", "")));
                case "center" -> List.of(outByChannel.getOrDefault("FC", ""));
                case "lfe" -> List.of(outByChannel.getOrDefault("LFE", ""));
                case "rear_left" -> List.of(outByChannel.getOrDefault("RL", ""));
                case "rear_right" -> List.of(outByChannel.getOrDefault("RR", ""));
                case "rear_sum" -> List.of(outByChannel.getOrDefault("RL", ""), outByChannel.getOrDefault("RR", ""));
                case "side_left" -> List.of(outByChannel.getOrDefault("SL", ""));
                case "side_right" -> List.of(outByChannel.getOrDefault("SR", ""));
                case "side_sum" -> List.of(outByChannel.getOrDefault("SL", ""), outByChannel.getOrDefault("SR", ""));
                case "surround_sum" -> List.of(outByChannel.getOrDefault("RL", ""), outByChannel.getOrDefault("RR", ""),
                        outByChannel.getOrDefault("SL", ""), outByChannel.getOrDefault("SR", ""));
                case "all_sum" -> List.of(outByChannel.getOrDefault("FL", ""), outByChannel.getOrDefault("FR", ""),
                        outByChannel.getOrDefault("FC", ""), outByChannel.getOrDefault("LFE", ""),
                        outByChannel.getOrDefault("RL", ""), outByChannel.getOrDefault("RR", ""),
                        outByChannel.getOrDefault("RC", ""), outByChannel.getOrDefault("SL", ""),
                        outByChannel.getOrDefault("SR", ""), outByChannel.getOrDefault("MONO", ""));
                default -> List.of(outByChannel.getOrDefault("FL", ""), outByChannel.getOrDefault("FR", ""), outByChannel.getOrDefault("MONO", ""));
            };
            if (toCapture) {
                for (String source : selected) if (!source.isBlank()) links.add(new Link(source, inByChannel.get("MONO")));
            } else {
                String source = outByChannel.getOrDefault("MONO", "");
                if (!source.isBlank()) {
                    for (String channel : List.of("FL", "FR")) if (inByChannel.containsKey(channel)) links.add(new Link(source, inByChannel.get(channel)));
                    if (links.isEmpty()) links.add(new Link(source, inByChannel.values().iterator().next()));
                }
            }
            return distinct(links);
        }
        if ("swap_lr".equals(channelMap) && outByChannel.containsKey("FL") && outByChannel.containsKey("FR")
                && inByChannel.containsKey("FL") && inByChannel.containsKey("FR")) {
            links.add(new Link(outByChannel.get("FL"), inByChannel.get("FR")));
            links.add(new Link(outByChannel.get("FR"), inByChannel.get("FL")));
            return links;
        }
        for (String channel : outByChannel.keySet()) {
            if (inByChannel.containsKey(channel)) links.add(new Link(outByChannel.get(channel), inByChannel.get(channel)));
        }
        if (links.isEmpty() && outByChannel.containsKey("MONO")) {
            String source = outByChannel.get("MONO");
            for (String channel : List.of("FL", "FR")) if (inByChannel.containsKey(channel)) links.add(new Link(source, inByChannel.get(channel)));
        }
        if (links.isEmpty() && inByChannel.containsKey("MONO")) {
            for (String channel : List.of("FL", "FR")) if (outByChannel.containsKey(channel)) links.add(new Link(outByChannel.get(channel), inByChannel.get("MONO")));
        }
        return distinct(links);
    }

    static List<Link> positionLinks(List<String> outputs, List<String> inputs) {
        Map<String, String> outByChannel = channelPorts(outputs);
        Map<String, String> inByChannel = channelPorts(inputs);
        List<Link> links = new ArrayList<>();
        for (String channel : outByChannel.keySet())
            if (inByChannel.containsKey(channel)) links.add(new Link(outByChannel.get(channel), inByChannel.get(channel)));
        return distinct(links);
    }

    private static List<Link> distinct(List<Link> links) { return List.copyOf(new LinkedHashSet<>(links)); }

    private static Map<String, String> channelPorts(List<String> ports) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String port : ports) {
            String leaf = port.substring(port.lastIndexOf(':') + 1);
            int underscore = leaf.lastIndexOf('_'); String suffix = underscore >= 0 ? leaf.substring(underscore + 1) : leaf;
            String channel = suffix.toUpperCase(Locale.ROOT);
            if (Set.of("FL", "FR", "FC", "LFE", "RL", "RR", "RC", "SL", "SR", "MONO").contains(channel)) result.put(channel, port);
            else if ("0".equals(suffix)) result.putIfAbsent("FL", port);
            else if ("1".equals(suffix)) result.putIfAbsent("FR", port);
        }
        return result;
    }

    private static List<String> positions(String layout) {
        return switch (layout) {
            case "mono" -> List.of("MONO");
            case "5.1" -> List.of("FL", "FR", "FC", "LFE", "RL", "RR");
            case "7.1" -> List.of("FL", "FR", "FC", "LFE", "RL", "RR", "SL", "SR");
            default -> List.of("FL", "FR");
        };
    }

    private static List<String> routePositions(String channelMap) {
        return monoChannelMap(channelMap) ? List.of("MONO") : List.of("FL", "FR");
    }

    private static boolean monoChannelMap(String channelMap) {
        return Set.of("mono", "mono_sum", "left", "right", "center", "lfe", "rear_left", "rear_right",
                "rear_sum", "side_left", "side_right", "side_sum", "surround_sum", "all_sum").contains(channelMap);
    }

    private static List<String> streamPositions(int channels) {
        return switch (channels) {
            case 1 -> List.of("MONO");
            case 3 -> List.of("FL", "FR", "FC");
            case 4 -> List.of("FL", "FR", "RL", "RR");
            case 5 -> List.of("FL", "FR", "FC", "RL", "RR");
            case 6 -> List.of("FL", "FR", "FC", "LFE", "RL", "RR");
            case 7 -> List.of("FL", "FR", "FC", "LFE", "RL", "RR", "RC");
            case 8 -> List.of("FL", "FR", "FC", "LFE", "RL", "RR", "SL", "SR");
            default -> List.of("FL", "FR");
        };
    }

    private static String positionArg(List<String> positions) { return "[ " + String.join(" ", positions) + " ]"; }
    private static String busNodeName(String busId) { return "audiomatrix_bus_" + shortHash(busId); }
    private static String routeNodeName(String edge, String routeId) { return "audiomatrix_route_" + shortHash(routeId) + "_" + edge; }
    private static String applicationNodeName(String edge, String streamName) { return "audiomatrix_app_" + shortHash(streamName) + "_" + edge; }
    private static String outputNodeName(String edge, String sinkName) { return "audiomatrix_output_" + shortHash(sinkName) + "_" + edge; }
    private static String shortHash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 12); }
        catch (Exception e) { return Integer.toHexString(value.hashCode()); }
    }

    private String signature(Plan plan, PipeWireBackend.Snapshot snapshot) {
        List<String> streamPlans = plan.streams().stream()
                .map(stream -> stream.node().name() + ":" + stream.busId() + ":" + stream.positions() + ":" + stream.filters().hashCode())
                .sorted().toList();
        List<String> outputPlans = plan.outputs().values().stream()
                .map(output -> output.sink().name() + ":" + output.positions() + ":" + output.filters().hashCode() + ":" + output.limiterFilters().hashCode())
                .sorted().toList();
        List<String> sinkNames = snapshot.sinks().stream().filter(sink -> !sink.name().startsWith("audiomatrix_")).map(AudioNode::name).sorted().toList();
        return plan.buses().hashCode() + ":" + plan.routes().hashCode() + ":" + streamPlans + ":" + outputPlans + ":" + sinkNames + ":" + snapshot.defaultSinkId();
    }

    private static Map<String, List<Link>> deepLinks(Map<String, List<Link>> source) {
        Map<String, List<Link>> copy = new LinkedHashMap<>(); source.forEach((key, links) -> copy.put(key, List.copyOf(links))); return copy;
    }

    private boolean processesAlive() {
        return busProcesses.values().stream().allMatch(process -> process.process().isAlive())
                && routeProcesses.values().stream().allMatch(process -> process.process().isAlive())
                && filterChainProcesses.values().stream().allMatch(process -> process.process().isAlive());
    }

    private static void requireCommands(String... names) throws IOException {
        for (String name : names) {
            boolean found = java.util.Arrays.stream(System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
                    .anyMatch(path -> Files.isExecutable(Path.of(path, name)));
            if (!found) throw new IOException(name + " is required to apply PipeWire routes");
        }
    }

    private static String run(List<String> command, Duration timeout) throws Exception {
        Path output = Files.createTempFile("audio-matrix-route-command-", ".out");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) { process.destroyForcibly(); throw new IOException(command.getFirst() + " timed out"); }
            String text = Files.readString(output);
            if (process.exitValue() != 0) throw new IOException(text.isBlank() ? command.getFirst() + " exited with " + process.exitValue() : text.trim());
            return text;
        } finally { Files.deleteIfExists(output); }
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) { return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
    private static List<String> strings(Object value) { return value instanceof List<?> list ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of(); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String text(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    private static double number(Object value, double fallback) { return value instanceof Number n ? n.doubleValue() : fallback; }
    private static boolean limiterEnabled(Map<String, Object> config) {
        return Boolean.TRUE.equals(object(object(config.get("dsp")).get("limiter")).get("enabled"));
    }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static String message(Throwable e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closeFuture != null) return closeFuture;
        closed = true;
        closeFuture = CompletableFuture.runAsync(() -> {
            try { clearNow(true); removeBundledLadspaDirectory(); }
            catch (Exception error) {
                disconnectOwnedLinks();
                stopOwnedProcesses();
                restoreAllOriginal();
                originalStreamLinks.clear();
                active = false;
                appliedSignature = "";
                status = "Routes stopped; cleanup reported: " + message(error);
                removeBundledLadspaDirectory();
            }
        }, worker).whenComplete((ignored, error) -> worker.shutdown());
        return closeFuture;
    }

    @Override public void close() { closeAsync(); }

    public record Link(String output, String input) {}
    public record ApplyReport(int buses, int routes, int streams, List<String> warnings) {
        public ApplyReport { warnings = List.copyOf(warnings); }
    }
    public record HeadroomEstimate(boolean available, double preLimiterPeakDbfs, double limiterCeilingDbfs,
                                   double postLimiterPeakDbfs, double worstCaseReductionDb, String message) {
        static HeadroomEstimate unavailable(String message) {
            return new HeadroomEstimate(false, Double.NaN, Double.NaN, Double.NaN, 0, message);
        }
    }
    private record ManagedProcess(Process process, Path log, List<String> command, Path configFile) {}
    private record BusPlan(String id, String nodeName, String channelLayout, List<String> positions, double volume, boolean muted) {}
    record RoutePlan(String id, String fromBusId, String targetBusId, AudioNode sink, String channelMap,
                             List<String> positions, ChannelMatrix channelMatrix,
                             double volume, double gainDb, double delayMs, boolean muted, int priority,
                             List<Map<String, Object>> filters, String captureNodeName, String playbackNodeName,
                             String filterInputNodeName, String filterOutputNodeName,
                             String matrixInputNodeName, String matrixOutputNodeName) {
        RoutePlan { positions = List.copyOf(positions); filters = List.copyOf(filters); }
    }
    private record OutputPlan(AudioNode sink, List<String> positions, List<Map<String, Object>> filters,
                              List<Map<String, Object>> limiterFilters, String filterInputNodeName,
                              String filterOutputNodeName, String limiterInputNodeName, String limiterOutputNodeName) {
        private OutputPlan {
            positions = List.copyOf(positions);
            filters = List.copyOf(filters);
            limiterFilters = List.copyOf(limiterFilters);
        }
        private String firstInputNodeName() {
            if (!filters.isEmpty()) return filterInputNodeName;
            if (!limiterFilters.isEmpty()) return limiterInputNodeName;
            return sink.name();
        }
    }
    private record StreamPlan(AudioNode node, String busId, List<Map<String, Object>> filters, List<String> positions,
                              String filterProcessKey, String filterInputNodeName, String filterOutputNodeName) {
        private StreamPlan { filters = List.copyOf(filters); positions = List.copyOf(positions); }
    }
    record Plan(Map<String, BusPlan> buses, List<RoutePlan> routes, List<StreamPlan> streams,
                        Map<String, OutputPlan> outputs, List<String> warnings, boolean profileOff) {}
}
