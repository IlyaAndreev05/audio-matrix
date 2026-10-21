package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class QuickSettingsGroupLibrary {
    private QuickSettingsGroupLibrary() {}

    static Map<String, Object> create(Map<String, Object> config, String name, int order) {
        String normalizedName = requireName(name);
        if (order < 0) throw new IllegalArgumentException("Quick settings group order must not be negative");
        Map<String, Object> group = DefaultConfig.map("id", "quick-" + UUID.randomUUID(),
                "name", normalizedName, "order", order, "collapsed", false);
        QuickSettingsConfig.groups(config).add(group);
        return group;
    }

    static Map<String, Object> update(Map<String, Object> config, String groupId, String name,
                                      int order, boolean collapsed) {
        if (order < 0) throw new IllegalArgumentException("Quick settings group order must not be negative");
        Map<String, Object> group = requireGroup(config, groupId);
        group.put("name", requireName(name));
        group.put("order", order);
        group.put("collapsed", collapsed);
        return group;
    }

    static void remove(Map<String, Object> config, String groupId) {
        List<Map<String, Object>> groups = QuickSettingsConfig.groups(config);
        Map<String, Object> removed = requireGroup(config, groupId);
        String removedName = String.valueOf(removed.get("name"));
        List<Map<String, Object>> remaining = new ArrayList<>(groups);
        remaining.remove(removed);
        Map<String, Object> target;
        if (remaining.isEmpty()) {
            groups.remove(removed);
            target = create(config, "Без группы", 0);
        } else {
            target = remaining.stream().min(Comparator.comparingInt(item -> number(item.get("order")))).orElseThrow();
            groups.remove(removed);
        }
        String targetId = String.valueOf(target.get("id"));
        String targetName = String.valueOf(target.get("name"));
        int nextPinOrder = QuickSettingsConfig.pins(config).stream()
                .filter(pin -> targetId.equals(pin.get("group_id")))
                .mapToInt(pin -> number(pin.get("order"))).max().orElse(-1) + 1;
        for (Map<String, Object> pin : QuickSettingsConfig.pins(config)) {
            if (!groupId.equals(pin.get("group_id"))) continue;
            pin.put("group_id", targetId);
            pin.put("order", nextPinOrder++);
        }
        for (Map<String, Object> control : maps(config.get("quick_controls"))) {
            if (!groupId.equals(control.get("group_id"))) continue;
            control.put("group_id", targetId);
            if (removedName.equals(control.get("section"))) control.put("section", targetName);
        }
        DynamicInstanceRegistry.rehomeInstanceGroupOverrides(config, groupId, targetId);
        groups.sort(Comparator.comparingInt(item -> number(item.get("order"))));
        for (int index = 0; index < groups.size(); index++)
            groups.get(index).put("order", index);
    }

    private static Map<String, Object> requireGroup(Map<String, Object> config, String groupId) {
        return QuickSettingsConfig.groups(config).stream()
                .filter(group -> groupId != null && groupId.equals(group.get("id")))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Quick settings group does not exist: " + groupId));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Quick settings group name must not be blank");
        return name.trim();
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
