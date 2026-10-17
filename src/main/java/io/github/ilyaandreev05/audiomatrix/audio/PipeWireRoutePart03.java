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

abstract class PipeWireRoutePart03 extends PipeWireRoutePart02 {
    protected ApplyReport reportAppliedPlan(Plan plan) {
        List<String> reportedWarnings = new ArrayList<>(plan.warnings());
        reportedWarnings.addAll(transitionWarnings);
        transitionWarnings.clear();
        lastReport = new ApplyReport(plan.buses().size(), plan.routes().size(),
                Math.max(0, plan.streams().size() - skippedStreamCount), reportedWarnings);
        int filterCount = plan.routes().stream().mapToInt(route -> route.filters().size()).sum()
                + plan.outputs().values().stream().mapToInt(output -> output.filters().size() + output.limiterFilters().size()).sum()
                + plan.streams().stream().mapToInt(stream -> stream.filters().size()).sum();
        status = "Applied " + plan.streams().size() + " streams through " + plan.buses().size()
                + " buses and " + plan.routes().size() + " routes";
        if (filterCount > 0) status += " with " + filterCount + " PipeWire filters";
        if (!reportedWarnings.isEmpty()) status += " · " + String.join("; ", reportedWarnings);
        return lastReport;
    }

    protected void fadeRoutePlan(Plan plan, boolean fadeOut, int durationMs) {
        if (plan == null || plan.routes().isEmpty() || durationMs <= 0) return;
        int steps = RouteTransitionMath.stepsFor(durationMs, plan.routes().size());
        if (steps <= 0) return;
        record FadeNode(int nodeId, double from, double to) {}
        List<FadeNode> nodes = new ArrayList<>();
        try {
            for (RoutePlan route : plan.routes()) {
                int nodeId = findNodeId(route.playbackNodeName());
                double configured = effectiveRoutePlaybackVolume(plan, route);
                double full = effectiveRoutePlaybackMuted(plan, route) ? 0.0 : configured;
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
}
