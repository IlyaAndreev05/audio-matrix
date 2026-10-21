package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class DynamicRegistryApplicationRules {
    private DynamicRegistryApplicationRules() {}

    static Optional<DynamicInstanceRegistry.ApplicationRuleResolution> ensure(
            Map<String, Object> config, DynamicInstanceRegistry.InstanceSnapshot snapshot) {
        if (!eligible(snapshot)) return Optional.empty();
        String appId = string(snapshot.runtimeBinding().get("applicationId"));
        String process = string(snapshot.runtimeBinding().get("processName"));
        String field = appId.isBlank() ? "process" : "app_id";
        String value = appId.isBlank() ? process : appId;
        List<?> existingRules = config.get("applications") instanceof List<?> list ? list : List.of();
        for (Object item : existingRules) {
            if (!(item instanceof Map<?, ?> rule) || !resolves(rule.get("match"), appId, process)) continue;
            String existingId = string(rule.get("id"));
            return existingId.isBlank() ? Optional.empty()
                    : Optional.of(new DynamicInstanceRegistry.ApplicationRuleResolution(existingId, false));
        }
        String id = uniqueId(existingRules, field, value);
        Map<String, Object> match = new LinkedHashMap<>();
        match.put(field, value);
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", id);
        rule.put("match", match);
        rule.put("volume", 1.0);
        rule.put("mute", false);
        List<Object> updatedRules = new ArrayList<>(existingRules);
        updatedRules.add(rule);
        config.put("applications", updatedRules);
        return Optional.of(new DynamicInstanceRegistry.ApplicationRuleResolution(id, true));
    }

    private static boolean eligible(DynamicInstanceRegistry.InstanceSnapshot snapshot) {
        if (snapshot == null || !"applications".equals(snapshot.registry())
                || snapshot.confidence() != DynamicInstanceRegistry.Confidence.HIGH) return false;
        Map<String, Object> binding = snapshot.runtimeBinding();
        String appId = string(binding.get("applicationId"));
        String process = string(binding.get("processName"));
        if ((appId.isBlank() && process.isBlank()) || !Boolean.TRUE.equals(binding.get("hasPcmBinding"))) return false;
        if (Boolean.TRUE.equals(binding.get("audioMatrixOwned")) || bindingText(binding, "browserAdapter")
                || DynamicRegistrySystemInput.APPLICATION_ID.equals(appId)
                || (snapshot.stableKey().startsWith("browser-parent:") && emptyStreams(binding))) return false;
        return !"io.github.ilyaandree05.eventsoundstudio".equals(appId)
                && !EventSoundStudioSource.APPLICATION_ID.equals(appId)
                && !"io.github.ilyaandree05.audiomatrix".equals(appId)
                && !appId.startsWith("io.github.ilyaandree05.audiomatrix.")
                && !("application:" + EventSoundStudioSource.APPLICATION_ID).equals(snapshot.selector());
    }

    private static boolean resolves(Object rawMatch, String appId, String process) {
        if (!(rawMatch instanceof Map<?, ?> match)) return false;
        String matchedApp = string(match.get("app_id"));
        String matchedProcess = string(match.get("process"));
        if (matchedApp.isBlank() && matchedProcess.isBlank()) return false;
        return (matchedApp.isBlank() || matchedApp.equals(appId))
                && (matchedProcess.isBlank() || matchedProcess.equals(process));
    }

    private static String uniqueId(List<?> rules, String field, String value) {
        String prefix = "dynamic-app-" + DynamicRegistryValues.sha256(field + "\u0000" + value);
        String candidate = prefix;
        for (int suffix = 2; idUsed(rules, candidate); suffix++) candidate = prefix + "-" + suffix;
        return candidate;
    }
    private static boolean idUsed(List<?> rules, String id) {
        for (Object item : rules)
            if (item instanceof Map<?, ?> rule && id.equals(rule.get("id"))) return true;
        return false;
    }
    private static boolean bindingText(Map<String, Object> binding, String key) {
        return !string(binding.get(key)).isBlank();
    }
    private static boolean emptyStreams(Map<String, Object> binding) {
        return !(binding.get("streams") instanceof List<?> streams) || streams.isEmpty();
    }
    private static String string(Object value) { return value instanceof String text ? text : ""; }
}
