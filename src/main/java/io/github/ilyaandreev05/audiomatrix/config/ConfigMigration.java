package io.github.ilyaandreev05.audiomatrix.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ConfigMigration {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    static void backupBeforeSchemaUpgrade(Path file) throws IOException {
        backupBeforeSchemaUpgrade(file, DefaultConfig.SCHEMA_VERSION);
    }

    static void backupBeforeSchemaUpgrade(Path file, int targetSchema) throws IOException {
        String base=file.getFileName()+".before-schema-"+targetSchema;
        Path backup=file.resolveSibling(base);
        for(int suffix=1;Files.exists(backup);suffix++)backup=file.resolveSibling(base+"."+suffix);
        Files.copy(file,backup);
    }

    static void migrateLegacyYaml(Path file, Path legacyFile, ObjectMapper mapper) throws IOException, ConfigStore.ConfigValidationException {
        Path legacy = legacyFile;
        if (legacy == null || Files.exists(file) || !Files.exists(legacy)) return;
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        Map<String, Object> parsed;
        try {
            parsed = yaml.readValue(Files.readAllBytes(legacy), MAP_TYPE);
        } catch (Exception error) {
            throw new ConfigStore.ConfigValidationException(List.of("Legacy YAML could not be read; it was left untouched: " + error.getMessage()));
        }
        importLegacyYamlDeviceAliases(parsed);
        int version = parsed.get("schema_version") instanceof Number number ? number.intValue() : -1;
        if (version < DefaultConfig.SCHEMA_VERSION) {
            try { upgradeConfig(parsed); }
            catch (RuntimeException invalid) {
                throw new ConfigStore.ConfigValidationException(List.of("Legacy configuration is incomplete: " + invalid.getMessage()));
            }
        }
        List<String> errors = ConfigValidator.validate(parsed);
        if (!errors.isEmpty()) throw new ConfigStore.ConfigValidationException(errors);
        Path backup = legacy.resolveSibling("config.yaml.before-json");
        if (Files.exists(backup)) throw new IOException("Legacy backup already exists; refusing to overwrite " + backup);
        Files.copy(legacy, backup);
        try {
            ConfigFileIO.atomicWrite(file, mapper.writeValueAsBytes(parsed));
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(file);
            throw failure;
        }
    }

    static void importLegacyYamlDeviceAliases(Map<String,Object> config) {
        if (!(config.get("devices") instanceof List<?> devices)) return;
        for (Object item : devices) {
            if (!(item instanceof Map<?,?> raw)) continue;
            @SuppressWarnings("unchecked") Map<String,Object> device=(Map<String,Object>)raw;
            Object alias = device.remove("alias");
            Object name = device.get("name");
            if (!(name instanceof String current) || current.isBlank()) {
                if (alias instanceof String legacyName && !legacyName.isBlank()) device.put("name", legacyName);
            }
        }
    }

    static void upgradeConfig(Map<String,Object> config) {
        int schema = config.get("schema_version") instanceof Number number ? number.intValue() : -1;
        if (schema >= DefaultConfig.SCHEMA_VERSION) return;
        if (schema < 10) {
            Map<String, Object> source = mutableObject(config);
            long legacyFields = List.of("ui", "mixer", "devices", "applications", "profiles", "buses", "routes",
                            "route_library", "route_groups", "active_route", "quick_settings")
                    .stream().filter(source::containsKey).count();
            if (legacyFields < 2) throw new IllegalArgumentException("not enough legacy settings to migrate safely");
            if (!source.containsKey("migration_archive")) {
                Map<String, Object> archive = new LinkedHashMap<>();
                archive.put("source_schema_version", schema);
                archive.put("prior_config", deepCopy(source));
                source.put("migration_archive", archive);
            }
            config.clear();
            config.putAll(source);
            DefaultConfig.create().forEach(config::putIfAbsent);
            UnifiedGraphConfig.migrate(config);
            config.put("schema_version", 10);
        }
        if (schema <= 10) {
            archiveAndRemovePresentationPins(config, schema);
            Map<String, Object> upgraded = io.github.ilyaandreev05.audiomatrix.graph.GraphCodec.upgradeSchema10Config(config);
            config.clear();
            config.putAll(upgraded);
            config.put("schema_version", DefaultConfig.SCHEMA_VERSION);
        }
    }
    private static void archiveAndRemovePresentationPins(Map<String, Object> config, int sourceSchema) {
        List<Map<String, Object>> pins = QuickSettingsConfig.pins(config);
        List<Map<String, Object>> removed = new ArrayList<>();
        pins.removeIf(pin -> {
            if (!isDefaultTechnicalPin(pin)) return false;
            removed.add(mutableObject(pin));
            return true;
        });
        for (Map<String, Object> pin : pins) {
            if (!GraphCommonVibro.VIBRO_GAIN_ID.equals(pin.get("node_id"))) continue;
            pin.put("group_id", "outputs");
        }
        if (removed.isEmpty()) return;
        Map<String, Object> archive = object(config.get("migration_archive"));
        if (archive.isEmpty()) {
            archive.put("source_schema_version", sourceSchema);
            archive.put("prior_config", deepCopy(config));
        }
        Map<String, Object> presentation = object(archive.get("presentation_archive"));
        List<Map<String, Object>> prior = maps(presentation.get("removed_quick_settings_pins"));
        List<Map<String, Object>> all = new ArrayList<>(prior);
        all.addAll(removed);
        presentation.put("removed_quick_settings_pins", all);
        archive.put("presentation_archive", presentation);
        config.put("migration_archive", archive);
    }

    private static boolean isDefaultTechnicalPin(Map<String, Object> pin) {
        String nodeId = String.valueOf(pin.getOrDefault("node_id", ""));
        String group = String.valueOf(pin.getOrDefault("group_id", ""));
        String expectedGroup = switch (nodeId) {
            case GraphCommonVibro.VIBRO_MIXER_ID, UnifiedGraphConfig.LIMITER_ID,
                    UnifiedGraphConfig.MUSIC_GAIN_ID -> "routing";
            case UnifiedGraphConfig.EVENT_SOURCE_ID -> "events";
            case GraphCommonVibro.VIBRO_OUTPUT_ID -> "outputs";
            default -> "";
        };
        if (expectedGroup.isBlank() || !expectedGroup.equals(group)
                || !"DEFAULT".equals(pin.getOrDefault("view", "DEFAULT"))) return false;
        if (nonBlank(pin.get("display_name")) || nonBlank(pin.get("icon"))
                || Boolean.TRUE.equals(pin.get("show_children"))
                || Boolean.TRUE.equals(pin.get("show_when_offline"))) return false;
        return !(pin.get("control_ids") instanceof List<?> controls) || controls.isEmpty();
    }

    private static boolean nonBlank(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static Map<String, Object> object(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map)
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), deepCopy(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(deepCopy(item)));
            return copy;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableObject(Map<String, Object> source) {
        return (Map<String, Object>) deepCopy(source);
    }

}
