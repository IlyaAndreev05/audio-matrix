package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Renders a private PipeWire client config for one owned EQ and polarity graph. */
final class PipeWireFilterChainConfig {
    private record GraphNode(String name, String definition) {}

    private static final List<Integer> RAW_FILTER_SAMPLE_RATES = List.of(
            8000, 11025, 16000, 22050, 32000, 44100, 48000, 64000, 88200, 96000, 128000,
            176400, 192000, 352800, 384000);

    private PipeWireFilterChainConfig() {}

    static String render(String description, String inputNodeName, String outputNodeName,
                         List<String> positions, List<Map<String, Object>> filters) throws IOException {
        return render(description, inputNodeName, outputNodeName, positions, filters, false);
    }

    static String render(String description, String inputNodeName, String outputNodeName,
                         List<String> positions, List<Map<String, Object>> filters, boolean allowPositiveGain) throws IOException {
        if (positions == null || positions.isEmpty()) throw new IOException("A filter chain needs at least one channel");
        List<Map<String, Object>> active = filters.stream().filter(filter -> !Boolean.FALSE.equals(filter.get("enabled"))).toList();
        List<Map<String, Object>> limiters = active.stream().filter(filter -> "limiter".equals(filter.get("type"))).toList();
        if (limiters.size() > 1) throw new IOException("A filter chain can contain only one peak limiter");
        boolean limiterEnabled = !limiters.isEmpty();
        if (limiterEnabled && positions.size() > 8) throw new IOException("The Audio Matrix limiter supports up to 8 channels");
        List<Map<String, Object>> equalizers = active.stream().filter(filter -> !"limiter".equals(filter.get("type"))).toList();
        if (limiterEnabled && !equalizers.isEmpty())
            throw new IOException("The linked peak limiter must be placed in its own post-mix filter chain");
        if (active.isEmpty()) throw new IOException("A filter chain needs at least one enabled filter");

        validateFilters(equalizers, positions);
        StringBuilder nodes = new StringBuilder();
        List<String> graphInputs = new ArrayList<>(), graphOutputs = new ArrayList<>(), laneOutputs = new ArrayList<>();
        List<String> links = new ArrayList<>();
        if (!limiterEnabled) for (String position : positions) {
            List<Integer> lane = new ArrayList<>();
            for (int i = 0; i < equalizers.size(); i++) if (appliesTo(equalizers.get(i), position)) lane.add(i);
            if (lane.isEmpty()) {
                String identity = "passthrough_" + channelToken(position);
                appendNode(nodes, "{ type = builtin name = " + identity + " label = linear control = { \"Mult\" = 1 \"Add\" = 0 } }");
                graphInputs.add(identity + ":In"); laneOutputs.add(identity + ":Out");
            } else {
                List<String> names = new ArrayList<>();
                for (int filterIndex : lane) {
                    Map<String, Object> filter = equalizers.get(filterIndex);
                    for (GraphNode graphNode : renderFilterNodes(filter, filterIndex, position, allowPositiveGain)) {
                        names.add(graphNode.name());
                        appendNode(nodes, graphNode.definition());
                    }
                }
                graphInputs.add(names.getFirst() + ":In");
                laneOutputs.add(names.getLast() + ":Out");
                for (int i = 0; i + 1 < names.size(); i++)
                    links.add("          { output = \"" + names.get(i) + ":Out\" input = \"" + names.get(i + 1) + ":In\" }");
            }
        }
        if (limiterEnabled) {
            Map<String, Object> limiter = limiters.getFirst();
            Object scoped = limiter.get("channels");
            if (scoped != null) throw new IOException("The physical output limiter must cover every channel");
            double ceiling = bounded(limiter.get("ceiling_db"), -1.0, -12.0, 0.0, "limiter ceiling");
            double lookahead = bounded(limiter.get("lookahead_ms"), 5.0, 1.0, 10.0, "limiter lookahead");
            double release = bounded(limiter.get("release_ms"), 50.0, 5.0, 500.0, "limiter release");
            appendNode(nodes, "{ type = ladspa name = audiomatrix_limiter plugin = libaudiomatrix_limiter label = audiomatrix_limiter control = { \"Ceiling (dBFS)\" = "
                    + decimal(ceiling) + " \"Lookahead (ms)\" = " + decimal(lookahead)
                    + " \"Release (ms)\" = " + decimal(release) + " } }");
            for (int i = 0; i < positions.size(); i++) {
                graphInputs.add("audiomatrix_limiter:Input " + (i + 1));
                graphOutputs.add("audiomatrix_limiter:Output " + (i + 1));
            }
        } else graphOutputs.addAll(laneOutputs);
        String linksBlock = links.isEmpty() ? "" : "links = [\n" + String.join("\n", links) + "\n        ]";
        String inputs = "inputs = [ " + String.join(" ", graphInputs.stream().map(PipeWireFilterChainConfig::quote).toList()) + " ]";
        String outputs = "outputs = [ " + String.join(" ", graphOutputs.stream().map(PipeWireFilterChainConfig::quote).toList()) + " ]";
        String channelPositions = "[ " + String.join(" ", positions) + " ]";
        int channels = positions.size();
        return """
                context.properties = { log.level = 1 }
                context.spa-libs = {
                  audio.convert.* = audioconvert/libspa-audioconvert
                  support.* = support/libspa-support
                }
                context.modules = [
                  { name = libpipewire-module-rt args = { } flags = [ ifexists nofail ] }
                  { name = libpipewire-module-protocol-native }
                  { name = libpipewire-module-client-node }
                  { name = libpipewire-module-adapter }
                  { name = libpipewire-module-filter-chain args = {
                      node.description = "%s"
                      media.name = "%s"
                      filter.graph = {
                        nodes = [
                %s
                        ]
                        %s
                        %s
                        %s
                      }
                      capture.props = { node.name = %s media.class = Audio/Sink node.autoconnect = false audio.channels = %d audio.position = %s }
                      playback.props = { node.name = %s node.passive = true node.autoconnect = false audio.channels = %d audio.position = %s }
                    }
                  }
                ]
                """.formatted(escape(description), escape(description), nodes, inputs, outputs, linksBlock, inputNodeName,
                channels, channelPositions, outputNodeName, channels, channelPositions);
    }

