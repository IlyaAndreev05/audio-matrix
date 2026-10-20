package io.github.ilyaandreev05.audiomatrix.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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

    static void upgradeConfig(Map<String,Object> config) {
        int schema = config.get("schema_version") instanceof Number number ? number.intValue() : -1;
        if (schema >= DefaultConfig.SCHEMA_VERSION) return;
        Map<String, Object> source = mutableObject(config);
        long legacyFields = List.of("ui", "mixer", "devices", "applications", "profiles", "buses", "routes",
                        "route_library", "route_groups", "active_route", "quick_settings")
                .stream().filter(source::containsKey).count();
        if (legacyFields < 2) throw new IllegalArgumentException("not enough legacy settings to migrate safely");
        if (!config.containsKey("migration_archive")) {
            Map<String, Object> archive = new LinkedHashMap<>();
            archive.put("source_schema_version", schema);
            archive.put("prior_config", deepCopy(source));
            source.put("migration_archive", archive);
        }
        config.clear();
        config.putAll(source);
        DefaultConfig.create().forEach(config::putIfAbsent);
        UnifiedGraphConfig.migrate(config);
        config.put("schema_version", DefaultConfig.SCHEMA_VERSION);
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
