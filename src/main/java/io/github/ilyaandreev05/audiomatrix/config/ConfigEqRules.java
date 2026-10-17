package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphSubgraph;
import io.github.ilyaandreev05.audiomatrix.graph.GraphValidator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConfigEqRules {
    private ConfigEqRules() {}
    static void validateEq(Object value, String path, List<String> errors) {
        if (value == null) return;
        Map<String, Object> eq = ConfigValidationValues.object(value, path, errors);
        if (eq == null) return;
        if (!(eq.get("enabled") instanceof Boolean)) errors.add(path + ".enabled must be boolean");
        Object filtersValue = eq.get("filters");
        if (filtersValue == null) { errors.add(path + ".filters must be an array"); return; }
        if (!(filtersValue instanceof List<?> filters)) { errors.add(path + ".filters must be an array"); return; }
        Set<String> types = Set.of("peaking", "notch", "low_shelf", "high_shelf", "high_pass", "low_pass", "band_pass");
        for (int i = 0; i < filters.size(); i++) {
            if (!(filters.get(i) instanceof Map<?, ?> filter)) { errors.add(path + ".filters[" + i + "] must be an object"); continue; }
            if (!types.contains(filter.get("type"))) errors.add(path + ".filters[" + i + "].type is unsupported");
            double frequency = ConfigValidationValues.number(filter.get("frequency_hz"), -1);
            if (!(frequency > 0 && frequency <= 96000)) errors.add(path + ".filters[" + i + "].frequency_hz must be > 0 and <= 96000");
            if (filter.containsKey("gain_db")) {
                double gain = ConfigValidationValues.number(filter.get("gain_db"), Double.NaN);
                if (!Double.isFinite(gain) || gain < -24 || gain > 24) errors.add(path + ".filters[" + i + "].gain_db must be between -24 and 24");
            }
            if (filter.containsKey("q")) {
                double q = ConfigValidationValues.number(filter.get("q"), Double.NaN);
                if (!Double.isFinite(q) || q <= 0 || q > 20) errors.add(path + ".filters[" + i + "].q must be > 0 and <= 20");
            }
            if (filter.containsKey("enabled") && !(filter.get("enabled") instanceof Boolean))
                errors.add(path + ".filters[" + i + "].enabled must be boolean");
            if (filter.containsKey("channels")) {
                Object channelsValue = filter.get("channels");
                if (!(channelsValue instanceof List<?> channels) || channels.isEmpty()
                        || channels.stream().anyMatch(channel -> !(channel instanceof String name) || !Set.of("FL", "FR", "MONO").contains(name)))
                    errors.add(path + ".filters[" + i + "].channels must be a non-empty array of FL, FR, or MONO");
            }
            if (filter.containsKey("slope_db_oct")) {
                double slope = ConfigValidationValues.number(filter.get("slope_db_oct"), Double.NaN);
                if (!Double.isFinite(slope) || !Set.of(6.0, 12.0, 18.0, 24.0, 36.0, 48.0).contains(slope))
                    errors.add(path + ".filters[" + i + "].slope_db_oct must be one of 6, 12, 18, 24, 36, or 48");
                if (!"high_pass".equals(filter.get("type")) && !"low_pass".equals(filter.get("type")))
                    errors.add(path + ".filters[" + i + "].slope_db_oct is only supported for high_pass or low_pass filters");
            }
        }
    }}
