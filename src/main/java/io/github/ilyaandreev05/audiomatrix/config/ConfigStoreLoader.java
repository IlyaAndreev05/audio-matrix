package io.github.ilyaandreev05.audiomatrix.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses, migrates and validates a configuration document before it may be published. */
final class ConfigStoreLoader {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private ConfigStoreLoader() {}

    /** A parsed document together with the flag telling whether it was migrated on the way in. */
    record Prepared(Map<String, Object> config, boolean upgraded) {}

    static Prepared prepare(ObjectMapper mapper, byte[] bytes) throws ConfigStore.ConfigValidationException {
        Map<String, Object> parsed;
        try { parsed = mapper.readValue(bytes, MAP_TYPE); }
        catch (Exception e) {
            throw new ConfigStore.ConfigValidationException(List.of("Configuration could not be parsed: " + e.getMessage()));
        }
        boolean upgraded = parsed.get("schema_version") instanceof Number version
                && version.intValue() < DefaultConfig.SCHEMA_VERSION;
        if (upgraded) {
            try { ConfigStore.upgradeConfig(parsed); }
            catch (RuntimeException invalid) {
                throw new ConfigStore.ConfigValidationException(List.of("Configuration migration failed: " + invalid.getMessage()));
            }
        }
        List<String> errors = ConfigValidator.validate(parsed);
        if (!errors.isEmpty()) throw new ConfigStore.ConfigValidationException(errors);
        return new Prepared(parsed, upgraded);
    }
}
