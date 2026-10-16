package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;
import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeWireFilterChainConfigTest {
    @Test
    void rendersSerialStereoBiquadsAndOwnedNodes() throws Exception {
        String config = PipeWireFilterChainConfig.render("route EQ", "am_eq_input", "am_eq_output",
                List.of("FL", "FR"), List.of(
                        filter("peaking", 250, -2, 1.2),
                        filter("low_pass", 120, 0, 0.7)));

        assertTrue(config.contains("label = bq_peaking"));
        assertTrue(config.contains("label = bq_lowpass"));
        assertTrue(config.contains("output = \"eq0_fl:Out\" input = \"eq1_fl:In\""));
        assertTrue(config.contains("inputs = [ \"eq0_fl:In\" \"eq0_fr:In\" ]"));
        assertTrue(config.contains("outputs = [ \"eq1_fl:Out\" \"eq1_fr:Out\" ]"));
        assertTrue(config.contains("audio.channels = 2 audio.position = [ FL FR ]"));
        assertTrue(config.contains("node.name = am_eq_input"));
        assertTrue(config.contains("node.name = am_eq_output"));
    }

    @Test
    void rejectsPositiveEqGainUntilLimiterIsImplemented() {
        assertThrows(IOException.class, () -> PipeWireFilterChainConfig.render("route EQ", "input", "output",
                List.of("MONO"), List.of(filter("peaking", 95, 1, 1.0))));
    }

    @Test
    void singleBiquadDoesNotEmitAnEmptyLinkList() throws Exception {
        String config = PipeWireFilterChainConfig.render("route EQ", "input", "output", List.of("MONO"),
                List.of(filter("notch", 90, 0, 4.0)));

        assertTrue(config.contains("audio.channels = 1 audio.position = [ MONO ]"));
        assertTrue(!config.contains("links = ["));
    }

    @Test
    void rendersBuiltinPhaseInvertAfterTheEqNode() throws Exception {
        String config = PipeWireFilterChainConfig.render("route EQ", "input", "output", List.of("FL", "FR"), List.of(
                filter("notch", 90, -2, 4.0), Map.of("type", "phase_invert", "enabled", true)));
        assertTrue(config.contains("name = phase_invert1_fl label = invert"));
        assertTrue(config.contains("output = \"eq0_fl:Out\" input = \"phase_invert1_fl:In\""));
    }

    @Test
    void rendersLeftOnlyPhaseInvertWithRightChannelPassthrough() throws Exception {
        String config = PipeWireFilterChainConfig.render("route EQ", "input", "output", List.of("FL", "FR"), List.of(
                Map.of("type", "phase_invert", "enabled", true, "channels", List.of("FL"))));
        assertTrue(config.contains("name = phase_invert0_fl label = invert"));
        assertTrue(config.contains("name = passthrough_fr label = linear"));
        assertTrue(config.contains("inputs = [ \"phase_invert0_fl:In\" \"passthrough_fr:In\" ]"));
        assertTrue(config.contains("outputs = [ \"phase_invert0_fl:Out\" \"passthrough_fr:Out\" ]"));
        assertTrue(!config.contains("phase_invert0_fr"));
    }

    @Test
    void rendersLimiterAsOneLinkedMultichannelNode() throws Exception {
        Map<String, Object> limiter = Map.of("type", "limiter", "enabled", true,
                "ceiling_db", -1.0, "lookahead_ms", 5.0, "release_ms", 50.0);
        String config = PipeWireFilterChainConfig.render("physical output", "input", "output",
                List.of("FL", "FR"), List.of(limiter), true);

        assertTrue(config.contains("plugin = libaudiomatrix_limiter label = audiomatrix_limiter"));
        assertTrue(config.contains("\"Ceiling (dBFS)\" = -1"));
        assertTrue(config.contains("\"Lookahead (ms)\" = 5"));
        assertTrue(config.contains("\"Release (ms)\" = 50"));
        assertTrue(config.contains("inputs = [ \"audiomatrix_limiter:Input 1\" \"audiomatrix_limiter:Input 2\" ]"));
        assertTrue(config.contains("outputs = [ \"audiomatrix_limiter:Output 1\" \"audiomatrix_limiter:Output 2\" ]"));
        assertTrue(!config.contains("type = builtin name = passthrough"));
        assertTrue(!config.contains("links = ["));
    }

    @Test
    void requiresPeakLimiterToBeSeparateFromEqNodes() {
        Map<String, Object> limiter = Map.of("type", "limiter", "enabled", true,
                "ceiling_db", -1.0, "lookahead_ms", 5.0, "release_ms", 50.0);
        assertThrows(IOException.class, () -> PipeWireFilterChainConfig.render("physical output", "input", "output",
                List.of("FL", "FR"), List.of(filter("peaking", 90, 2, 1.0), limiter), true));
    }

    @Test
    void rejectsPositiveGainWhenLimiterIsNotIncludedInThatChain() {
        assertThrows(IOException.class, () -> PipeWireFilterChainConfig.render("route EQ", "input", "output",
                List.of("MONO"), List.of(filter("peaking", 95, 1, 1.0)), false));
    }

    @Test
    void rejectsFilterChannelNotPresentInRouteLayout() {
        assertThrows(IOException.class, () -> PipeWireFilterChainConfig.render("route EQ", "input", "output",
                List.of("MONO"), List.of(Map.of("type", "phase_invert", "enabled", true, "channels", List.of("FL")))));
    }

    @Test
    void rendersTheSupportedFilterSlopeOrders() throws Exception {
        for (int slope : List.of(6, 12, 18, 24, 36, 48)) {
            Map<String, Object> filter = filter("low_pass", 120, 0, 0.7071);
            filter.put("slope_db_oct", slope);
            String config = PipeWireFilterChainConfig.render("subwoofer", "input", "output",
                    List.of("MONO"), List.of(filter));
            assertTrue(config.contains("label = bq_lowpass") || config.contains("label = bq_raw"),
                    "slope " + slope + " dB/oct must render a low-pass section");
            long expectedSections = (slope / 6 + 1L) / 2L;
            long renderedSections = config.lines().filter(line -> line.contains("{ type = builtin name = ")).count();
            assertEquals(expectedSections, renderedSections, "slope " + slope + " dB/oct must render the requested order");
            assertEquals(slope % 12 == 6, config.contains("label = bq_raw"),
                    "odd-order slopes use a first-order bq_raw section");
        }

        Map<String, Object> firstOrder = filter("high_pass", 35, 0, 0.7071);
        firstOrder.put("slope_db_oct", 6);
        String raw = PipeWireFilterChainConfig.render("subwoofer protection", "input", "output",
                List.of("MONO"), List.of(firstOrder));
        assertTrue(raw.contains("label = bq_raw"));
        assertTrue(raw.contains("rate = 48000"));

        Map<String, Object> unsupported = filter("low_pass", 120, 0, 0.7071);
        unsupported.put("slope_db_oct", 30);
        assertThrows(IOException.class, () -> PipeWireFilterChainConfig.render("route EQ", "input", "output",
                List.of("MONO"), List.of(unsupported)));
    }

    @Test
    void renders24DbOctButterworthLowPassAsTwoLinkedSections() throws Exception {
        Map<String, Object> filter = filter("low_pass", 120, 0, 0.7);
        filter = new java.util.LinkedHashMap<>(filter);
        filter.put("slope_db_oct", 24);
        String config = PipeWireFilterChainConfig.render("route EQ", "input", "output", List.of("MONO"), List.of(filter));

        assertTrue(config.contains("name = eq0_mono_stage1 label = bq_lowpass control = { \"Freq\" = 120.00000000 \"Q\" = 0.54119610"));
        assertTrue(config.contains("name = eq0_mono_stage2 label = bq_lowpass control = { \"Freq\" = 120.00000000 \"Q\" = 1.30656296"));
        assertTrue(config.contains("output = \"eq0_mono_stage1:Out\" input = \"eq0_mono_stage2:In\""));
    }

    @Test
    void rendersAUserMatrixAsCopyMixerAndPolarityNodes() throws Exception {
        ChannelMatrix matrix = ChannelMatrix.parse(DefaultConfig.map(
                "inputs", List.of("FL", "FR"), "outputs", List.of("FL", "FR"),
                "coefficients", List.of(List.of(0.5, 0.5), List.of(0.0, -1.0))));

        String config = PipeWireFilterChainConfig.renderMatrix("custom route matrix", "matrix_input", "matrix_output", matrix);

        assertTrue(config.contains("name = copy_fl label = copy"));
        assertTrue(config.contains("name = matrix_fl label = mixer control = { \"Gain 1\" = 0.50000000 \"Gain 2\" = 0.50000000 }"));
        assertTrue(config.contains("name = invert_fr_fr label = invert"));
        assertTrue(config.contains("input = \"matrix_fr:In 2\""));
        assertTrue(config.contains("outputs = [ \"matrix_fl:Out\" \"matrix_fr:Out\" ]"));
        assertTrue(config.contains("capture.props = { node.name = matrix_input media.class = Audio/Sink node.autoconnect = false audio.channels = 2 audio.position = [ FL FR ] }"));
        assertTrue(config.contains("playback.props = { node.name = matrix_output node.passive = true node.autoconnect = false audio.channels = 2 audio.position = [ FL FR ] }"));
    }

    @Test
    void rejectsUnknownFilterTypesAndEmptyEnabledChains() {
        assertThrows(IOException.class, () -> PipeWireFilterChainConfig.render("route EQ", "input", "output",
                List.of("MONO"), List.of(filter("unsupported", 100, 0, 1.0))));
        assertThrows(IOException.class, () -> PipeWireFilterChainConfig.render("route EQ", "input", "output",
                List.of("MONO"), List.of(Map.of("type", "peaking", "frequency_hz", 100, "enabled", false))));
    }

    private static Map<String, Object> filter(String type, double frequency, double gain, double q) {
        return new java.util.LinkedHashMap<>(Map.of("type", type, "frequency_hz", frequency,
                "gain_db", gain, "q", q, "enabled", true));
    }
}
