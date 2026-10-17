package io.github.ilyaandreev05.audiomatrix.config;

import java.util.List;
import java.util.Map;

public final class ConfigValidator {
    private ConfigValidator() {}
    public static List<String> validate(Map<String, Object> config) {
        return ConfigValidationPipeline.validate(config);
    }
}
