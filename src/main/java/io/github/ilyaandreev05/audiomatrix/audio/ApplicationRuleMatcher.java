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
}
