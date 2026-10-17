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

final class ConfigCalibrationRules {
    private ConfigCalibrationRules() {}
    static void validateCalibrations(List<Map<String, Object>> calibrations, List<String> errors) {
        for (int i = 0; i < calibrations.size(); i++) {
            Map<String, Object> calibration = calibrations.get(i);
            if (!Set.of("manual", "microphone", "accelerometer", "combined").contains(calibration.get("method")))
                errors.add("calibrations[" + i + "].method is unsupported");
            if (!(calibration.get("verified") instanceof Boolean)) errors.add("calibrations[" + i + "].verified must be boolean");
            Object measurementKind = calibration.get("measurement_kind");
            if (measurementKind != null && !(measurementKind instanceof String))
                errors.add("calibrations[" + i + "].measurement_kind must be a string or null");
            else if (measurementKind instanceof String kind && !"impulse_decay".equals(kind))
                errors.add("calibrations[" + i + "].measurement_kind is unsupported");
            Object rollbackRef = calibration.get("rollback_filters_ref");
            if (rollbackRef != null && (!(rollbackRef instanceof String reference)
                    || !reference.startsWith("dsp.route_chains.") || reference.length() <= "dsp.route_chains.".length()))
                errors.add("calibrations[" + i + "].rollback_filters_ref must be a route-chain reference or null");
            for (String field : Set.of("sensor_source", "sensor_source_description")) {
                Object source = calibration.get(field);
                if (source != null && !(source instanceof String)) errors.add("calibrations[" + i + "]." + field + " must be a string or null");
            }
            for (String field : Set.of("measurement_file", "response_curve_file", "decay_curve_file")) {
                Object path = calibration.get(field);
                if (path != null && (!(path instanceof String text) || !relativeMeasurementPath(text)))
                    errors.add("calibrations[" + i + "]." + field + " must be a safe relative path or null");
            }
            for (String field : Set.of("impulse_offset_ms", "response_window_ms", "impulse_peak_offset_ms", "dominant_frequency_hz",
                    "quality_factor", "decay_t20_ms", "decay_t60_projected_ms", "noise_rms_pcm")) {
                Object value = calibration.get(field);
                if (value != null && (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())))
                    errors.add("calibrations[" + i + "]." + field + " must be a finite number or null");
            }
            calibrationRange(calibration, "impulse_offset_ms", 20, 60_000, i, errors);
            calibrationRange(calibration, "response_window_ms", 200, 10_000, i, errors);
            calibrationRange(calibration, "impulse_peak_offset_ms", 0, 60_000, i, errors);
            calibrationRange(calibration, "dominant_frequency_hz", 20, 1000, i, errors);
            calibrationRange(calibration, "quality_factor", 0, 1_000_000, i, errors);
            calibrationRange(calibration, "decay_t20_ms", 0, 600_000, i, errors);
            calibrationRange(calibration, "decay_t60_projected_ms", 0, 600_000, i, errors);
            calibrationRange(calibration, "noise_rms_pcm", 0, 1, i, errors);
        }
    }
    static void calibrationRange(Map<String, Object> calibration, String field, double minimum, double maximum,
                                         int index, List<String> errors) {
        Object value = calibration.get(field);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) return;
        double numeric = number.doubleValue();
        if (numeric < minimum || numeric > maximum || (minimum == 0 && numeric == 0
                && Set.of("quality_factor", "decay_t20_ms", "decay_t60_projected_ms").contains(field)))
            errors.add("calibrations[" + index + "]." + field + " is outside the supported range");
    }
    static boolean relativeMeasurementPath(String value) {
        if (value.isBlank() || value.contains("\\") || value.contains(":") || value.startsWith("/")) return false;
        try {
            Path path = Path.of(value).normalize();
            return !path.isAbsolute() && !path.startsWith("..") && path.toString().equals(value);
        } catch (RuntimeException invalid) { return false; }
    }}
