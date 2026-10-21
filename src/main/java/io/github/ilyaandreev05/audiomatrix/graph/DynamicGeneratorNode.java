package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A control-plane generator whose registry expands matching streams into an effective graph. */
public final class DynamicGeneratorNode extends GraphNode {
    private final List<String> channels;
    private final Map<String, Object> parameters;

    public DynamicGeneratorNode(String id, String name, String selector, List<String> channels) {
        this(id, name, selector, channels, Map.of());
    }

    public DynamicGeneratorNode(String id, String name, String selector, List<String> channels,
                                Map<String, Object> parameters) {
        super(id, name, List.of());
        if (selector == null || selector.isBlank()) throw new IllegalArgumentException("Generator selector must not be blank");
        this.channels = List.copyOf(channels);
        if (this.channels.isEmpty() || this.channels.stream().anyMatch(channel -> channel == null || channel.isBlank()))
            throw new IllegalArgumentException("Generator channels must not be empty");
        if (this.channels.stream().distinct().count() != this.channels.size())
            throw new IllegalArgumentException("Generator channels must be unique");
        this.parameters = immutableMap(normalizeParameters(selector, this.channels, parameters));
    }

    static Map<String, Object> normalizeParameters(String selector, List<String> channels,
                                                   Map<String, Object> parameters) {
        Map<String, Object> normalized = mutableMap(parameters);
        normalized.put("selector", selector);
        putDefault(normalized, "registry", "applications");
        String registry = text(normalized.get("registry"), "applications");
        putDefault(normalized, "template", defaultTemplate(registry));
        putDefault(normalized, "initial_gain", 1.0);
        putDefault(normalized, "auto_pin", Boolean.TRUE);
        putDefault(normalized, "group_id", "");
        putDefault(normalized, "retain_on_disappear", Boolean.TRUE);
        putDefault(normalized, "restore_on_reappear", Boolean.TRUE);
        putDefault(normalized, "excluded_selectors", List.of());
        putDefault(normalized, "instances", Map.of());
        if (!normalized.containsKey("template_nodes") || !normalized.containsKey("template_connections")) {
            DefaultTemplate defaults = defaultTemplateGraph(registry, selector, channels,
                    number(normalized.get("initial_gain"), 1.0));
            putDefault(normalized, "template_nodes", defaults.nodes());
            putDefault(normalized, "template_connections", defaults.connections());
        }
        return normalized;
    }

    private static void putDefault(Map<String, Object> parameters, String key, Object value) {
        if (!parameters.containsKey(key)) parameters.put(key, value);
    }

    @Override public String type() { return "dynamic_generator"; }
    @Override public Map<String, Object> parameters() { return parameters; }

    public List<String> channels() { return channels; }
    public String selector() { return text(parameters.get("selector"), ""); }
    public String registry() { return text(parameters.get("registry"), "applications"); }
    public String template() { return text(parameters.get("template"), defaultTemplate(registry())); }
    public List<Map<String, Object>> templateNodes() { return maps(parameters.get("template_nodes")); }
    public List<Map<String, Object>> templateConnections() { return maps(parameters.get("template_connections")); }
    public Map<String, Object> connectTo() { return map(parameters.get("connect_to")); }
    public double initialGain() { return number(parameters.get("initial_gain"), 1.0); }
    public boolean autoPin() { return bool(parameters.get("auto_pin"), true); }
    public String groupId() { return text(parameters.get("group_id"), ""); }
    public boolean retainOnDisappear() { return bool(parameters.get("retain_on_disappear"), true); }
    public boolean restoreOnReappear() { return bool(parameters.get("restore_on_reappear"), true); }
    public boolean autoSelectTrustedHdmiOnFirstDiscovery() {
        return bool(parameters.get("auto_select_trusted_hdmi_on_first_discovery"), false);
    }
    public List<String> excludedSelectors() { return strings(parameters.get("excluded_selectors")); }
    public Map<String, Object> instances() { return map(parameters.get("instances")); }

    public DynamicGeneratorNode withId(String id) {
        return new DynamicGeneratorNode(id, name(), selector(), channels, parameters);
    }

    public DynamicGeneratorNode withInstances(Map<String, Object> instances) {
        Map<String, Object> updated = mutableMap(parameters);
        updated.put("instances", instances);
        return new DynamicGeneratorNode(id(), name(), selector(), channels, updated);
    }
    public DynamicGeneratorNode withParameter(String key, Object value) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Parameter key must not be blank");
        String nextSelector = selector();
        if ("selector".equals(key)) {
            if (!(value instanceof String text)) throw new IllegalArgumentException("Generator selector must be a string");
            nextSelector = text;
        }
        Map<String, Object> updated = mutableMap(parameters);
        updated.put(key, value);
        return new DynamicGeneratorNode(id(), name(), nextSelector, channels, updated);
    }

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

    private static DefaultTemplate defaultTemplateGraph(String registry, String selector, List<String> channels,
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

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) result.add(stringMap(map));
        return List.copyOf(result);
    }

    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? stringMap(map) : Map.of();
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> mutableMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source != null) source.forEach((key, value) -> result.put(String.valueOf(key), mutableValue(value)));
        return result;
    }

    private static Object mutableValue(Object value) {
        if (value instanceof Map<?, ?> map) return mutableMap(map);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(mutableValue(item)));
            return result;
        }
        return value;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), immutableValue(item)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(immutableValue(item)));
            return Collections.unmodifiableList(result);
        }
        return value;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean result ? result : fallback;
    }

    private record DefaultTemplate(List<Map<String, Object>> nodes, List<Map<String, Object>> connections) {}
}
