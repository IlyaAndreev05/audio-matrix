package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import io.github.ilyaandreev05.audiomatrix.graph.EventSoundStudioSource;

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

abstract class PipeWireRouteStaticSupport01 extends PipeWireRouteTypes {
    static final String EVENT_SOUND_STUDIO_APPLICATION_ID = EventSoundStudioSource.APPLICATION_ID;
    protected static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);
    protected static final int START_ATTEMPTS = 30;
    protected static final long START_WAIT_MS = 100;

    protected static boolean isAudioMatrixGraphLink(Link link) {
        String outputNode = link.output().substring(0, link.output().indexOf(':'));
        String inputNode = link.input().substring(0, link.input().indexOf(':'));
        return outputNode.startsWith("audiomatrix_") || inputNode.startsWith("audiomatrix_");
    }

    protected static List<Link> linksFromNode(String listing, String nodeName) {
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

    protected static List<Link> distinct(List<Link> links) { return List.copyOf(new LinkedHashSet<>(links)); }

    protected static Map<String, String> channelPorts(List<String> ports) {
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

    protected static List<String> positions(String layout) {
        return switch (layout) {
            case "mono" -> List.of("MONO");
            case "5.1" -> List.of("FL", "FR", "FC", "LFE", "RL", "RR");
            case "7.1" -> List.of("FL", "FR", "FC", "LFE", "RL", "RR", "SL", "SR");
            default -> List.of("FL", "FR");
        };
    }

    protected static List<String> routePositions(String channelMap) {
        return monoChannelMap(channelMap) ? List.of("MONO") : List.of("FL", "FR");
    }

    protected static boolean monoChannelMap(String channelMap) {
        return Set.of("mono", "mono_sum", "left", "right", "center", "lfe", "rear_left", "rear_right",
                "rear_sum", "side_left", "side_right", "side_sum", "surround_sum", "all_sum").contains(channelMap);
    }

    protected static List<String> streamPositions(int channels) {
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

    protected static String positionArg(List<String> positions) { return "[ " + String.join(" ", positions) + " ]"; }

    protected static String busNodeName(String busId) { return "audiomatrix_bus_" + shortHash(busId); }

    protected static String routeNodeName(String edge, String routeId) { return "audiomatrix_route_" + shortHash(routeId) + "_" + edge; }

    protected static String applicationNodeName(String edge, String streamName) { return "audiomatrix_app_" + shortHash(streamName) + "_" + edge; }

    protected static String outputNodeName(String edge, String sinkName) { return "audiomatrix_output_" + shortHash(sinkName) + "_" + edge; }

    protected static String shortHash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 12); }
        catch (Exception e) { return Integer.toHexString(value.hashCode()); }
    }


    protected static void requireCommands(String... names) throws IOException {
        for (String name : names) {
            boolean found = java.util.Arrays.stream(System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
                    .anyMatch(path -> Files.isExecutable(Path.of(path, name)));
            if (!found) throw new IOException(name + " is required to apply PipeWire routes");
        }
    }


    @SuppressWarnings("unchecked") protected static List<Map<String, Object>> maps(Object value) { return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of(); }

    @SuppressWarnings("unchecked") protected static Map<String, Object> object(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }

    protected static List<String> strings(Object value) { return value instanceof List<?> list ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of(); }

    protected static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    protected static String text(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }

    protected static double number(Object value, double fallback) { return value instanceof Number n ? n.doubleValue() : fallback; }

    protected static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    protected static String message(Throwable e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
