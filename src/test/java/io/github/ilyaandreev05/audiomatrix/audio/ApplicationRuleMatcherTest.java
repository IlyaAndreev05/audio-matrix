package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationRuleMatcherTest {
    @Test void matchesByProcessBinaryAndRejectsOtherProcesses() {
        Map<String, Object> rule = rule(Map.of("process", "firefox"));
        assertSame(rule, ApplicationRuleMatcher.find(List.of(rule), stream("firefox", "org.mozilla.firefox")));
        assertNull(ApplicationRuleMatcher.find(List.of(rule), stream("chromium", "org.chromium.Chromium")));
    }

    @Test void matchesByDesktopAppIdAndRequiresAllProvidedSelectors() {
        Map<String, Object> appIdRule = rule(Map.of("app_id", "org.telegram.desktop"));
        assertSame(appIdRule, ApplicationRuleMatcher.find(List.of(appIdRule), stream("telegram-desktop", "org.telegram.desktop")));

        Map<String, Object> combined = rule(Map.of("process", "telegram-desktop", "app_id", "org.telegram.desktop"));
        assertSame(combined, ApplicationRuleMatcher.find(List.of(combined), stream("telegram-desktop", "org.telegram.desktop")));
        assertNull(ApplicationRuleMatcher.find(List.of(combined), stream("telegram-desktop", "org.telegram.messenger")));
    }

    @Test void chromeAndTelegramCanUseIndependentVolumeRules() {
        Map<String, Object> chrome = appRule("chrome", 0.75);
        Map<String, Object> telegram = appRule("telegram-desktop", 0.65);
        List<Map<String, Object>> rules = List.of(chrome, telegram);

        assertSame(chrome, ApplicationRuleMatcher.find(rules, stream("chrome", "")));
        assertSame(telegram, ApplicationRuleMatcher.find(rules, stream("telegram-desktop", "")));
        assertNotSame(chrome, telegram);
        assertEquals(0.75, chrome.get("volume"));
        assertEquals(0.65, telegram.get("volume"));
    }

    private static Map<String, Object> appRule(String process, double volume) {
        Map<String, Object> rule = rule(Map.of("process", process));
        rule.put("volume", volume);
        return rule;
    }

    private static Map<String, Object> rule(Map<String, Object> match) {
        Map<String, Object> rule = new LinkedHashMap<>(); rule.put("match", match); return rule;
    }
    private static AudioNode stream(String process, String appId) {
        return new AudioNode(4, "stream-4", "Test stream", "Stream/Output/Audio", "running", "Test app", process, appId, 2, List.of(48000), .5, false, 14);
    }
}
