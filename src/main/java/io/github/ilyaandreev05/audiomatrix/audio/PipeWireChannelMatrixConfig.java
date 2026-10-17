package io.github.ilyaandreev05.audiomatrix.audio;

import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Renders explicit multi-channel route matrices as PipeWire filter-chain configurations. */
final class PipeWireChannelMatrixConfig {
    private PipeWireChannelMatrixConfig() {}

    static String render(String description, String inputNodeName, String outputNodeName,
                         ChannelMatrix matrix) throws IOException {
        validateSize(matrix);
        StringBuilder nodes = new StringBuilder();
        List<String> graphInputs = new ArrayList<>(), graphOutputs = new ArrayList<>(), links = new ArrayList<>();
        List<String> copies = appendInputCopies(matrix, nodes, graphInputs);
        appendOutputMixers(matrix, nodes, graphOutputs, links, copies);
        String linksBlock = "links = [\n" + String.join("\n", links) + "\n        ]";
        return renderDocument(description, inputNodeName, outputNodeName, matrix, nodes,
                graphInputs, graphOutputs, linksBlock);
    }

    private static void validateSize(ChannelMatrix matrix) throws IOException {
        if (matrix.inputs().isEmpty() || matrix.outputs().isEmpty()
                || matrix.inputs().size() > 8 || matrix.outputs().size() > 8)
            throw new IOException("A channel matrix must have between 1 and 8 input and output channels");
    }

    private static List<String> appendInputCopies(ChannelMatrix matrix, StringBuilder nodes,
                                                  List<String> graphInputs) {
        List<String> copies = new ArrayList<>();
        for (String input : matrix.inputs()) {
            String name = "copy_" + channelToken(input);
            copies.add(name);
            appendNode(nodes, "{ type = builtin name = " + name + " label = copy }");
            graphInputs.add(name + ":In");
        }
        return copies;
    }

    private static void appendOutputMixers(ChannelMatrix matrix, StringBuilder nodes,
                                           List<String> graphOutputs, List<String> links,
                                           List<String> copies) {
        for (int outputIndex = 0; outputIndex < matrix.outputs().size(); outputIndex++) {
            String output = matrix.outputs().get(outputIndex);
            String mixer = "matrix_" + channelToken(output);
            appendMixer(matrix, nodes, graphOutputs, outputIndex, mixer);
            appendMixerLinks(matrix, nodes, links, copies, outputIndex, output, mixer);
        }
    }

    private static void appendMixer(ChannelMatrix matrix, StringBuilder nodes, List<String> graphOutputs,
                                    int outputIndex, String mixer) {
        List<String> controls = new ArrayList<>();
        for (int inputIndex = 0; inputIndex < matrix.inputs().size(); inputIndex++) {
            double coefficient = matrix.coefficients().get(outputIndex).get(inputIndex);
            controls.add(quote("Gain " + (inputIndex + 1)) + " = " + decimal(Math.abs(coefficient)));
        }
        appendNode(nodes, "{ type = builtin name = " + mixer + " label = mixer control = { "
                + String.join(" ", controls) + " } }");
        graphOutputs.add(mixer + ":Out");
    }

    private static void appendMixerLinks(ChannelMatrix matrix, StringBuilder nodes, List<String> links,
                                         List<String> copies, int outputIndex, String output, String mixer) {
        for (int inputIndex = 0; inputIndex < matrix.inputs().size(); inputIndex++) {
            double coefficient = matrix.coefficients().get(outputIndex).get(inputIndex);
            if (coefficient == 0.0) continue;
            String source = copies.get(inputIndex) + ":Out";
            if (coefficient < 0.0) source = appendInverter(nodes, links, source, output, matrix.inputs().get(inputIndex));
            links.add("          { output = " + quote(source) + " input = "
                    + quote(mixer + ":In " + (inputIndex + 1)) + " }");
        }
    }

    private static String appendInverter(StringBuilder nodes, List<String> links,
                                         String source, String output, String input) {
        String invert = "invert_" + channelToken(output) + "_" + channelToken(input);
        appendNode(nodes, "{ type = builtin name = " + invert + " label = invert }");
        links.add("          { output = " + quote(source) + " input = " + quote(invert + ":In") + " }");
        return invert + ":Out";
    }

    private static String renderDocument(String description, String inputNodeName, String outputNodeName,
                                         ChannelMatrix matrix, StringBuilder nodes, List<String> graphInputs,
                                         List<String> graphOutputs, String linksBlock) {
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
                        inputs = [ %s ]
                        outputs = [ %s ]
                        %s
                      }
                      capture.props = { node.name = %s media.class = Audio/Sink node.autoconnect = false audio.channels = %d audio.position = %s }
                      playback.props = { node.name = %s node.passive = true node.autoconnect = false audio.channels = %d audio.position = %s }
                    }
                  }
                ]
                """.formatted(escape(description), escape(description), nodes,
                String.join(" ", graphInputs.stream().map(PipeWireChannelMatrixConfig::quote).toList()),
                String.join(" ", graphOutputs.stream().map(PipeWireChannelMatrixConfig::quote).toList()),
                linksBlock, inputNodeName, matrix.inputs().size(), positionArg(matrix.inputs()),
                outputNodeName, matrix.outputs().size(), positionArg(matrix.outputs()));
    }

    private static void appendNode(StringBuilder nodes, String node) {
        if (!nodes.isEmpty()) nodes.append('\n');
        nodes.append("          ").append(node);
    }

    private static String channelToken(String position) {
        return position.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private static String positionArg(List<String> positions) { return "[ " + String.join(" ", positions) + " ]"; }
    private static String quote(String value) { return "\"" + escape(value) + "\""; }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static String decimal(double value) { return String.format(java.util.Locale.ROOT, "%.8f", value); }
}
