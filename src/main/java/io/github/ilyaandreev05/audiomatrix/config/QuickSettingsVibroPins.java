package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class QuickSettingsVibroPins {
    private QuickSettingsVibroPins() {}

    static void migrateBassControls(Map<String, Object> config) {
        if (GraphCommonVibro.graph(config) == null) return;
        String routeId = GraphCommonVibro.COMMON_ROUTE_ID;
        String filterId = "octine-bass-low-pass";
        Map<String, Object> mixerPin = QuickSettingsConfig.pins(config).stream()
                .filter(pin -> routeId.equals(pin.get("route_id"))
                        && GraphCommonVibro.VIBRO_MIXER_ID.equals(pin.get("node_id")))
                .findFirst().orElse(null);
        if (mixerPin == null) return;
        String groupId = String.valueOf(mixerPin.get("group_id"));
        Map<String, Object> filterPin = QuickSettingsConfig.pins(config).stream()
                .filter(pin -> routeId.equals(pin.get("route_id")) && filterId.equals(pin.get("node_id")))
                .findFirst().orElse(null);
        if (filterPin == null) {
            int order = QuickSettingsConfig.pins(config).stream().filter(pin -> groupId.equals(pin.get("group_id")))
                    .mapToInt(pin -> pin.get("order") instanceof Number number ? number.intValue() : 0)
                    .max().orElse(0) + 1;
            filterPin = QuickSettingsConfig.pinNode(config, routeId, filterId, groupId, order, "COMPACT");
        }
        List<Map<String, Object>> controls = new ArrayList<>(maps(config.get("quick_controls")));
        config.put("quick_controls", controls);
        addControl(controls, filterPin, routeId, filterId, "octine-bass-frequency",
                "frequency_hz", "Частота басов", 0);
        addControl(controls, filterPin, routeId, filterId, "octine-bass-slope",
                "slope_db_oct", "Крутизна басового фильтра", 1);
    }

    private static void addControl(List<Map<String, Object>> controls, Map<String, Object> pin,
                                   String routeId, String nodeId, String id, String parameter,
                                   String label, int order) {
        Map<String, Object> control = controls.stream().filter(item -> id.equals(item.get("id"))).findFirst().orElse(null);
        if (control == null) {
            control = DefaultConfig.map("id", id, "route_id", routeId, "node_id", nodeId,
                    "parameter_id", parameter, "label", label, "section", "Подробности вибродинамика",
                    "order", order, "view", "MATRIX", "group_id", pin.get("group_id"));
            controls.add(control);
        }
        List<String> ids = pin.get("control_ids") instanceof List<?> raw
                ? raw.stream().map(String::valueOf).collect(java.util.stream.Collectors.toCollection(ArrayList::new))
                : new ArrayList<>();
        pin.put("control_ids", ids);
        if (!ids.contains(id)) ids.add(id);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
