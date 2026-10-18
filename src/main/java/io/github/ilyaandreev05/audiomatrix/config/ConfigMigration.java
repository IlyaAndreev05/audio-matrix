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
        if (version < DefaultConfig.SCHEMA_VERSION) upgradeConfig(parsed);
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

    @SuppressWarnings("unchecked")
    static void upgradeConfig(Map<String,Object> config) {
        int schema = config.get("schema_version") instanceof Number number ? number.intValue() : -1;
        if (schema < 2) {
            config.putIfAbsent("route_groups", new ArrayList<>());
            config.putIfAbsent("route_library", new ArrayList<>());
            config.putIfAbsent("saved_copies", new ArrayList<>());
            config.putIfAbsent("graph_ui_layout", new LinkedHashMap<>());
            config.putIfAbsent("graph_ui_scale", new LinkedHashMap<>());
            config.putIfAbsent("graph_ui_subgraphs", new LinkedHashMap<>());
            config.putIfAbsent("quick_controls", new ArrayList<>());
            config.putIfAbsent("active_route", "");
            schema = 2;
        }
        if (schema < 3) {
            List<Map<String,Object>> profiles = config.get("profiles") instanceof List<?> list
                    ? (List<Map<String,Object>>) list : new ArrayList<>();
            if (!(config.get("profiles") instanceof List<?>)) config.put("profiles", profiles);
            String activeProfile = String.valueOf(config.getOrDefault("active_profile", ""));
            boolean hasActiveProfile=false;
            for(Map<String,Object> profile:profiles)if(activeProfile.equals(profile.get("id"))){hasActiveProfile=true;break;}
            if (!hasActiveProfile) {
                activeProfile = profiles.isEmpty() ? "desk" : String.valueOf(profiles.getFirst().get("id"));
                if (profiles.isEmpty()) profiles.add(DefaultConfig.map("id", activeProfile,
                        "name", "Рабочий стол", "mode", "hybrid", "default_output", "system_default"));
                config.put("active_profile", activeProfile);
            }
            List<Map<String,Object>> groups = config.get("route_groups") instanceof List<?> list
                    ? (List<Map<String,Object>>) list : new ArrayList<>();
            if (!(config.get("route_groups") instanceof List<?>)) config.put("route_groups", groups);
            List<Map<String,Object>> routes = config.get("route_library") instanceof List<?> list
                    ? (List<Map<String,Object>>) list : new ArrayList<>();
            if (!(config.get("route_library") instanceof List<?>)) config.put("route_library", routes);
            Map<String,String> groupProfiles = new LinkedHashMap<>();
            for (Map<String,Object> group : groups) {
                group.putIfAbsent("profile_id", activeProfile);
                groupProfiles.put(String.valueOf(group.get("id")), String.valueOf(group.get("profile_id")));
            }
            for (Map<String,Object> route : routes) {
                String groupId = String.valueOf(route.getOrDefault("group_id", ""));
                route.putIfAbsent("profile_id", groupProfiles.getOrDefault(groupId, activeProfile));
            }
            String activeRoute = String.valueOf(config.getOrDefault("active_route", ""));
            for (Map<String,Object> profile : profiles) {
                String profileId = String.valueOf(profile.get("id"));
                String selected = "";
                if (profileId.equals(activeProfile) && !activeRoute.isBlank()
                        && routes.stream().anyMatch(route -> activeRoute.equals(route.get("id")) && profileId.equals(route.get("profile_id")))) {
                    selected = activeRoute;
                } else {
                    selected = routes.stream().filter(route -> profileId.equals(route.get("profile_id")))
                            .map(route -> String.valueOf(route.get("id"))).findFirst().orElse("");
                }
                profile.putIfAbsent("selected_route_id", selected);
            }
            if (config.get("saved_copies") instanceof List<?> copies) {
                for (Object raw : copies) if (raw instanceof Map<?,?> copyRaw) {
                    Map<String,Object> copy = (Map<String,Object>) copyRaw;
                    if (!copy.containsKey("profile_id") && copy.get("route") instanceof Map<?,?> routeRaw) {
                        Object profileId = routeRaw.get("profile_id");
                        copy.put("profile_id", profileId == null ? activeProfile : profileId);
                    }
                }
            }
            config.put("schema_version", 3);
        }
        if (schema < 4) {
            QuickSettingsConfig.migrate(config);
            config.put("schema_version", 4);
            schema = 4;
        }
        if (schema < 5) {
            QuickSettingsConfig.migrate(config);
            QuickSettingsConfig.migrateNodePins(config);
            config.put("schema_version", 5);
            schema = 5;
        }
        if (schema < 7) {
            config.putIfAbsent("graph_ui_matrix_views", new LinkedHashMap<>());
            GraphEndpointGainMigration.migrate(config);
            schema = 7;
        }
        if (schema < 8) {
            GraphSwitchMigration.upgrade(config);
            // Presets are explicit opt-in on upgrades: existing routes and active selections survive.
            config.putIfAbsent("octine_profiles_available", false);
            config.put("schema_version", 8);
        }
    }

}
