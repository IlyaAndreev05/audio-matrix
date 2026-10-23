package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.config.DeviceDisplayNames;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;

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

    private static String generatedGainName(DynamicInstanceRegistry.RuntimeInstance instance) {
        Object name = instance.runtimeBinding().get("applicationName");
        if (name instanceof String value && !value.isBlank()) return value;
        Object process = instance.runtimeBinding().get("processName");
        if (process instanceof String value && !value.isBlank()) return value;
        return instance.instanceKey();
    }
}
