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
        List<Map<String, Object>> controls = ConfigValidationValues.optionalList(config.get("quick_controls"), "quick_controls", errors);
        for (int index = 0; index < controls.size(); index++) {
            String groupId = String.valueOf(controls.get(index).getOrDefault("group_id", ""));
            if (groupId.isBlank() || !groupIds.contains(groupId))
                errors.add("quick_controls[" + index + "].group_id must reference a quick settings group");
        }
    }
}
