package io.github.ilyaandreev05.audiomatrix.audio;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Adds the stable virtual event monitor to the active PipeWire stream plan. */
final class EventInputStreamPlanner {
    @FunctionalInterface
    interface ApplicationFilterReader {
        List<Map<String, Object>> read(Map<String, Object> rule, Map<String, Object> config) throws IOException;
    }

    private EventInputStreamPlanner() {}

    static List<String> append(Map<String, Object> config, Map<String, PipeWireRouteTypes.BusPlan> buses,
                               List<PipeWireRouteTypes.StreamPlan> streams, ApplicationFilterReader filters)
            throws IOException {
        List<String> busIds = PipeWireRouteStaticSupport01.strings(config.get("event_input_buses")).stream()
                .filter(buses::containsKey).toList();
        Map<String, Object> rule = PipeWireRouteStaticSupport03.explicitEventApplicationRule(
                PipeWireRouteStaticSupport01.maps(config.get("applications")));
        for (String busId : busIds) {
            List<Map<String, Object>> appFilters = rule == null ? List.of() : filters.read(rule, config);
            String name = EventInputMonitor.NODE_NAME;
            streams.add(new PipeWireRouteTypes.StreamPlan(EventInputMonitor.node(), "", "", busId, appFilters,
                    List.of("FL", "FR"), "event-input:" + busId,
                    PipeWireRouteStaticSupport01.applicationNodeName("dsp_input", name),
                    PipeWireRouteStaticSupport01.applicationNodeName("dsp_output", name)));
        }
        return busIds;
    }
}
