package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.List;
import java.util.Map;

/** Matches persisted application rules against PipeWire's stable application properties. */
public final class ApplicationRuleMatcher {
    private ApplicationRuleMatcher() {}

    public static Map<String, Object> find(List<Map<String, Object>> rules, AudioNode stream) {
        if (rules == null || stream == null || !"stream".equals(stream.kind())) return null;
        for (Map<String, Object> rule : rules) {
            if (!(rule.get("match") instanceof Map<?, ?> match)) continue;
            Object process = match.get("process");
            Object appId = match.get("app_id");
            boolean hasProcess = process instanceof String value && !value.isBlank();
            boolean hasAppId = appId instanceof String value && !value.isBlank();
            if (!hasProcess && !hasAppId) continue;
            if (hasProcess && !process.equals(stream.processName())) continue;
            if (hasAppId && !appId.equals(stream.applicationId())) continue;
            return rule;
        }
        return null;
    }

    /** Resolves a captured tab's parent app rule only after its opaque stream name matched exactly. */
    public static Map<String, Object> find(List<Map<String, Object>> rules, AudioNode capture,
                                           PipeWireRouteTypes.CapturedStreamBinding binding) {
        if (rules == null || capture == null || binding == null
                || binding.nodeName() == null || !binding.nodeName().equals(capture.name())) return null;
        AudioNode parent = new AudioNode(capture.id(), capture.name(), capture.description(), capture.mediaClass(),
                capture.state(), capture.applicationName(), binding.parentProcessName(),
                binding.parentApplicationId(), capture.channels(), capture.sampleRates(), capture.volume(),
                capture.muted(), capture.pulseId(), capture.identity());
        Map<String, Object> matched = find(rules, parent);
        String identity = binding.applicationRuleId() == null ? "" : binding.applicationRuleId();
        if (identity.isBlank()) return matched;

        for (Map<String, Object> rule : rules) {
            if (!identity.equals(String.valueOf(rule.getOrDefault("id", "")))) continue;
            return matchesParent(rule, binding) ? rule : null;
        }
        if (matched != null && matchesParent(matched, binding)) {
            Map<String, Object> match = castMap(matched.get("match"));
            if (identity.equals(String.valueOf(match.getOrDefault("app_id", "")))
                    || identity.equals(String.valueOf(match.getOrDefault("process", "")))) return matched;
        }
        return null;
    }

    private static boolean matchesParent(Map<String, Object> rule,
                                         PipeWireRouteTypes.CapturedStreamBinding binding) {
        Map<String, Object> match = castMap(rule.get("match"));
        String appId = binding.parentApplicationId() == null ? "" : binding.parentApplicationId();
        String process = binding.parentProcessName() == null ? "" : binding.parentProcessName();
        boolean hasAppId = match.get("app_id") instanceof String value && !value.isBlank();
        boolean hasProcess = match.get("process") instanceof String value && !value.isBlank();
        return (hasAppId || hasProcess) && (!hasAppId || appId.equals(match.get("app_id")))
                && (!hasProcess || process.equals(match.get("process")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
