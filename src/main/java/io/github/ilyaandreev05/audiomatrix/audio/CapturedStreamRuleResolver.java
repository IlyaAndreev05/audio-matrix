package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves only capture streams whose browser parent matches a live application rule identity. */
final class CapturedStreamRuleResolver {
    private CapturedStreamRuleResolver() {}

    static Map<String, Map<String, Object>> indexRules(List<Map<String, Object>> rules) {
        Map<String, Map<String, Object>> indexed = new HashMap<>();
        Set<String> duplicates = new HashSet<>();
        for (Map<String, Object> rule : rules == null ? List.<Map<String, Object>>of() : rules) {
            String id = String.valueOf(rule.getOrDefault("id", ""));
            if (id.isBlank()) continue;
            if (indexed.putIfAbsent(id, rule) != null) duplicates.add(id);
        }
        duplicates.forEach(indexed::remove);
        return indexed;
    }

    static Map<String, Object> findRule(CapturedStreamBinding binding, AudioNode captureStream,
                                        List<AudioNode> streams,
                                        Map<String, Map<String, Object>> rulesById) {
        if (binding == null || captureStream == null || !binding.nodeName().equals(captureStream.name())) return null;
        Map<String, Object> rule = rulesById.get(binding.applicationRuleId());
        if (rule == null) return null;
        boolean hasAppId = !binding.parentApplicationId().isBlank();
        boolean hasProcess = !binding.parentProcessName().isBlank();
        if (!hasAppId && !hasProcess) return null;
        for (AudioNode parent : streams) {
            if (parent == captureStream || AudioMatrixOwnedStream.matches(parent)
                    || parent.name().startsWith(CapturedStreamBinding.NODE_PREFIX)) continue;
            if (hasAppId && !binding.parentApplicationId().equals(parent.applicationId())) continue;
            if (hasProcess && !binding.parentProcessName().equals(parent.processName())) continue;
            if (ApplicationRuleMatcher.find(List.of(rule), parent) == rule) return rule;
        }
        return null;
    }
}
