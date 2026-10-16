package io.github.ilyaandreev05.audiomatrix.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ConfigValidatorTest {
    @Test void defaultConfigurationPassesValidation() {
        assertEquals(List.of(), ConfigValidator.validate(DefaultConfig.create()));
    }
    @Test void limiterSettingsAreRangeChecked() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> limiter = (Map<String, Object>) dsp.get("limiter");
        limiter.put("ceiling_db", -13.0);
        limiter.put("lookahead_ms", 11.0);
        limiter.put("release_ms", 4.0);
        List<String> errors = ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(message -> message.contains("dsp.limiter.ceiling_db")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("dsp.limiter.lookahead_ms")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("dsp.limiter.release_ms")));
    }
    @Test void volumeOutsideYamlContractIsRejected() {
        Map<String, Object> config = DefaultConfig.create();
        ((Map<String, Object>) config.get("mixer")).put("global_volume", 1.01);
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("global_volume")));
    }
    @Test void missingActiveProfileIsRejected() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("active_profile", "missing");
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("active_profile")));
    }

    @Test void calibrationSourceAndCurveFilesMustStayRelativeToTheConfigDirectory() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> calibrations = (List<Map<String, Object>>) config.get("calibrations");
        calibrations.add(DefaultConfig.map("id", "bad-measurement", "method", "accelerometer", "verified", false,
                "measurement_kind", "impulse_decay", "measurement_file", "../../outside.wav",
                "response_curve_file", "/tmp/curve.csv", "decay_curve_file", "../decay.csv", "quality_factor", Double.NaN));

        List<String> errors = ConfigValidator.validate(config);

        assertTrue(errors.stream().anyMatch(message -> message.contains("measurement_file must be a safe relative path")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("response_curve_file must be a safe relative path")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("decay_curve_file must be a safe relative path")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("quality_factor must be a finite number")));

        calibrations.clear();
        calibrations.add(DefaultConfig.map("id", "impulse-measurement", "method", "accelerometer", "verified", false,
                "measurement_kind", "impulse_decay", "measurement_file", "measurements/impulse.wav",
                "decay_curve_file", "measurements/impulse-decay.csv", "impulse_offset_ms", 500,
                "response_window_ms", 2000, "dominant_frequency_hz", 164.0, "quality_factor", 25.0,
                "rollback_filters_ref", "dsp.route_chains.previous-output"));
        assertEquals(List.of(), ConfigValidator.validate(config));

        calibrations.getFirst().put("rollback_filters_ref", "unknown-chain");
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("rollback_filters_ref must be a route-chain reference")));
    }
    @Test void deviceSelectorsAreSupportedAndNamesAreNonEmpty() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String,Object>> devices=(List<Map<String,Object>>)config.get("devices");
        devices.add(DefaultConfig.map("id","dac","name"," ","selector",DefaultConfig.map("kind","device_serial","value","serial-1"),"role","output","volume",1.0,"mute",false));
        List<String> errors=ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(message->message.contains("selector.kind is unsupported")));
        assertTrue(errors.stream().anyMatch(message->message.contains("devices[0].name")));
    }

    @Test void unknownDeviceAndBusRolesAreRejected() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "dac", "selector", DefaultConfig.map("kind", "name_equals", "value", "DAC"),
                "role", "guess-haptic", "volume", 1.0, "mute", false));
        @SuppressWarnings("unchecked") List<Map<String, Object>> buses = (List<Map<String, Object>>) config.get("buses");
        buses.getFirst().put("role", "notifications");

        List<String> errors = ConfigValidator.validate(config);

        assertTrue(errors.stream().anyMatch(message -> message.contains("devices[0].role is unsupported")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("buses[0].role is unsupported")));
    }

    @Test void routeTransitionDurationIsBounded() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String, Object> engine = (Map<String, Object>) config.get("engine");
        engine.put("transition_ms", 1001);
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("engine.transition_ms")));
        engine.put("transition_ms", 0);
        assertFalse(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("engine.transition_ms")));
    }

    @Test void routeGainAndDelayMustStayWithinSupportedRanges() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String,Object>> routes=(List<Map<String,Object>>)config.get("routes");
        routes.add(DefaultConfig.map("id", "bad-route", "from", "music", "to", "system_default", "enabled", true,
                "volume", 1.0, "gain_db", 25.0, "delay_ms", 2001.0, "priority", 1001, "fallback", "missing-output", "channel_map", "stereo"));
        List<String> errors = ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(message -> message.contains("gain_db")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("delay_ms")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("priority")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("fallback")));
    }

    @Test void routePolarityAllowsAllOrPerChannelInversionOnlyOnStereo() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String,Object>> routes=(List<Map<String,Object>>)config.get("routes");
        routes.add(DefaultConfig.map("id", "invert-route", "from", "music", "to", "system_default", "enabled", true, "polarity", "invert"));
        assertEquals(List.of(), ConfigValidator.validate(config));
        routes.getLast().put("polarity", "invert_left");
        assertEquals(List.of(), ConfigValidator.validate(config));
        routes.getLast().put("channel_map", "mono_sum");
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("channel-specific polarity requires a stereo channel map")));
    }

    @Test void surroundChannelMapsValidateAsMonoAndRejectLeftRightPolarity() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> buses = (List<Map<String, Object>>) config.get("buses");
        buses.stream().filter(bus -> "music".equals(bus.get("id"))).findFirst().orElseThrow().put("channels", "5.1");
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "lfe-haptics", "from", "music", "to", "system_default", "enabled", true,
                "channel_map", "lfe", "polarity", "invert"));
        assertEquals(List.of(), ConfigValidator.validate(config));

        routes.getLast().put("polarity", "invert_left");
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("channel-specific polarity requires a stereo channel map")));
    }

    @Test void surroundExtractionRequiresThatTheSourceBusHasThatChannel() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "missing-lfe", "from", "music", "to", "system_default", "enabled", true,
                "channel_map", "lfe"));
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("source bus channels unavailable in stereo")));
    }

    @Test void activeSummingChannelMapsRequireTheOutputLimiter() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> limiter = (Map<String, Object>) dsp.get("limiter");
        limiter.put("enabled", false);
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        @SuppressWarnings("unchecked") List<Map<String, Object>> buses = (List<Map<String, Object>>) config.get("buses");
        buses.stream().filter(bus -> "music".equals(bus.get("id"))).findFirst().orElseThrow().put("channels", "5.1");
        Map<String, Object> haptic = DefaultConfig.map("id", "haptic", "from", "music", "to", "system_default",
                "enabled", true, "channel_map", "rear_sum");
        routes.add(haptic);
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("requires the output limiter")));

        haptic.put("enabled", false);
        assertFalse(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("requires the output limiter")));
    }

    @Test void userChannelMatrixValidatesLayoutsDimensionsAndSignedCoefficients() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        Map<String, Object> matrix = DefaultConfig.map("inputs", List.of("FL", "FR"), "outputs", List.of("FL", "FR"),
                "coefficients", List.of(List.of(0.5, 0.5), List.of(0.5, -0.5)));
        Map<String, Object> route = DefaultConfig.map("id", "matrix", "from", "music", "to", "system_default",
                "enabled", true, "channel_map", "stereo", "channel_matrix", matrix);
        routes.add(route);
        assertEquals(List.of(), ConfigValidator.validate(config));

        @SuppressWarnings("unchecked") List<Map<String, Object>> buses = (List<Map<String, Object>>) config.get("buses");
        buses.stream().filter(bus -> "music".equals(bus.get("id"))).findFirst().orElseThrow().put("channels", "mono");
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("inputs must match the source bus")));
        buses.stream().filter(bus -> "music".equals(bus.get("id"))).findFirst().orElseThrow().put("channels", "stereo");

        matrix.put("coefficients", List.of(List.of(1.0), List.of(0.0)));
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("coefficient row 0")));
        matrix.put("coefficients", List.of(List.of(1.1, 0.0), List.of(0.0, 1.0)));
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("between -1 and 1")));
    }

    @Test void matrixBoostRequiresLimiterButAUnitBoundedMixDoesNot() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> limiter = (Map<String, Object>) dsp.get("limiter");
        limiter.put("enabled", false);
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "matrix-boost", "from", "music", "to", "system_default", "enabled", true,
                "channel_matrix", DefaultConfig.map("inputs", List.of("FL", "FR"), "outputs", List.of("FL"),
                        "coefficients", List.of(List.of(1.0, 1.0)))));

        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("channel_matrix requires the output limiter")));
        limiter.put("enabled", true);
        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    @Test void enabledBusRoutesCannotCreateFeedbackCycles() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String,Object>> routes=(List<Map<String,Object>>)config.get("routes");
        routes.add(DefaultConfig.map("id", "music-effects", "from", "music", "to", "effects", "enabled", true));
        routes.add(DefaultConfig.map("id", "effects-music", "from", "effects", "to", "music", "enabled", true));
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("feedback cycle")));
    }

    @Test void applicationRulesCannotPointAtMissingBuses() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String,Object>> applications=(List<Map<String,Object>>)config.get("applications");
        applications.add(DefaultConfig.map("id", "browser", "match", DefaultConfig.map("process", "browser"),
                "volume", 1.0, "mute", false, "bus", "missing-bus"));
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("applications[0].bus")));
    }

    @Test void routeEqReferenceMustResolveToAValidStoredChain() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String,Object>> routes=(List<Map<String,Object>>)config.get("routes");
        routes.add(DefaultConfig.map("id", "eq-route", "from", "music", "to", "system_default", "enabled", true,
                "filters_ref", "dsp.route_chains.effects"));
        @SuppressWarnings("unchecked") Map<String,Object> dsp=(Map<String,Object>)config.get("dsp");
        @SuppressWarnings("unchecked") Map<String,Object> chains=(Map<String,Object>)dsp.get("route_chains");
        chains.put("effects", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "peaking", "frequency_hz", 95.0, "gain_db", -1.0, "q", 1.0, "enabled", true))));
        assertEquals(List.of(), ConfigValidator.validate(config));

        chains.clear();
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("filters_ref references a missing route chain")));
    }

    @Test void busEqReferenceMustResolveToAValidStoredChain() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String,Object>> buses=(List<Map<String,Object>>)config.get("buses");
        buses.getFirst().put("filters_ref", "dsp.route_chains.music_eq");
        @SuppressWarnings("unchecked") Map<String,Object> dsp=(Map<String,Object>)config.get("dsp");
        @SuppressWarnings("unchecked") Map<String,Object> chains=(Map<String,Object>)dsp.get("route_chains");
        chains.put("music_eq", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "low_pass", "frequency_hz", 120.0, "enabled", true))));
        assertEquals(List.of(), ConfigValidator.validate(config));
        chains.clear();
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("buses[0].filters_ref references a missing route chain")));
    }

    @Test void applicationEqReferenceMustResolveToAValidStoredChain() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> applications = (List<Map<String, Object>>) config.get("applications");
        applications.add(DefaultConfig.map("id", "browser", "match", DefaultConfig.map("process", "browser"),
                "volume", 1.0, "mute", false, "bus", "main", "filters_ref", "dsp.route_chains.browser_eq"));
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> chains = (Map<String, Object>) dsp.get("route_chains");
        chains.put("browser_eq", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "notch", "frequency_hz", 80.0, "gain_db", -2.0, "q", 2.0, "enabled", true))));
        assertEquals(List.of(), ConfigValidator.validate(config));

        chains.clear();
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("applications[0].filters_ref references a missing route chain")));
    }

    @Test void physicalOutputEqReferenceMustResolveToAValidStoredChain() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> devices = (List<Map<String, Object>>) config.get("devices");
        devices.add(DefaultConfig.map("id", "dac", "selector", DefaultConfig.map("kind", "name_equals", "value", "test-dac"),
                "role", "output", "volume", 1.0, "mute", false, "filters_ref", "dsp.route_chains.dac_eq"));
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> chains = (Map<String, Object>) dsp.get("route_chains");
        chains.put("dac_eq", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "high_pass", "frequency_hz", 30.0, "enabled", true))));
        assertEquals(List.of(), ConfigValidator.validate(config));

        chains.clear();
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("devices[0].filters_ref references a missing route chain")));
    }

    @Test void filterSlopeMustMatchTheYamlEnum() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String,Object> dsp=(Map<String,Object>)config.get("dsp");
        @SuppressWarnings("unchecked") Map<String,Object> chains=(Map<String,Object>)dsp.get("route_chains");
        chains.put("bass", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "low_pass", "frequency_hz", 120.0, "slope_db_oct", 13, "enabled", true))));
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("slope_db_oct")));
    }

    @Test void filterSlopeIsOnlyValidForHighPassAndLowPass() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String,Object> dsp=(Map<String,Object>)config.get("dsp");
        @SuppressWarnings("unchecked") Map<String,Object> chains=(Map<String,Object>)dsp.get("route_chains");
        chains.put("notch", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "notch", "frequency_hz", 120.0, "slope_db_oct", 24, "enabled", true))));
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("only supported for high_pass or low_pass")));
    }

    @Test void channelScopedEqMustFitEveryRouteUsingItsBusOrRouteChain() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) config.get("routes");
        routes.add(DefaultConfig.map("id", "mono-out", "from", "music", "to", "system_default", "enabled", true,
                "channel_map", "mono_sum", "filters_ref", "dsp.route_chains.left-only"));
        @SuppressWarnings("unchecked") Map<String, Object> dsp = (Map<String, Object>) config.get("dsp");
        @SuppressWarnings("unchecked") Map<String, Object> chains = (Map<String, Object>) dsp.get("route_chains");
        chains.put("left-only", DefaultConfig.map("enabled", true, "filters", List.of(
                DefaultConfig.map("type", "notch", "frequency_hz", 80.0, "channels", List.of("FL")))));
        assertTrue(ConfigValidator.validate(config).stream().anyMatch(message -> message.contains("channel-scoped filter that is unavailable on MONO")));
    }

    @Test void unverifiedManualListeningObservationIsAcceptedAsACalibrationRecord() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String,Object>> calibrations=(List<Map<String,Object>>)config.get("calibrations");
        calibrations.add(DefaultConfig.map("id", "manual-check-1", "device", "family17h", "method", "manual",
                "channel", null, "measurement_file", null, "filters_ref", null, "verified", false,
                "frequency_hz", 80.0, "phase", "before", "notes", "light buzz"));
        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    @Test void calibrationSensorSourceMetadataMustBeStrings() {
        Map<String, Object> config = DefaultConfig.create();
        @SuppressWarnings("unchecked") List<Map<String,Object>> calibrations=(List<Map<String,Object>>)config.get("calibrations");
        calibrations.add(DefaultConfig.map("id", "sensor-source-check", "device", "family17h", "method", "accelerometer",
                "verified", false, "sensor_source", 42, "sensor_source_description", false));
        List<String> errors = ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(message -> message.contains("sensor_source must be a string or null")));
        assertTrue(errors.stream().anyMatch(message -> message.contains("sensor_source_description must be a string or null")));
    }
}
