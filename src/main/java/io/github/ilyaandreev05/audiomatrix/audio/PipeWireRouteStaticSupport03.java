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

abstract class PipeWireRouteStaticSupport03 extends PipeWireRouteStaticSupport02 {
    protected static boolean isEventSoundStudioStream(AudioNode stream) {
        // EES preview and generator playback may use Java Sound from the same process.
        // Only the dedicated pw-cat event stream carries this stable application ID.
        return EVENT_SOUND_STUDIO_APPLICATION_ID.equals(stream.applicationId());
    }

    protected static boolean sameRoutingTopology(Plan previous, Plan next) {
        if (!previous.buses().keySet().equals(next.buses().keySet())
                || !previous.outputs().keySet().equals(next.outputs().keySet())
                || previous.routes().size() != next.routes().size()
                || previous.streams().size() != next.streams().size()) return false;
        for (String id : previous.buses().keySet()) {
            BusPlan left = previous.buses().get(id), right = next.buses().get(id);
            if (!left.nodeName().equals(right.nodeName()) || !left.channelLayout().equals(right.channelLayout())
                    || !left.positions().equals(right.positions())) return false;
        }
        Map<String, RoutePlan> nextRoutes = new LinkedHashMap<>();
        next.routes().forEach(route -> nextRoutes.put(route.id(), route));
        for (RoutePlan left : previous.routes()) {
            RoutePlan right = nextRoutes.get(left.id());
            if (right == null || !left.fromBusId().equals(right.fromBusId())
                    || !java.util.Objects.equals(left.targetBusId(), right.targetBusId())
                    || !sameSink(left.sink(), right.sink())
                    || !left.channelMap().equals(right.channelMap()) || !left.positions().equals(right.positions())
                    || !java.util.Objects.equals(left.channelMatrix(), right.channelMatrix())
                    || Double.compare(left.delayMs(), right.delayMs()) != 0 || left.priority() != right.priority()
                    || !left.filters().equals(right.filters())
                    || !left.captureNodeName().equals(right.captureNodeName())
                    || !left.playbackNodeName().equals(right.playbackNodeName())
                    || !left.filterInputNodeName().equals(right.filterInputNodeName())
                    || !left.filterOutputNodeName().equals(right.filterOutputNodeName())
                    || !left.matrixInputNodeName().equals(right.matrixInputNodeName())
                    || !left.matrixOutputNodeName().equals(right.matrixOutputNodeName())) return false;
        }
        for (String name : previous.outputs().keySet()) {
            OutputPlan left = previous.outputs().get(name), right = next.outputs().get(name);
            if (!sameSink(left.sink(), right.sink()) || !left.positions().equals(right.positions())
                    || !left.filters().equals(right.filters()) || !left.limiterFilters().equals(right.limiterFilters())
                    || !left.filterInputNodeName().equals(right.filterInputNodeName())
                    || !left.filterOutputNodeName().equals(right.filterOutputNodeName())
                    || !left.limiterInputNodeName().equals(right.limiterInputNodeName())
                    || !left.limiterOutputNodeName().equals(right.limiterOutputNodeName())) return false;
        }
        if (next.streams().size() != previous.streams().size()) return false;
        Map<String, StreamPlan> nextStreams = new LinkedHashMap<>();
        next.streams().forEach(stream -> nextStreams.put(stream.node().name(), stream));
        for (StreamPlan left : previous.streams()) {
            StreamPlan right = nextStreams.get(left.node().name());
            if (right == null || left.node().id() != right.node().id() || !left.busId().equals(right.busId())
                    || !left.filters().equals(right.filters()) || !left.positions().equals(right.positions())
                    || !left.filterProcessKey().equals(right.filterProcessKey())
                    || !left.filterInputNodeName().equals(right.filterInputNodeName())
                    || !left.filterOutputNodeName().equals(right.filterOutputNodeName())) return false;
        }
        return true;
    }