    static String renderMatrix(String description, String inputNodeName, String outputNodeName,
                               ChannelMatrix matrix) throws IOException {
        return PipeWireChannelMatrixConfig.render(description, inputNodeName, outputNodeName, matrix);
    }

    private static void validateFilters(List<Map<String, Object>> filters, List<String> positions) throws IOException {
        for (Map<String, Object> filter : filters) {
            Object channels = filter.get("channels");
            if (channels == null) continue;
            if (!(channels instanceof List<?> list) || list.isEmpty()) throw new IOException("Filter channels must be a non-empty array");
            for (Object channel : list) if (!(channel instanceof String name) || !positions.contains(name))
                throw new IOException("Filter channel does not exist in this route: " + channel);
        }
    }

    private static boolean appliesTo(Map<String, Object> filter, String position) {
        Object channels = filter.get("channels");
        return channels == null || (channels instanceof List<?> list && list.contains(position));
    }

    private static List<GraphNode> renderFilterNodes(Map<String, Object> filter, int index, String position,
                                                     boolean allowPositiveGain) throws IOException {
        Object type = filter.get("type");
        if (!(type instanceof String filterType) || !Set.of("high_pass", "low_pass").contains(filterType)) {
            String name = nodeName(filter, index, position);
            return List.of(new GraphNode(name, renderNode(filter, index, position, allowPositiveGain, name)));
        }

        double slope = number(filter.get("slope_db_oct"), 12.0);
        if (!Set.of(6.0, 12.0, 18.0, 24.0, 36.0, 48.0).contains(slope))
            throw new IOException("Filter slope must be 6, 12, 18, 24, 36, or 48 dB/oct");
        if (slope == 12.0) {
            String name = nodeName(filter, index, position);
            return List.of(new GraphNode(name, renderNode(filter, index, position, allowPositiveGain, name)));
        }

        int order = (int) (slope / 6.0);
        String baseName = nodeName(filter, index, position);
        List<GraphNode> stages = new ArrayList<>();
        if (order % 2 != 0) {
            String name = baseName + "_stage1";
            stages.add(new GraphNode(name, renderRawFirstOrderNode(filter, name)));
        }
        int biquadCount = order / 2;
        for (int section = 0; section < biquadCount; section++) {
            String name = baseName + "_stage" + (stages.size() + 1);
            double q = butterworthSectionQ(order, section);
            Map<String, Object> stage = new java.util.LinkedHashMap<>(filter);
            stage.put("q", q);
            stage.remove("slope_db_oct");
            stages.add(new GraphNode(name, renderNode(stage, index, position, allowPositiveGain, name)));
        }
        return List.copyOf(stages);
    }

