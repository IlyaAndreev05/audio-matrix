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

abstract class PipeWireRouteStaticSupport04 extends PipeWireRouteStaticSupport03 {
    protected static List<Link> channelLinks(List<String> outputs, List<String> inputs, String channelMap, boolean toCapture) {
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

    protected static List<Link> positionLinks(List<String> outputs, List<String> inputs) {
        Map<String, String> outByChannel = channelPorts(outputs);
        Map<String, String> inByChannel = channelPorts(inputs);
        List<Link> links = new ArrayList<>();
        for (String channel : outByChannel.keySet())
            if (inByChannel.containsKey(channel)) links.add(new Link(outByChannel.get(channel), inByChannel.get(channel)));
        return distinct(links);
    }
}
