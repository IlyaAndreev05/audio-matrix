package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphSchema10OutputUpgradeTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void schemaTenUpgradeAddsOnlyMissingOutputGeneratorAndExcludesExactStaticIdentities() {
        DeviceIdentity monitor = identity("pci-monitor", "monitor-serial", "hdmi-output");
        DeviceIdentity usb = identity("usb-audio", "usb-serial", "analog-output-headphones");
        DynamicGeneratorNode applications = new DynamicGeneratorNode("apps", "Applications",
                "application:unassigned", STEREO, Map.of("connect_to", Map.of(
                "node_id", "merge", "port_id", "applications"), "instances", Map.of()));
        MixerNode merge = new MixerNode("merge", "Merge", STEREO,
                Map.of("applications", 1.0, "additional", 1.0));
        SwitchNode multi = new SwitchNode("multi", "MultiSwitch", STEREO,
                List.of("monitor", "usb"), List.of("in->monitor", "in->usb"));
        OutputNode monitorOutput = new OutputNode("monitor-output", "Monitor", "monitor-device", STEREO);
        DeviceSlotNode usbOutput = new DeviceSlotNode("usb-slot", "USB output", "usb-device", STEREO, 0.31, true);
        DynamicGeneratorNode hapticGenerator = new DynamicGeneratorNode("haptic-outputs", "Haptic output",
                "device:role:haptic_output", STEREO, Map.of("registry", "outputs",
                "connect_to", Map.of("node_id", "limiter", "port_id", "out"),
                "auto_select_trusted_hdmi_on_first_discovery", false));
        FilterNode limiter = new FilterNode("limiter", "Final haptic limiter", STEREO, "limiter", true, Map.of());
        AudioGraph graph = new AudioGraph("route-main", "Main",
                List.of(applications, merge, multi, monitorOutput, usbOutput, hapticGenerator, limiter), List.of(
                edge("merge-switch", "merge", "out", "multi", "in"),
                edge("switch-monitor", "multi", "monitor", "monitor-output", "in"),
                edge("switch-usb", "multi", "usb", "usb-slot", "in")));
        Map<String, Object> route = new LinkedHashMap<>(GraphCodec.toMap(graph));
        route.put("profile_id", "main");
        route.put("group_id", "work");
        List<Map<String, Object>> originalNodes = new ArrayList<>(maps(route.get("nodes")));
        List<Map<String, Object>> originalEdges = new ArrayList<>(maps(route.get("connections")));
        Map<String, Object> copy = Map.of("id", "saved-copy", "route", route);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("active_profile", "main");
        config.put("active_route", graph.id());
        config.put("profiles", List.of(Map.of("id", "main", "selected_route_id", graph.id())));
        config.put("devices", List.of(device("monitor-device", monitor), device("usb-device", usb)));
        config.put("route_library", List.of(route));
        config.put("saved_copies", List.of(copy));

        Map<String, Object> upgraded = GraphCodec.upgradeSchema10Config(config);
        Map<String, Object> upgradedRoute = maps(upgraded.get("route_library")).getFirst();
        List<Map<String, Object>> upgradedNodes = maps(upgradedRoute.get("nodes"));
        Map<String, Object> outputGenerator = upgradedNodes.stream()
                .filter(node -> "dynamic_generator".equals(node.get("type")))
                .map(node -> object(node.get("parameters")))
                .filter(parameters -> "outputs".equals(parameters.get("registry"))
                        && "device:*".equals(parameters.get("selector"))).findFirst().orElseThrow();
        Map<String, Object> upgradedCopy = maps(upgraded.get("saved_copies")).getFirst();
        Map<String, Object> copiedRoute = object(upgradedCopy.get("route"));
        List<Map<String, Object>> copiedNodes = maps(copiedRoute.get("nodes"));

        assertEquals(graph.id(), upgradedRoute.get("id"));
        assertEquals("main", upgraded.get("active_profile"));
        assertEquals(graph.id(), upgraded.get("active_route"));
        assertEquals("work", upgradedRoute.get("group_id"));
        assertEquals(originalNodes.stream().map(node -> node.get("id")).toList(),
                upgradedNodes.subList(0, originalNodes.size()).stream().map(node -> node.get("id")).toList());
        assertEquals(originalEdges, maps(upgradedRoute.get("connections")));
        assertEquals(Boolean.FALSE, outputGenerator.get("auto_select_trusted_hdmi_on_first_discovery"));
        assertEquals("device:*", outputGenerator.get("selector"));
        assertEquals(Map.of("node_id", "multi", "port_id", "in"), outputGenerator.get("connect_to"));
        assertTrue(strings(outputGenerator.get("excluded_selectors")).contains("device:" + monitor.fingerprint()));
        assertTrue(strings(outputGenerator.get("excluded_selectors")).contains("device:" + usb.fingerprint()));
        assertTrue(strings(outputGenerator.get("excluded_selectors")).contains("device:role:haptic_output"));
        assertEquals("usb-device", object(upgradedNodes.get(4).get("parameters")).get("device_id"));
        assertTrue(copiedNodes.stream().anyMatch(node -> "dynamic_generator".equals(node.get("type"))
                && "outputs".equals(object(node.get("parameters")).get("registry"))));
        Map<String, Object> reupgraded = GraphCodec.upgradeSchema10Config(upgraded);
        assertEquals(upgradedRoute, maps(reupgraded.get("route_library")).getFirst());
        assertEquals(route.get("nodes"), originalNodes);
    }

    private static DeviceIdentity identity(String busPath, String serial, String port) {
        return DeviceIdentity.from(Map.of("device.bus-path", busPath, "device.serial", serial,
                "device.profile.name", "stereo", "device.port.name", port, "audio.position", "FL,FR"), 2);
    }

    private static Map<String, Object> device(String id, DeviceIdentity identity) {
        return Map.of("id", id, "identity", identity.toMap());
    }

    private static GraphConnection edge(String id, String from, String fromPort, String to, String toPort) {
        return new GraphConnection(id, from, fromPort, to, toPort, true, 1.0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }
}
