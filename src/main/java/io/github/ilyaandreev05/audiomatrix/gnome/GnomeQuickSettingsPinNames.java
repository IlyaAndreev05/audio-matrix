package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.config.DeviceDisplayNames;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import java.util.List;
import java.util.Map;

/** User-visible fallback names for generated Quick Settings pins. */
final class GnomeQuickSettingsPinNames {
    private GnomeQuickSettingsPinNames() {}

    static boolean hasDisplayName(Object value) {
        return !displayLabel(value).isBlank();
    }

    static String displayLabel(Object value) {
        return value instanceof String text ? DeviceDisplayNames.displayLabel(text, "") : "";
    }

    static String generatedFallback(DynamicInstanceRegistry.RuntimeInstance instance) {
        return DeviceDisplayNames.displayLabel(generatedGainName(instance), "Аудиоэлемент");
    }

    static String displayName(Map<String, Object> pin, DynamicInstanceRegistry.RuntimeInstance instance,
                              List<Map<String, Object>> applications) {
        String value = displayLabel(pin.get("display_name"));
        if (!value.isBlank()) return value;
        value = displayLabel(instance.overrides().get("display_name"));
        if (!value.isBlank()) return value;
        Map<String, Object> app = GnomeQuickSettingsGeneratedPins.applicationGroupFor(instance, applications);
        if (app != null) {
            value = displayLabel(app.get("name"));
            if (!value.isBlank()) return value;
        }
        Map<String, Object> tab = GnomeQuickSettingsGeneratedPins.browserTab(instance, applications);
        if (tab != null) {
            value = displayLabel(tab.get("title"));
            if (!value.isBlank()) return value;
        }
        return generatedFallback(instance);
    }

    private static String generatedGainName(DynamicInstanceRegistry.RuntimeInstance instance) {
        Object name = instance.runtimeBinding().get("applicationName");
        if (name instanceof String value && !value.isBlank()) return value;
        Object process = instance.runtimeBinding().get("processName");
        if (process instanceof String value && !value.isBlank()) return value;
        return instance.instanceKey();
    }
}