    private static double butterworthSectionQ(int order, int section) {
        // The pole-pair Q values factor an Nth-order Butterworth low/high-pass into biquads.
        double angle = order % 2 == 0
                ? (2.0 * section + 1.0) * Math.PI / (2.0 * order)
                : (section + 1.0) * Math.PI / order;
        return 1.0 / (2.0 * Math.cos(angle));
    }

    private static String renderRawFirstOrderNode(Map<String, Object> filter, String name) throws IOException {
        double frequency = number(filter.get("frequency_hz"), Double.NaN);
        if (!Double.isFinite(frequency) || frequency <= 0)
            throw new IOException("Filter frequency must be finite and positive");
        String type = String.valueOf(filter.get("type"));
        List<String> coefficients = new ArrayList<>();
        for (int sampleRate : RAW_FILTER_SAMPLE_RATES) {
            if (frequency >= sampleRate / 2.0) continue;
            // Bilinear transform of a first-order Butterworth pole for PipeWire's bq_raw node.
            double k = Math.tan(Math.PI * frequency / sampleRate);
            double denominator = 1.0 + k;
            double b0 = "low_pass".equals(type) ? k / denominator : 1.0 / denominator;
            double b1 = "low_pass".equals(type) ? b0 : -b0;
            double a1 = (k - 1.0) / denominator;
            coefficients.add("{ rate = " + sampleRate + " b0 = " + decimal(b0) + " b1 = " + decimal(b1)
                    + " b2 = 0 a0 = 1 a1 = " + decimal(a1) + " a2 = 0 }");
        }
        if (coefficients.isEmpty()) throw new IOException("Filter frequency exceeds the supported sample-rate table");
        return "{ type = builtin name = " + name + " label = bq_raw config = { coefficients = [ "
                + String.join(" ", coefficients) + " ] } }";
    }

    private static String renderNode(Map<String, Object> filter, int index, String position,
                                     boolean allowPositiveGain, String name) throws IOException {
        if ("phase_invert".equals(filter.get("type")))
            return "{ type = builtin name = " + name + " label = invert }";
        String label = label(filter.get("type"));
        double frequency = number(filter.get("frequency_hz"), Double.NaN);
        boolean passFilter = "high_pass".equals(filter.get("type")) || "low_pass".equals(filter.get("type"));
        double defaultQ = passFilter ? 0.70710678 : 1.0;
        double q = number(filter.get("q"), defaultQ);
        double gain = number(filter.get("gain_db"), 0.0);
        if (!Double.isFinite(frequency) || frequency <= 0) throw new IOException("Filter frequency must be finite and positive");
        if (!Double.isFinite(q) || q <= 0) throw new IOException("Filter Q must be finite and positive");
        if (!Double.isFinite(gain) || (gain > 0 && !allowPositiveGain)) throw new IOException("Positive EQ gain requires an active limiter");
        return "{ type = builtin name = " + name + " label = " + label
                + " control = { \"Freq\" = " + decimal(frequency)
                + " \"Q\" = " + decimal(q)
                + " \"Gain\" = " + decimal(gain) + " } }";
    }

    private static void appendNode(StringBuilder nodes, String node) {
        if (!nodes.isEmpty()) nodes.append('\n');
        nodes.append("          ").append(node);
    }

    private static String label(Object type) throws IOException {
        return switch (String.valueOf(type)) {
            case "peaking" -> "bq_peaking";
            case "notch" -> "bq_notch";
            case "low_shelf" -> "bq_lowshelf";
            case "high_shelf" -> "bq_highshelf";
            case "high_pass" -> "bq_highpass";
            case "low_pass" -> "bq_lowpass";
            case "band_pass" -> "bq_bandpass";
            default -> throw new IOException("Unsupported PipeWire filter type: " + type);
        };
    }

    private static String nodeName(Map<String, Object> filter, int index, String position) {
        String prefix = "phase_invert".equals(filter.get("type")) ? "phase_invert" : "eq";
        return prefix + index + "_" + channelToken(position);
    }

    private static String channelToken(String position) { return position.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_"); }
    private static String positionArg(List<String> positions) { return "[ " + String.join(" ", positions) + " ]"; }
    private static String quote(String value) { return "\"" + escape(value) + "\""; }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static String decimal(double value) { return String.format(java.util.Locale.ROOT, "%.8f", value); }
    private static double number(Object value, double fallback) { return value instanceof Number n ? n.doubleValue() : fallback; }
    private static double bounded(Object value, double fallback, double minimum, double maximum, String label) throws IOException {
        double result = number(value, fallback);
        if (!Double.isFinite(result) || result < minimum || result > maximum)
            throw new IOException(label + " must be between " + minimum + " and " + maximum);
        return result;
    }
}
