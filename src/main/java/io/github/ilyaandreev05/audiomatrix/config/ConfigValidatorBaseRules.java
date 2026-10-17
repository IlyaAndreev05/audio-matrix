package io.github.ilyaandreev05.audiomatrix.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfigValidatorBaseRules {
    private ConfigValidatorBaseRules() {}
    static void validate(ConfigValidationContext ctx) {
        if (ConfigValidationValues.number(ctx.config.get("schema_version"), -1) != 5) ctx.errors.add("schema_version must be 5");
        if (!"audio-matrix".equals(ctx.config.get("app"))) ctx.errors.add("app must be audio-matrix");
        ConfigValidationValues.required(ctx.config, "active_profile", String.class, ctx.errors);
        Map<String, Object> ui = ConfigValidationValues.object(ctx.config.get("ui"), "ui", ctx.errors);
        if (ui != null) {
            if (!Set.of("ru", "en").contains(ui.get("language"))) ctx.errors.add("ui.language must be ru or en");
            if (!Set.of("light", "dark", "system").contains(ui.get("theme"))) ctx.errors.add("ui.theme must be light, dark, or system");
        }
        Map<String, Object> mixer = ConfigValidationValues.object(ctx.config.get("mixer"), "mixer", ctx.errors);
        if (mixer != null) {
            ConfigValidationValues.checkVolume(mixer.get("global_volume"), "mixer.global_volume", ctx.errors);
            ConfigValidationValues.required(mixer, "global_mute", Boolean.class, ctx.errors);
            ConfigEqRules.validateEq(mixer.get("global_eq"), "mixer.global_eq", ctx.errors);
        }
        Map<String, Object> engine = ConfigValidationValues.object(ctx.config.get("engine"), "engine", ctx.errors);
        if (engine != null && !"pipewire".equals(engine.get("backend"))) ctx.errors.add("engine.backend must be pipewire");
        if (engine != null && engine.containsKey("transition_ms")) {
            double transition = ConfigValidationValues.number(engine.get("transition_ms"), Double.NaN);
            if (!Double.isFinite(transition) || transition != Math.rint(transition) || transition < 0 || transition > 1000)
                ctx.errors.add("engine.transition_ms must be an integer between 0 and 1000");
        }
        ctx.devices = ConfigValidationValues.list(ctx.config.get("devices"), "devices", ctx.errors);
        ctx.applications = ConfigValidationValues.list(ctx.config.get("applications"), "applications", ctx.errors);
        ctx.buses = ConfigValidationValues.list(ctx.config.get("buses"), "buses", ctx.errors);
        ctx.routes = ConfigValidationValues.list(ctx.config.get("routes"), "routes", ctx.errors);
        ctx.profiles = ConfigValidationValues.list(ctx.config.get("profiles"), "profiles", ctx.errors);
        ctx.calibrations = ConfigValidationValues.list(ctx.config.get("calibrations"), "calibrations", ctx.errors);
        ctx.busIds = ConfigValidationValues.ids(ctx.buses);
        ctx.profileIds = ConfigValidationValues.ids(ctx.profiles);
        ctx.deviceIds = ConfigValidationValues.deviceIds(ctx.devices);
        ctx.routeIds = ConfigValidationValues.ids(ctx.routes);
        ConfigValidationValues.checkUniqueIds(ctx.devices, "devices", ctx.errors);
        ConfigValidationValues.checkUniqueIds(ctx.applications, "applications", ctx.errors);
        ConfigValidationValues.checkUniqueIds(ctx.buses, "buses", ctx.errors);
        ConfigValidationValues.checkUniqueIds(ctx.routes, "routes", ctx.errors);
        ConfigValidationValues.checkUniqueIds(ctx.profiles, "profiles", ctx.errors);
        ConfigValidationValues.checkUniqueIds(ctx.calibrations, "calibrations", ctx.errors);
        ConfigValidationValues.validateVolumes(ctx.devices, "devices", ctx.errors);
        ConfigValidationValues.validateVolumes(ctx.applications, "applications", ctx.errors);
        ConfigValidationValues.validateVolumes(ctx.buses, "buses", ctx.errors);
        for (int i = 0; i < ctx.devices.size(); i++) {
            Map<String, Object> selector = ConfigValidationValues.object(ctx.devices.get(i).get("selector"), "devices[" + i + "].selector", ctx.errors);
            if (selector != null) {
                if (!Set.of("name_equals", "name_contains", "description_contains").contains(selector.get("kind")))
                    ctx.errors.add("devices[" + i + "].selector.kind is unsupported");
                if (!(selector.get("value") instanceof String value) || value.isBlank()) ctx.errors.add("devices[" + i + "].selector.value must be non-empty");
            }
            if (ctx.devices.get(i).containsKey("name") && (!(ctx.devices.get(i).get("name") instanceof String name) || name.isBlank()))
                ctx.errors.add("devices[" + i + "].name must be a non-empty string");
            if (ctx.devices.get(i).containsKey("role")) {
                Object role = ctx.devices.get(i).get("role");
                if (!(role instanceof String value) || !Set.of("output", "source", "input", "speaker", "speakers",
                        "subwoofer", "haptic_output", "microphone", "accelerometer").contains(value))
                    ctx.errors.add("devices[" + i + "].role is unsupported");
            }
            if (!(ctx.devices.get(i).get("mute") instanceof Boolean)) ctx.errors.add("devices[" + i + "].mute must be boolean");
        }
        for (int i = 0; i < ctx.applications.size(); i++) {
            Map<String, Object> match = ConfigValidationValues.object(ctx.applications.get(i).get("match"), "applications[" + i + "].match", ctx.errors);
            if (match != null && !(match.get("process") instanceof String) && !(match.get("app_id") instanceof String))
                ctx.errors.add("applications[" + i + "].match needs process or app_id");
            if (ctx.applications.get(i).containsKey("bus") && !ctx.busIds.contains(String.valueOf(ctx.applications.get(i).get("bus"))))
                ctx.errors.add("applications[" + i + "].bus must reference a bus id");
            if (!(ctx.applications.get(i).get("mute") instanceof Boolean)) ctx.errors.add("applications[" + i + "].mute must be boolean");
        }
        for (int i = 0; i < ctx.buses.size(); i++) {
            if (ctx.buses.get(i).containsKey("name") && (!(ctx.buses.get(i).get("name") instanceof String name) || name.isBlank()))
                ctx.errors.add("buses[" + i + "].name must be a non-empty string");
            Object channels = ctx.buses.get(i).get("channels");
            if (!(channels instanceof String value) || !Set.of("mono", "stereo", "5.1", "7.1").contains(value)) ctx.errors.add("buses[" + i + "].channels is unsupported");
            if (ctx.buses.get(i).containsKey("role")) {
                Object role = ctx.buses.get(i).get("role");
                if (!(role instanceof String value) || !Set.of("general", "music", "effects", "subwoofer").contains(value))
                    ctx.errors.add("buses[" + i + "].role is unsupported");
            }
            if (!(ctx.buses.get(i).get("mute") instanceof Boolean)) ctx.errors.add("buses[" + i + "].mute must be boolean");
        }
    }
}
