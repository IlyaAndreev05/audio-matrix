package io.github.ilyaandreev05.audiomatrix.config;

import java.util.List;
import java.util.Map;

final class ConfigValidationPipeline {
    private ConfigValidationPipeline() {}
    static List<String> validate(Map<String, Object> config) {
        ConfigValidationContext context = new ConfigValidationContext(config);
        ConfigValidatorBaseRules.validate(context);
        ConfigValidatorRouteRules.validate(context);
        ConfigValidatorDspRules.validate(context);
        ConfigValidatorProfileRules.validate(context);
        return context.errors;
    }
}
