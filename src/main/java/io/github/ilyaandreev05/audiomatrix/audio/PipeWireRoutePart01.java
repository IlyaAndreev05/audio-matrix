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

abstract class PipeWireRoutePart01 extends PipeWireRouteContract {
    public CompletableFuture<ApplyReport> apply(Map<String, Object> config, PipeWireBackend.Snapshot snapshot) {
        return apply(config, snapshot, Map.of(), false);
    }

    public CompletableFuture<ApplyReport> apply(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                                Map<String, CapturedStreamBinding> capturedBindings) {
        return apply(config, snapshot, capturedBindings, false);
    }

    public CompletableFuture<ApplyReport> reapplyAfterReconnect(Map<String, Object> config, PipeWireBackend.Snapshot snapshot) {
        return apply(config, snapshot, Map.of(), true);
    }

    public CompletableFuture<ApplyReport> reapplyAfterReconnect(Map<String, Object> config,
                                                                PipeWireBackend.Snapshot snapshot,
                                                                Map<String, CapturedStreamBinding> capturedBindings) {
        return apply(config, snapshot, capturedBindings, true);
    }

    protected CompletableFuture<ApplyReport> apply(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                                   boolean reconnect) {
        return apply(config, snapshot, Map.of(), reconnect);
    }

    protected CompletableFuture<ApplyReport> apply(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                                   Map<String, CapturedStreamBinding> capturedBindings,
                                                   boolean reconnect) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Route manager is closed"));
        Map<String, CapturedStreamBinding> bindings = capturedBindings == null ? Map.of() : Map.copyOf(capturedBindings);
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Reconnect uses observed links and process liveness, not an unconditional rebuild.
                return applyNow(config, snapshot, bindings);
            }
            catch (Exception e) {
                status = "Route apply failed: " + message(e)
                        + (transitionWarnings.isEmpty() ? "" : " · " + String.join("; ", transitionWarnings));
                throw new CompletionException(e);
            } finally {
                publishDiagnostics();
            }
        }, worker);
    }

    public CompletableFuture<Void> clear() {
        if (closed) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try { clearNow(true); }
            catch (Exception e) { throw new CompletionException(e); }
            finally { publishDiagnostics(); }
        }, worker);
    }

    public boolean active() { return active; }

    public String status() { return status; }

    public ApplyReport lastReport() { return lastReport; }
    public RouteDiagnostics diagnostics() { return diagnostics; }

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
        branchGain *= plan.masterVolume();
        if (plan.masterMuted()) return HeadroomEstimate.unavailable("No active route reaches the selected output");
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

    protected ApplyReport applyNow(Map<String, Object> config, PipeWireBackend.Snapshot snapshot) throws Exception {
        return applyNow(config, snapshot, Map.of());
    }

    protected ApplyReport applyNow(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                   Map<String, CapturedStreamBinding> capturedBindings) throws Exception {
        commandTimeouts = 0;
        if (snapshot == null || !snapshot.connected()) {
            if (active || !busProcesses.isEmpty() || !routeProcesses.isEmpty() || !filterChainProcesses.isEmpty()) clearNow(false);
            originalStreamLinks.clear();
            status = "PipeWire is unavailable; Audio Matrix routes are stopped";
            throw new IOException("PipeWire is not connected");
        }
        requireCommands("pw-loopback", "pw-link", "pw-dump", "wpctl");
        Plan plan = plan(config, snapshot, capturedBindings);
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
            publishBranchDiagnostics(config, snapshot, plan);
            return lastReport;
        }
        for (RoutePlan route : plan.routes()) {
            if (route.gainDb() > 0 && !limiterEnabled(config))
                throw new IOException("Route '" + route.id() + "' requests positive gain; enable the limiter DSP before applying it");
        }
        String signature = signature(plan, snapshot);
        boolean observed = active && processesAlive() && observedGraphMatches();
        if (observed && signature.equals(appliedSignature)) {
            activeTransitionMs = transitionMillis(config);
            publishBranchDiagnostics(config, snapshot, plan);
            return reportAppliedPlan(plan);
        }

        // The serialized route worker owns reconciliation. A changed binding, one dead
        // process, or one missing link is repaired in place; it is not a reason to stop
        // unrelated buses, routes, filters, and output branches.
        activeTransitionMs = transitionMillis(config);
        skippedStreamCount = 0;
        ApplyReport report = reconcilePipeWireGraph(plan, signature, activeTransitionMs);
        publishBranchDiagnostics(config, snapshot, plan);
        return report;
    }
    protected void publishBranchDiagnostics(Map<String, Object> config, PipeWireBackend.Snapshot snapshot, Plan plan) {
        List<BranchDiagnostic> result = new ArrayList<>();
        Map<String, Object> profile = maps(config.get("profiles")).stream()
                .filter(item -> String.valueOf(config.get("active_profile")).equals(item.get("id")))
                .findFirst().orElseGet(() -> maps(config.get("profiles")).stream().findFirst().orElse(Map.of()));
        Set<String> selectedRoutes = new HashSet<>(strings(profile.get("active_routes")));
        Map<String, Map<String, Object>> busConfig = new LinkedHashMap<>();
        for (Map<String, Object> bus : maps(config.get("buses"))) busConfig.put(text(bus.get("id")), bus);
        Map<String, RoutePlan> planRoutes = new LinkedHashMap<>();
        plan.routes().forEach(route -> planRoutes.put(route.id(), route));
        List<AudioNode> physicalSinks = snapshot.sinks().stream()
                .filter(node -> !AudioMatrixOwnedStream.matches(node)).toList();
        List<Map<String, Object>> devices = maps(config.get("devices"));

        for (Map<String, Object> configured : maps(config.get("routes"))) {
            String id = text(configured.get("id"));
            if (id.isBlank()) continue;
            boolean selected = !plan.profileOff() && Boolean.TRUE.equals(configured.get("enabled"))
                    && selectedRoutes.contains(id);
            String target = text(configured.get("to"));
            boolean busTarget = busConfig.containsKey(target);
            AudioNode sink = busTarget ? null : resolveSink(target, profile, physicalSinks, devices, snapshot.defaultSinkId());
            if (!busTarget && sink == null && configured.get("fallback") != null)
                sink = resolveSink(text(configured.get("fallback")), profile, physicalSinks, devices, snapshot.defaultSinkId());
            boolean available = busTarget ? busConfig.containsKey(target) : sink != null;
            RoutePlan route = planRoutes.get(id);
            String owner = "route:" + id;
            Set<Link> desired = new LinkedHashSet<>(linkOwners.getOrDefault(owner, Set.of()));
            boolean processAlive = processAlive(routeProcesses.get(id));
            String error = branchErrors.getOrDefault(owner, "");
            if (route != null) {
                processAlive &= processAlive(busProcesses.get(route.fromBusId()));
                if (route.targetBusId() != null) processAlive &= processAlive(busProcesses.get(route.targetBusId()));
                if (route.sink() != null) {
                    OutputPlan output = plan.outputs().get(route.sink().name());
                    if (output != null) {
                        String outputOwner = "output:" + route.sink().name();
                        desired.addAll(linkOwners.getOrDefault(outputOwner, Set.of()));
                        if (!output.filters().isEmpty())
                            processAlive &= processAlive(filterChainProcesses.get(outputOwner));
                        if (!output.limiterFilters().isEmpty())
                            processAlive &= processAlive(filterChainProcesses.get("output-limiter:" + route.sink().name()));
                        if (error.isBlank()) error = branchErrors.getOrDefault(outputOwner, "");
                    }
                }
            }
            if (selected && route == null && error.isBlank()) {
                error = plan.warnings().stream().filter(warning -> warning.startsWith("route " + id + " "))
                        .findFirst().orElse(available ? "Selected route was not compiled" : "Selected output is unavailable");
            }
            List<Link> observed = desired.stream().filter(observedLinks::contains).toList();
            result.add(new BranchDiagnostic(id, BranchKind.ROUTE, selected, available, processAlive,
                    new ArrayList<>(desired), observed, error));
        }

        for (BusPlan bus : plan.buses().values()) {
            String owner = "bus:" + bus.id();
            Set<Link> desired = linkOwners.get(owner);
            if (desired == null) continue;
            List<Link> observed = desired.stream().filter(observedLinks::contains).toList();
            result.add(new BranchDiagnostic(bus.id(), BranchKind.BUS, true, true,
                    processAlive(busProcesses.get(bus.id())), new ArrayList<>(desired), observed,
                    branchErrors.getOrDefault(owner, "")));
        }

        Map<String, List<StreamPlan>> streamsByBinding = new LinkedHashMap<>();
        for (StreamPlan stream : plan.streams())
            streamsByBinding.computeIfAbsent(stream.bindingId(), ignored -> new ArrayList<>()).add(stream);
        streamsByBinding.forEach((bindingId, streams) -> {
            Set<Link> desired = new LinkedHashSet<>();
            boolean live = false;
            List<String> errors = new ArrayList<>();
            for (StreamPlan stream : streams) {
                String owner = "stream:" + stream.bindingId() + ":" + stream.node().name();
                desired.addAll(linkOwners.getOrDefault(owner, Set.of()));
                ManagedProcess filter = stream.filters().isEmpty() ? null
                        : filterChainProcesses.get(stream.filterProcessKey());
                live |= stream.node().active() && (stream.filters().isEmpty() || processAlive(filter));
                String error = branchErrors.get(owner);
                if (error != null && !error.isBlank()) errors.add(error);
            }
            List<Link> observed = desired.stream().filter(observedLinks::contains).toList();
            BranchKind kind = streams.stream().anyMatch(stream -> EventInputMonitor.NODE_NAME.equals(stream.node().name()))
                    ? BranchKind.EVENT_INPUT : BranchKind.APPLICATION_STREAM;
            result.add(new BranchDiagnostic(bindingId, kind, true, true, live,
                    new ArrayList<>(desired), observed, String.join("; ", errors.stream().distinct().toList())));
        });
        branchDiagnosticsSnapshot = List.copyOf(result);
    }

    private static boolean processAlive(ManagedProcess process) {
        return process != null && process.process().isAlive();
    }
}
