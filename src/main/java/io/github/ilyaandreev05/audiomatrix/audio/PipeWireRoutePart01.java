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
        return apply(config, snapshot, Map.of());
    }

    public CompletableFuture<ApplyReport> reapplyAfterReconnect(Map<String, Object> config,
                                                                 PipeWireBackend.Snapshot snapshot) {
        return apply(config, snapshot, Map.of());
    }

    public CompletableFuture<ApplyReport> reapplyAfterReconnect(Map<String, Object> config,
                                                                 PipeWireBackend.Snapshot snapshot,
                                                                 Map<String, CapturedStreamBinding> bindings) {
        return apply(config, snapshot, bindings);
    }

    public CompletableFuture<ApplyReport> apply(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                                 Map<String, CapturedStreamBinding> bindings) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Route manager is closed"));
        if (bindings != null) {
            for (Map.Entry<String, CapturedStreamBinding> entry : bindings.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null
                        || !entry.getKey().equals(entry.getValue().nodeName()))
                    return CompletableFuture.failedFuture(new IllegalArgumentException(
                            "Captured binding map keys must equal their exact nodeName"));
            }
        }
        Map<String, CapturedStreamBinding> stableBindings = bindings == null ? Map.of() : Map.copyOf(bindings);
        return CompletableFuture.supplyAsync(() -> {
            try { return applyNow(config, snapshot, stableBindings); }
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
    public Map<String, BranchDiagnostic> branchDiagnostics() { return branchDiagnostics; }

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
                                   Map<String, CapturedStreamBinding> bindings) throws Exception {
        commandTimeouts = 0;
        transitionWarnings.clear();
        if (snapshot == null || !snapshot.connected()) {
            if (active || !busProcesses.isEmpty() || !routeProcesses.isEmpty() || !filterChainProcesses.isEmpty())
                clearNow(false);
            originalStreamLinks.clear();
            status = "PipeWire is unavailable; Audio Matrix routes are stopped";
            throw new IOException("PipeWire is not connected");
        }
        requireCommands("pw-loopback", "pw-link", "pw-dump", "wpctl");
        Plan plan = plan(config, snapshot, bindings);
        if (plan.routes().stream().anyMatch(route -> !route.filters().isEmpty()
                || route.channelMatrix() != null)
                || plan.outputs().values().stream().anyMatch(output ->
                        !output.filters().isEmpty() || !output.limiterFilters().isEmpty())
                || plan.streams().stream().anyMatch(stream -> !stream.filters().isEmpty()))
            requireCommands("pipewire");
        if (plan.profileOff()) {
            clearNow(true);
            appliedPlan = plan;
            appliedSignature = signature(plan, snapshot);
            lastReport = new ApplyReport(0, 0, 0, plan.warnings());
            status = plan.warnings().isEmpty() ? "No active route reaches an output" : String.join("; ", plan.warnings());
            return lastReport;
        }
        for (RoutePlan route : plan.routes()) {
            if (route.gainDb() > 0 && !limiterEnabled(config))
                throw new IOException("Route '" + route.id() + "' requests positive gain; enable the limiter DSP before applying it");
        }
        int transitionMs = transitionMillis(config);
        skippedStreamCount = 0;
        return reconcilePipeWireGraph(plan, signature(plan, snapshot), transitionMs);
    }
}
