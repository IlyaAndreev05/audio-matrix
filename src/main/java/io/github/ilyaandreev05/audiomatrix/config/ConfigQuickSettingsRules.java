package io.github.ilyaandreev05.audiomatrix.config;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfigQuickSettingsRules {
    private ConfigQuickSettingsRules() {}

    static void validate(Map<String, Object> config, List<String> errors) {
        Map<String, Object> settings = ConfigValidationValues.object(config.get("quick_settings"), "quick_settings", errors);
        if (settings == null) return;
        List<Map<String, Object>> groups = ConfigValidationValues.optionalList(settings.get("groups"), "quick_settings.groups", errors);
        ConfigValidationValues.checkUniqueIds(groups, "quick_settings.groups", errors);
        Set<String> groupIds = new HashSet<>();
        for (int index = 0; index < groups.size(); index++) {
            Map<String, Object> group = groups.get(index);
            String id = String.valueOf(group.getOrDefault("id", ""));
            if (!id.isBlank()) groupIds.add(id);
            if (!(group.get("name") instanceof String name) || name.isBlank())
                errors.add("quick_settings.groups[" + index + "].name must not be blank");
            Object order = group.get("order");
            if (!(order instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue()) || number.intValue() < 0)
                errors.add("quick_settings.groups[" + index + "].order must be a non-negative integer");
            if (!(group.get("collapsed") instanceof Boolean))
                errors.add("quick_settings.groups[" + index + "].collapsed must be boolean");
        }
        validatePins(settings, groupIds, errors);
        List<Map<String, Object>> controls = ConfigValidationValues.optionalList(config.get("quick_controls"), "quick_controls", errors);
        for (int index = 0; index < controls.size(); index++) {
            String groupId = String.valueOf(controls.get(index).getOrDefault("group_id", ""));
            if (groupId.isBlank() || !groupIds.contains(groupId))
                errors.add("quick_controls[" + index + "].group_id must reference a quick settings group");
        }
    }

    private static void validatePins(Map<String, Object> settings, Set<String> groupIds, List<String> errors) {
        List<Map<String, Object>> pins = ConfigValidationValues.optionalList(
                settings.get("pins"), "quick_settings.pins", errors);
        ConfigValidationValues.checkUniqueIds(pins, "quick_settings.pins", errors);
        Set<List<Object>> targets = new HashSet<>();
        for (int index = 0; index < pins.size(); index++) {
            Map<String, Object> pin = pins.get(index);
            for (String key : List.of("route_id", "node_id", "group_id"))
                if (!(pin.get(key) instanceof String value) || value.isBlank())
                    errors.add("quick_settings.pins[" + index + "]." + key + " must not be blank");
            if (!targets.add(java.util.Arrays.asList(pin.getOrDefault("route_id", ""), pin.getOrDefault("node_id", ""),
                    pin.getOrDefault("instance_key", ""), pin.getOrDefault("child_id", ""))))
                errors.add("quick_settings.pins[" + index + "] duplicates a pinned target");
            for (String key : List.of("display_name", "icon", "instance_key", "child_id"))
                if (pin.containsKey(key) && !(pin.get(key) instanceof String))
                    errors.add("quick_settings.pins[" + index + "]." + key + " must be a string");
            for (String key : List.of("show_children", "show_when_offline", "expanded", "pinned"))
                if (pin.containsKey(key) && !(pin.get(key) instanceof Boolean))
                    errors.add("quick_settings.pins[" + index + "]." + key + " must be boolean");
            if (pin.containsKey("child_id") && String.valueOf(pin.getOrDefault("instance_key", "")).isBlank())
                errors.add("quick_settings.pins[" + index + "].child_id requires instance_key");
            if (!groupIds.contains(String.valueOf(pin.get("group_id"))))
                errors.add("quick_settings.pins[" + index + "].group_id must reference a quick settings group");
            Object order = pin.get("order");
            if (!(order instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue()) || number.intValue() < 0)
                errors.add("quick_settings.pins[" + index + "].order must be a non-negative integer");
            if (!Set.of("DEFAULT", "COMPACT", "EXPANDED").contains(pin.get("view")))
                errors.add("quick_settings.pins[" + index + "].view is unsupported");
            if (!(pin.get("control_ids") instanceof List<?> controls))
                errors.add("quick_settings.pins[" + index + "].control_ids must be an array");
            else if (controls.stream().anyMatch(item -> !(item instanceof String)))
                errors.add("quick_settings.pins[" + index + "].control_ids must contain strings");
        }
    }
}
