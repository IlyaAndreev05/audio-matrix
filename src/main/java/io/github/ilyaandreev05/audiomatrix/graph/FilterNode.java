package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FilterNode extends GraphNode {
    private final String processor;
    private final boolean enabled;
    private final Map<String, Object> parameters;
    public FilterNode(String id, String name, List<String> channels, String processor, boolean enabled,
                      Map<String, Object> parameters) {
        super(id, name, List.of(new AudioPort("in", AudioPort.Direction.INPUT, channels, true),
                new AudioPort("out", AudioPort.Direction.OUTPUT, channels, false)));
        if (processor == null || !List.of("eq", "high_pass", "low_pass", "delay", "phase_invert", "limiter").contains(processor))
            throw new IllegalArgumentException("Unsupported processor: " + processor);
        this.processor = processor;
        this.enabled = enabled;
        this.parameters = Map.copyOf(new LinkedHashMap<>(parameters == null ? Map.of() : parameters));
    }
    public String processor() { return processor; }
    public boolean enabled() { return enabled; }
    @Override public String type() { return "filter"; }
    @Override public Map<String, Object> parameters() {
        Map<String, Object> result = new LinkedHashMap<>(parameters);
        result.put("processor", processor);
        result.put("enabled", enabled);
        return Map.copyOf(result);
    }
}
