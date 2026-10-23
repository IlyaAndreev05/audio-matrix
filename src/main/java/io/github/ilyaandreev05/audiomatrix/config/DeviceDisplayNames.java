package io.github.ilyaandreev05.audiomatrix.config;

import java.util.Map;
import java.util.regex.Pattern;

/** Display labels only: the existing device.name remains the sole persisted user alias. */
public final class DeviceDisplayNames {
    private DeviceDisplayNames() {}
    private static final String[] LABEL_FIELDS = {"description", "product_name", "model"};
    private static final Pattern OPAQUE_ID = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f-]{27,}|[0-9a-f]{32,}");
    private static final Pattern SYSTEM_INPUT_LABEL = Pattern.compile("(?i)\\bSystem input\\b");

    public static String displayName(Map<String, Object> config, String deviceId, String detectedName) {
        String id = deviceId == null ? "" : deviceId;
        if (id.startsWith("device:")) id = id.substring("device:".length());
        if (config != null && config.get("devices") instanceof Iterable<?> devices) {
            for (Object item : devices) {
                if (!(item instanceof Map<?, ?> device)) continue;
                if (!id.equals(device.get("id")) && !(device.get("identity") instanceof Map<?,?> identity
                        && id.equals(identity.get("fingerprint")))) continue;
                return label(device, detectedName);
            }
        }
        return label(Map.of(), detectedName);
    }

    public static String displayName(Map<String, Object> device, String detectedName) {
        return label(device == null ? Map.of() : device, detectedName);
    }

    /** Localizes generic device categories and refuses to display internal/opaque identifiers. */
    public static String displayLabel(String value, String fallback) {
        if (!human(value)) return fallback == null ? "" : fallback;
        return localize(value.trim());
    }

    private static String label(Map<?, ?> device, String detectedName) {
        Object alias = device.get("name");
        if (alias instanceof String name && human(name)) return localize(name.trim());
        if (human(detectedName)) return localize(detectedName.trim());
        for (String field : LABEL_FIELDS) {
            Object value = device.get(field);
            if (value instanceof String name && human(name)) return localize(name.trim());
        }
        if (device.get("selector") instanceof Map<?, ?> selector
                && "description_contains".equals(selector.get("kind"))
                && selector.get("value") instanceof String value && human(value)) return localize(value.trim());
        return "Выходное устройство";
    }

    private static boolean human(String value) {
        if (value == null) return false;
        String candidate = value.trim();
        if (candidate.isEmpty()) return false;
        return !candidate.startsWith("device:") && !candidate.startsWith("device-")
                && !candidate.startsWith("alsa_output.") && !candidate.startsWith("alsa_input.")
                && !candidate.startsWith("bluez_output.") && !candidate.startsWith("bluez_input.")
                && !candidate.startsWith("audiomatrix_") && !candidate.startsWith("audio-matrix-")
                && !OPAQUE_ID.matcher(candidate).matches();
    }

    private static String localize(String value) {
        String localized = value.replace("Built-in Audio", "Встроенное аудио")
                .replace("Analog Stereo", "Аналоговый стереовыход")
                .replace("Digital Stereo", "Цифровой стереовыход")
                .replace("Headphones", "Наушники")
                .replace("Speakers", "Колонки")
                .replace("Line Out", "Линейный выход");
        return SYSTEM_INPUT_LABEL.matcher(localized).replaceAll("Системный звук");
    }
}
