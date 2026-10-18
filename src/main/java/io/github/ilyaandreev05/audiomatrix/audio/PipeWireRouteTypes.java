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

public abstract class PipeWireRouteTypes {
    public record Link(String output, String input) {}
    public record ApplyReport(int buses, int routes, int streams, List<String> warnings) {
        public ApplyReport { warnings = List.copyOf(warnings); }
    }
    /** Counts from the last reconciliation; no device identities or command output. */
    public record RouteDiagnostics(int desiredLinks, int observedLinks, int missingLinks,
                                   int unavailableOutputs, int conflictingOutputs,
                                   int commandTimeouts, boolean active) {}
    public record HeadroomEstimate(boolean available, double preLimiterPeakDbfs, double limiterCeilingDbfs,
                                   double postLimiterPeakDbfs, double worstCaseReductionDb, String message) {
        static HeadroomEstimate unavailable(String message) {
            return new HeadroomEstimate(false, Double.NaN, Double.NaN, Double.NaN, 0, message);
        }
    }
    protected record ManagedProcess(Process process, Path log, List<String> command, Path configFile) {}
    protected record BusPlan(String id, String nodeName, String channelLayout, List<String> positions, double volume, boolean muted) {}
    protected record RoutePlan(String id, String fromBusId, String targetBusId, AudioNode sink, String channelMap,
                             List<String> positions, ChannelMatrix channelMatrix,
                             double volume, double gainDb, double delayMs, boolean muted, int priority,
                             List<Map<String, Object>> filters, String captureNodeName, String playbackNodeName,
                             String filterInputNodeName, String filterOutputNodeName,
                             String matrixInputNodeName, String matrixOutputNodeName) {
        protected RoutePlan { positions = List.copyOf(positions); filters = List.copyOf(filters); }
    }
    protected record OutputPlan(AudioNode sink, List<String> positions, List<Map<String, Object>> filters,
                              List<Map<String, Object>> limiterFilters, String filterInputNodeName,
                              String filterOutputNodeName, String limiterInputNodeName, String limiterOutputNodeName) {
        protected OutputPlan {
            positions = List.copyOf(positions);
            filters = List.copyOf(filters);
            limiterFilters = List.copyOf(limiterFilters);
        }
        protected String firstInputNodeName() {
            if (!filters.isEmpty()) return filterInputNodeName;
            if (!limiterFilters.isEmpty()) return limiterInputNodeName;
            return sink.name();
        }
    }
    protected record StreamPlan(AudioNode node, String busId, List<Map<String, Object>> filters, List<String> positions,
                              String filterProcessKey, String filterInputNodeName, String filterOutputNodeName) {
        protected StreamPlan { filters = List.copyOf(filters); positions = List.copyOf(positions); }
    }
    protected record Plan(Map<String, BusPlan> buses, List<RoutePlan> routes, List<StreamPlan> streams,
                        Map<String, OutputPlan> outputs, List<String> warnings, boolean profileOff,
                        double masterVolume, boolean masterMuted) {
        List<String> routedStreamNames() { return streams.stream().map(plan -> plan.node().name()).toList(); }
    }
}
