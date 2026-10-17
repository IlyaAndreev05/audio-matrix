package io.github.ilyaandreev05.audiomatrix.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfigValidatorProfileRules {
    private ConfigValidatorProfileRules() {}
    static void validate(ConfigValidationContext ctx) {
        for (int i = 0; i < ctx.profiles.size(); i++) {
            Map<String, Object> profile = ctx.profiles.get(i);
            if (!Set.of("haptic", "subwoofer", "speaker", "hybrid", "multi-output", "off").contains(profile.get("mode")))
                ctx.errors.add("profiles[" + i + "].mode is unsupported");
            for (String busId : ConfigValidationValues.stringList(profile.get("active_buses")))
                if (!ctx.busIds.contains(busId)) ctx.errors.add("profiles[" + i + "] references missing bus " + busId);
            for (String routeId : ConfigValidationValues.stringList(profile.get("active_routes")))
                if (!ctx.routeIds.contains(routeId)) ctx.errors.add("profiles[" + i + "] references missing route " + routeId);
        }
        ConfigCalibrationRules.validateCalibrations(ctx.calibrations, ctx.errors);
        ConfigGraphRules.validateGraphLibrary(ctx.config, ctx.errors);
    }
}
