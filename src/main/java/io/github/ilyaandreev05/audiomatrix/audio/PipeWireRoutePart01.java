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
        return apply(config, snapshot, false);
    }

    public CompletableFuture<ApplyReport> reapplyAfterReconnect(Map<String, Object> config, PipeWireBackend.Snapshot snapshot) {
        return apply(config, snapshot, true);
    }

    protected CompletableFuture<ApplyReport> apply(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
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
        if (active && appliedPlan != null && processesAlive() && sameRoutingTopology(appliedPlan, plan)) {
            try {
                updateLiveLevels(appliedPlan, plan);
                activeTransitionMs = transitionMillis(config);
                appliedPlan = plan;
                appliedSignature = signature;
                return reportAppliedPlan(plan);
            } catch (Exception error) {
                transitionWarnings.add("live level update failed; graph rebuild used instead: " + message(error));
            }
        }
        int transitionMs = transitionMillis(config);
        int fadeOutMs = active && appliedPlan != null ? transitionMs / 2 : 0;
        int fadeInMs = active && appliedPlan != null ? transitionMs - fadeOutMs : transitionMs;
        skippedStreamCount = 0;

        Map<String, List<Link>> originalBeforeApply = deepLinks(originalStreamLinks);
        return rebuildPipeWireGraph(plan, signature, transitionMs, fadeOutMs, fadeInMs, originalBeforeApply);
    }
}
