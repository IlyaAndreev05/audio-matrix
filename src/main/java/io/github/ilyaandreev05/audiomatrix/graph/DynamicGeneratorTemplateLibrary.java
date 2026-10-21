package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Default template graphs and registry/template compatibility rules for dynamic generators. */
final class DynamicGeneratorTemplateLibrary {
    private DynamicGeneratorTemplateLibrary() {}

    /** The node and connection templates a generator starts from. */
    record DefaultTemplate(List<Map<String, Object>> nodes, List<Map<String, Object>> connections) {}

    static String defaultTemplate(String registry) {
        return switch (registry) {
            case "outputs" -> "default-output";
            case "browser_tabs" -> "default-tab";
            default -> "default-application";
        };
    }

    static boolean compatibleTemplate(String registry, String template) {
        return switch (registry) {
            case "applications" -> "default-application".equals(template);
            case "outputs" -> "default-output".equals(template);
            case "browser_tabs" -> "default-tab".equals(template);
            default -> false;
        };
    }

    static DefaultTemplate defaultTemplateGraph(String registry, String selector, List<String> channels,
                                                 double initialGain) {
        String target = switch (registry) {
            case "outputs" -> "output_device";
            case "browser_tabs" -> "browser_tab";
            default -> "application_stream";
        };
        List<Map<String, Object>> nodes;
        List<Map<String, Object>> connections;
        if ("outputs".equals(registry)) {
            nodes = List.of(
                    templateNode("gain", "Volume", "gain", channels,
                            Map.of("gain", initialGain, "muted", false, "gain_target", target)),
                    templateNode("output", "Output", "output", channels,
                            Map.of("device_id", "device:unbound")));
            connections = List.of(templateConnection("gain-output", "gain", "out", "output", "in"));
        } else {
            nodes = List.of(
                    templateNode("source", "Source", "source", channels, Map.of("selector", selector)),
                    templateNode("gain", "Volume", "gain", channels,
                            Map.of("gain", initialGain, "muted", false, "gain_target", target)));
            connections = List.of(templateConnection("source-gain", "source", "out", "gain", "in"));
        }
        return new DefaultTemplate(nodes, connections);
    }

    private static Map<String, Object> templateNode(String id, String name, String type, List<String> channels,
                                                    Map<String, Object> parameters) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("name", name);
        node.put("type", type);
        node.put("channels", channels);
        node.put("parameters", parameters);
        return node;
    }

    private static Map<String, Object> templateConnection(String id, String fromNode, String fromPort,
                                                           String toNode, String toPort) {
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("id", id);
        connection.put("from_node", fromNode);
        connection.put("from_port", fromPort);
        connection.put("to_node", toNode);
        connection.put("to_port", toPort);
        connection.put("enabled", true);
        connection.put("gain", 1.0);
        return connection;
    }
}
