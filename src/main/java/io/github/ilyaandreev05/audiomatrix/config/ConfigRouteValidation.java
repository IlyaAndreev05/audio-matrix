package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphSubgraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphValidator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfigRouteValidation {
    private ConfigRouteValidation() {}
    static void validateRouteChainReference(Object ref, Map<String, Object> routeChains, String path, List<String> errors) {
        if (ref == null) return;
        if (!(ref instanceof String text) || !text.startsWith("dsp.route_chains.") || text.length() == "dsp.route_chains.".length())
            errors.add(path + " must reference dsp.route_chains.<id>");
        else if (!routeChains.containsKey(text.substring("dsp.route_chains.".length())))
            errors.add(path + " references a missing route chain");
    }
    static void validateRouteChainChannels(Object ref, Map<String, Object> routeChains, Set<String> positions,
                                                   String path, List<String> errors) {
        if (!(ref instanceof String text) || !text.startsWith("dsp.route_chains.")) return;
        Object chainValue = routeChains.get(text.substring("dsp.route_chains.".length()));
        if (!(chainValue instanceof Map<?, ?> chain) || !(chain.get("filters") instanceof List<?> filters)) return;
        for (Object filterValue : filters) {
            if (!(filterValue instanceof Map<?, ?> filter) || !(filter.get("channels") instanceof List<?> channels)) continue;
            for (Object channel : channels) if (!positions.contains(channel)) {
                errors.add(path + " has a channel-scoped filter that is unavailable on " + String.join("/", positions));
                break;
            }
        }
    }
    static boolean monoChannelMap(String channelMap) {
        return Set.of("mono", "mono_sum", "left", "right", "center", "lfe", "rear_left", "rear_right",
                "rear_sum", "side_left", "side_right", "side_sum", "surround_sum", "all_sum").contains(channelMap);
    }
    static boolean channelMapMaySum(String channelMap) {
        return Set.of("mono", "mono_sum", "rear_sum", "side_sum", "surround_sum", "all_sum").contains(channelMap);
    }
    static boolean supportsChannelMap(String channelMap, String layout) {
        return switch (channelMap) {
            case "center", "lfe", "rear_left", "rear_right", "rear_sum", "surround_sum" -> Set.of("5.1", "7.1").contains(layout);
            case "side_left", "side_right", "side_sum" -> "7.1".equals(layout);
            default -> true;
        };
    }
    static List<String> channelPositions(String layout) {
        return switch (layout) {
            case "mono" -> List.of("MONO");
            case "5.1" -> List.of("FL", "FR", "FC", "LFE", "RL", "RR");
            case "7.1" -> List.of("FL", "FR", "FC", "LFE", "RL", "RR", "SL", "SR");
            default -> List.of("FL", "FR");
        };
    }
    static void checkRouteCycles(List<Map<String, Object>> routes, Set<String> busIds, List<String> errors) {
        Map<String, Set<String>> graph = new java.util.HashMap<>();
        for (String bus : busIds) graph.put(bus, new HashSet<>());
        for (Map<String, Object> route : routes) {
            if (Boolean.FALSE.equals(route.get("enabled"))) continue;
            String from = String.valueOf(route.getOrDefault("from", ""));
            String to = String.valueOf(route.getOrDefault("to", ""));
            if (graph.containsKey(from) && graph.containsKey(to)) graph.get(from).add(to);
        }
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (String bus : busIds) if (hasCycle(bus, graph, visiting, visited)) {
            errors.add("routes contain a feedback cycle"); return;
        }
    }
    static boolean hasCycle(String node, Map<String, Set<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(node)) return true;
        if (!visited.add(node)) return false;
        visiting.add(node);
        for (String next : graph.getOrDefault(node, Set.of())) if (hasCycle(next, graph, visiting, visited)) return true;
        visiting.remove(node); return false;
    }}
