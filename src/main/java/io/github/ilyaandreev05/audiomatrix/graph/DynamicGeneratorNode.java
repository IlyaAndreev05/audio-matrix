package io.github.ilyaandreev05.audiomatrix.graph;

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
        this.parameters = DynamicGeneratorParameters.immutableMap(
                DynamicGeneratorParameters.normalizeParameters(selector, this.channels, parameters));
    }

    static Map<String, Object> normalizeParameters(String selector, List<String> channels,
                                                   Map<String, Object> parameters) {
        return DynamicGeneratorParameters.normalizeParameters(selector, channels, parameters);
    }

    @Override public String type() { return "dynamic_generator"; }
    @Override public Map<String, Object> parameters() { return parameters; }

    public List<String> channels() { return channels; }
    public String selector() { return DynamicGeneratorParameters.text(parameters.get("selector"), ""); }
    public String registry() { return DynamicGeneratorParameters.text(parameters.get("registry"), "applications"); }
    public String template() { return DynamicGeneratorParameters.text(parameters.get("template"), defaultTemplate(registry())); }
    public List<Map<String, Object>> templateNodes() { return DynamicGeneratorParameters.maps(parameters.get("template_nodes")); }
    public List<Map<String, Object>> templateConnections() { return DynamicGeneratorParameters.maps(parameters.get("template_connections")); }
    public Map<String, Object> connectTo() { return DynamicGeneratorParameters.map(parameters.get("connect_to")); }
    public double initialGain() { return DynamicGeneratorParameters.number(parameters.get("initial_gain"), 1.0); }
    public boolean autoPin() { return DynamicGeneratorParameters.bool(parameters.get("auto_pin"), true); }
    public String groupId() { return DynamicGeneratorParameters.text(parameters.get("group_id"), ""); }
    public boolean retainOnDisappear() { return DynamicGeneratorParameters.bool(parameters.get("retain_on_disappear"), true); }
    public boolean restoreOnReappear() { return DynamicGeneratorParameters.bool(parameters.get("restore_on_reappear"), true); }
    public boolean autoSelectTrustedHdmiOnFirstDiscovery() {
        return DynamicGeneratorParameters.bool(parameters.get("auto_select_trusted_hdmi_on_first_discovery"), false);
    }
    public List<String> excludedSelectors() { return DynamicGeneratorParameters.strings(parameters.get("excluded_selectors")); }
    public Map<String, Object> instances() { return DynamicGeneratorParameters.map(parameters.get("instances")); }

    public DynamicGeneratorNode withId(String id) {
        return new DynamicGeneratorNode(id, name(), selector(), channels, parameters);
    }

    public DynamicGeneratorNode withInstances(Map<String, Object> instances) {
        Map<String, Object> updated = DynamicGeneratorParameters.mutableMap(parameters);
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
        Map<String, Object> updated = DynamicGeneratorParameters.mutableMap(parameters);
        updated.put(key, value);
        return new DynamicGeneratorNode(id(), name(), nextSelector, channels, updated);
    }

    static String defaultTemplate(String registry) {
        return DynamicGeneratorTemplateLibrary.defaultTemplate(registry);
    }

    static boolean compatibleTemplate(String registry, String template) {
        return DynamicGeneratorTemplateLibrary.compatibleTemplate(registry, template);
    }
}