    protected static List<RoutePlan> routeLevelUpdateOrder(Plan previous, Plan next) {
        Map<String, RoutePlan> previousRoutes = new LinkedHashMap<>();
        previous.routes().forEach(route -> previousRoutes.put(route.id(), route));
        List<RoutePlan> lowering = new ArrayList<>(), remaining = new ArrayList<>();
        for (RoutePlan route : next.routes()) {
            RoutePlan before = previousRoutes.get(route.id());
            if (before == null) { remaining.add(route); continue; }
            boolean wasMuted = effectiveRoutePlaybackMuted(previous, before);
            boolean nowMuted = effectiveRoutePlaybackMuted(next, route);
            double wasLevel = wasMuted ? 0.0 : effectiveRoutePlaybackVolume(previous, before);
            double nowLevel = nowMuted ? 0.0 : effectiveRoutePlaybackVolume(next, route);
            if ((nowMuted && !wasMuted) || nowLevel + 1e-9 < wasLevel) lowering.add(route);
            else remaining.add(route);
        }
        List<RoutePlan> ordered = new ArrayList<>(next.routes().size());
        ordered.addAll(lowering);
        ordered.addAll(remaining);
        return List.copyOf(ordered);
    }

    protected static Map<String,Object> explicitEventApplicationRule(List<Map<String,Object>> rules) {
        for (Map<String,Object> rule : rules) {
            Map<String,Object> match = object(rule.get("match"));
            if (EVENT_SOUND_STUDIO_APPLICATION_ID.equals(text(match.get("app_id")))) return rule;
        }
        return null;
    }

    protected static void appendReferencedFilters(List<Map<String, Object>> filters, Map<String, Object> dsp,
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

    protected static void appendEnabledFilters(List<Map<String, Object>> target, Map<String, Object> eq, String label,
                                             boolean limiterEnabled) throws IOException {
        if (!Boolean.TRUE.equals(eq.get("enabled"))) return;
        for (Map<String, Object> filter : maps(eq.get("filters"))) {
            if (Boolean.FALSE.equals(filter.get("enabled"))) continue;
            if (number(filter.get("gain_db"), 0) > 0 && !limiterEnabled)
                throw new IOException("Positive EQ gain in " + label + " requires an active limiter");
            target.add(new LinkedHashMap<>(filter));
        }
    }

    protected static boolean hasOutputPath(String busId, Map<String, List<RoutePlan>> outgoing, Set<String> visiting) {
        if (!visiting.add(busId)) return false;
        for (RoutePlan route : outgoing.getOrDefault(busId, List.of())) {
            if (route.sink() != null) { visiting.remove(busId); return true; }
            if (route.targetBusId() != null && hasOutputPath(route.targetBusId(), outgoing, visiting)) { visiting.remove(busId); return true; }
        }
        visiting.remove(busId); return false;
    }

    protected static String configuredOutputRole(AudioNode sink, List<Map<String, Object>> devices) {
        List<Map<String, Object>> matching = devices.stream()
                .filter(device -> configuredDeviceMatches(sink, device)).toList();
        if (matching.size() != 1) return matching.isEmpty() ? "" : "ambiguous";
        return text(matching.getFirst().get("role"), "");
    }

    protected static String configuredOutputDeviceId(AudioNode sink, List<Map<String, Object>> devices) {
        List<Map<String, Object>> matching = devices.stream()
                .filter(device -> configuredDeviceMatches(sink, device)).toList();
        return matching.size() == 1 ? text(matching.getFirst().get("id"), "") : "";
    }

    private static boolean configuredDeviceMatches(AudioNode sink, Map<String, Object> device) {
        if (device.get("identity") instanceof Map<?, ?>)
            return DeviceDiscovery.resolve(List.of(sink), device).state() == DeviceDiscovery.State.ONLINE;
        return DeviceSelectorMatcher.matches(sink, object(device.get("selector")));
    }

    protected static boolean hasLowPass(List<Map<String, Object>> filters) {
        return filters.stream().anyMatch(filter -> "low_pass".equals(filter.get("type"))
                && !Boolean.FALSE.equals(filter.get("enabled")));
    }

    protected static boolean hasUnfilteredPathToSink(String busId, String sinkName,
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

    protected static boolean limiterEnabled(Map<String, Object> config) {
        return Boolean.TRUE.equals(object(object(config.get("dsp")).get("limiter")).get("enabled"));
    }
}
