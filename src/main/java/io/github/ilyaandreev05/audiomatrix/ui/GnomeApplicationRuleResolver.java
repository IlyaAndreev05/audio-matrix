package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import java.util.List;
import java.util.Map;

/** Resolves or creates canonical applications[] rules for generated app controls. */
final class GnomeApplicationRuleResolver {
    private GnomeApplicationRuleResolver() {}

    static Map<String, Object> only(Map<String, Object> config, String selector, Map<String, Object> binding,
            PipeWireBackend.Snapshot snapshot) {
        return Map.of("applications", List.of(ensureResolved(config, selector, binding, snapshot)));
    }

    static Map<String, Object> ensureResolved(Map<String, Object> config, String selector,
            Map<String, Object> binding, PipeWireBackend.Snapshot snapshot) {
        Map<String, Object> existing = find(config, selector, binding);
        if (existing != null) return existing;
        DynamicInstanceRegistry.InstanceSnapshot observation = observation(binding, snapshot);
        String ruleId = DynamicInstanceRegistry.ensureApplicationRule(config, observation)
                .map(DynamicInstanceRegistry.ApplicationRuleResolution::ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Generated application rule is unavailable"));
        return ruleById(config, ruleId);
    }

    static Map<String, Object> ensure(Map<String, Object> config, String groupId) {
        String field;
        String value;
        if (groupId.startsWith("app:")) {
            field = "app_id";
            value = groupId.substring(4);
        } else if (groupId.startsWith("process:")) {
            field = "process";
            value = groupId.substring(8);
        } else {
            throw new IllegalArgumentException("A transient stream cannot be saved as an application group");
        }
        if (value.isBlank()) throw new IllegalArgumentException("Application identity cannot be blank");
        String appId = "app_id".equals(field) ? value : "";
        String process = "process".equals(field) ? value : "";
        Map<String, Object> binding = Map.of("applicationId", appId, "processName", process,
                "hasPcmBinding", true, "audioMatrixOwned", false);
        String selector = "app_id".equals(field) ? "application:" + appId : "process:" + process;
        DynamicInstanceRegistry.InstanceSnapshot observation = new DynamicInstanceRegistry.InstanceSnapshot(
                "applications", groupId, groupId, selector, List.of("FL", "FR"), binding,
                DynamicInstanceRegistry.Confidence.HIGH);
        String ruleId = DynamicInstanceRegistry.ensureApplicationRule(config, observation)
                .map(DynamicInstanceRegistry.ApplicationRuleResolution::ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Application rule is unavailable"));
        return ruleById(config, ruleId);
    }

    private static Map<String, Object> ruleById(Map<String, Object> config, String ruleId) {
        return maps(config.get("applications")).stream()
                .filter(rule -> ruleId.equals(rule.get("id"))).findFirst()
                .orElseThrow(() -> new IllegalStateException("Application rule identity was not retained"));
    }

    private static DynamicInstanceRegistry.InstanceSnapshot observation(Map<String, Object> binding,
            PipeWireBackend.Snapshot snapshot) {
        if (snapshot == null || !snapshot.connected())
            throw new IllegalArgumentException("Generated application stream is unavailable");
        Object raw = binding.get("streams");
        if (!(raw instanceof List<?> streams))
            throw new IllegalArgumentException("Generated application has no live stream binding");
        for (Object item : streams) {
            if (!(item instanceof Map<?, ?> stream) || !(stream.get("nodeId") instanceof Number id)) continue;
            AudioNode live = snapshot.streams().stream()
                    .filter(candidate -> candidate.id() == id.intValue()).findFirst().orElse(null);
            if (live != null) return DynamicInstanceRegistry.application(live);
        }
        throw new IllegalArgumentException("Generated application stream binding is stale");
    }


    static Map<String, Object> find(Map<String, Object> config, String selector, Map<String, Object> binding) {
        String appId = text(binding.get("applicationId"));
        String process = text(binding.get("processName"));
        if (selector.startsWith("application:")) {
            String selected = selector.substring("application:".length());
            if (!selected.isBlank() && !"unassigned".equals(selected) && !"*".equals(selected)
                    && !selected.equals(appId))
                throw new IllegalArgumentException("Generated application identity no longer matches its selector");
        } else if (selector.startsWith("process:")) {
            String selected = selector.substring("process:".length());
            if (!selected.equals(process))
                throw new IllegalArgumentException("Generated process identity no longer matches its selector");
        }
        for (Map<String, Object> rule : maps(config.get("applications"))) {
            Map<String, Object> match = object(rule.get("match"));
            String ruleApp = text(match.get("app_id"));
            String ruleProcess = text(match.get("process"));
            if (ruleApp.isBlank() && ruleProcess.isBlank()) continue;

            if (!ruleApp.isBlank() && !ruleApp.equals(appId)) continue;
            if (!ruleProcess.isBlank() && !ruleProcess.equals(process)) continue;
            return rule;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }
}
