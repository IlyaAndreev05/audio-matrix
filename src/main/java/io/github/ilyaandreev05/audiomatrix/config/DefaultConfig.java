package io.github.ilyaandreev05.audiomatrix.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DefaultConfig {
    public static final int SCHEMA_VERSION = 8;
    private DefaultConfig() {}

    public static Map<String, Object> create() {
        Map<String, Object> root = map();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("route_groups", new ArrayList<>());
        root.put("route_library", new ArrayList<>());
        root.put("saved_copies", new ArrayList<>());
        root.put("graph_ui_layout", map());
        root.put("graph_ui_matrix_views", map());
        root.put("graph_ui_scale", map());
        root.put("graph_ui_subgraphs", map());
        root.put("quick_controls", new ArrayList<>());
        root.put("active_route", "");
        root.put("app", "audio-matrix");
        root.put("active_profile", "monitor");
        root.put("ui", map("language", "ru", "theme", "system", "start_minimized", false));
        root.put("mixer", map("global_volume", 0.8, "global_mute", false,
                "global_eq", map("enabled", false, "filters", new ArrayList<>())));
        root.put("quick_settings", QuickSettingsConfig.defaults());
        root.put("engine", map("backend", "pipewire", "apply_on_start", false,
                "restore_owned_nodes_on_exit", true, "device_loss_policy", "mute", "transition_ms", 120));
        root.put("devices", new ArrayList<>());
        root.put("applications", new ArrayList<>());
        root.put("buses", List.of(
                map("id", "main", "name", "Main", "role", "general", "channels", "stereo", "volume", 1.0, "mute", false),
                map("id", "music", "name", "Music", "role", "music", "channels", "stereo", "volume", 1.0, "mute", false),
                map("id", "effects", "name", "Effects", "role", "effects", "channels", "stereo", "volume", 0.8, "mute", false),
                map("id", "subwoofer", "name", "Subwoofer", "role", "subwoofer", "channels", "mono", "volume", 0.65, "mute", false),
                map("id", "aux", "name", "Aux", "role", "general", "channels", "stereo", "volume", 1.0, "mute", false)));
        root.put("routes", new ArrayList<>());
        root.put("dsp", map("order", List.of("stream", "bus", "route", "physical_output", "limiter"),
                "global_eq", map("enabled", false, "filters", new ArrayList<>()),
                "devices", map(), "route_chains", map(),
                "limiter", map("enabled", true, "ceiling_db", -1.0, "lookahead_ms", 5.0, "release_ms", 50.0)));
        root.put("profiles", new ArrayList<>());
        OctineProfileConfig.install(root);
        root.put("active_route", "octine-monitor");
        root.put("calibrations", new ArrayList<>());
        root.put("export", map("include_measurements", true, "include_audio_assets", false));
        return root;
    }

    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
