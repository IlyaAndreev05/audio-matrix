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

abstract class PipeWireRouteStaticSupport02 extends PipeWireRouteStaticSupport01 {
    protected static double pathEqGain(String busId, String sinkName, Map<String, BusPlan> buses,
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

    protected static double pathGain(String busId, String sinkName, Map<String, BusPlan> buses,
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

    protected static int channelMapSourceCount(String channelMap, BusPlan bus) {
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

    protected static double positiveEqGain(List<Map<String, Object>> filters) {
        double gain = 0;
        for (Map<String, Object> filter : filters) gain += Math.max(0, number(filter.get("gain_db"), 0));
        return gain;
    }

    protected static double dbToLinear(double decibels) { return Math.pow(10.0, decibels / 20.0); }

    protected static int transitionMillis(Map<String, Object> config) {
        return Math.max(0, Math.min(1000, (int) number(object(config.get("engine")).get("transition_ms"), 120)));
    }

    protected static void sleepUntil(long deadlineNanos) throws InterruptedException {
        long remaining;
        while ((remaining = deadlineNanos - System.nanoTime()) > 0)
            TimeUnit.NANOSECONDS.sleep(remaining);
    }

    protected static double effectiveRoutePlaybackVolume(Plan plan, RoutePlan route) {
        double routeVolume = MixLevelMath.effectiveRouteVolume(route.volume(), route.gainDb());
        return route.sink() == null ? routeVolume : routeVolume * plan.masterVolume();
    }

    protected static boolean effectiveRoutePlaybackMuted(Plan plan, RoutePlan route) {
        return route.muted() || (route.sink() != null && plan.masterMuted());
    }

    protected static boolean sameSink(AudioNode left, AudioNode right) {
        return left == null ? right == null : right != null && left.name().equals(right.name())
                && java.util.Objects.equals(left.identity(), right.identity());
    }
}
