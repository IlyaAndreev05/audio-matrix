package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Applies Shell drag/drop ordering to the canonical persisted groups. */
final class GnomeGroupOrder {
    private GnomeGroupOrder() {}
    static void apply(Map<String, Object> config, String value) {
        String[] fields = value.split("\\t", -1);
        if (fields.length != 2 || fields[0].isBlank())
            throw new IllegalArgumentException("Group order is malformed");
        int target;
        try { target = Integer.parseInt(fields[1]); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Group index is invalid"); }
        List<Map<String, Object>> groups = new ArrayList<>(QuickSettingsConfig.groups(config));
        groups.sort(Comparator.comparingInt(group -> group.get("order") instanceof Number n ? n.intValue() : 0));
        Map<String, Object> moved = groups.stream().filter(group -> fields[0].equals(group.get("id")))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Group no longer exists"));
        if (target < 0 || target >= groups.size()) throw new IllegalArgumentException("Group index is out of range");
        groups.remove(moved);
        groups.add(target, moved);
        for (int index = 0; index < groups.size(); index++) groups.get(index).put("order", index);
    }
}
