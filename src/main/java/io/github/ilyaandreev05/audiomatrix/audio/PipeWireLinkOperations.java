package io.github.ilyaandreev05.audiomatrix.audio;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Port/link listing parsers and transactional stream moves using pw-link. */
final class PipeWireLinkOperations {
    private PipeWireLinkOperations() {}

    @FunctionalInterface
    interface Command {
        String run(List<String> command, Duration timeout) throws Exception;
    }

    static void moveStream(String streamNode, String sinkNode, Command command) throws Exception {
        List<PipeWireRouteManager.Link> previous = List.of(), connected = new ArrayList<>();
        try {
            List<String> sourcePorts = portsForNode(command.run(List.of("pw-link", "-o"), Duration.ofSeconds(3)), streamNode);
            List<String> targetPorts = portsForNode(command.run(List.of("pw-link", "-i"), Duration.ofSeconds(3)), sinkNode);
            List<PipeWireRouteManager.Link> replacement = PipeWireRouteManager.channelLinks(sourcePorts, targetPorts, "stereo", false);
            if (replacement.isEmpty()) throw new IOException("No compatible audio ports are available on the selected sink");
            previous = linksFromNode(command.run(List.of("pw-link", "-l"), Duration.ofSeconds(3)), streamNode);
            if (previous.size() == replacement.size() && previous.containsAll(replacement)) return;
            for (PipeWireRouteManager.Link link : previous)
                command.run(List.of("pw-link", "-d", link.output(), link.input()), Duration.ofSeconds(3));
            for (PipeWireRouteManager.Link link : replacement) {
                // Register before execution: a timeout can happen after PipeWire created the link.
                connected.add(link);
                // --wait keeps a pw-link client alive; it is not a creation acknowledgement.
                command.run(List.of("pw-link", link.output(), link.input()), Duration.ofSeconds(3));
            }
            List<PipeWireRouteManager.Link> observed = linksFromNode(
                    command.run(List.of("pw-link", "-l"), Duration.ofSeconds(3)), streamNode);
            if (observed.size() != replacement.size() || !observed.containsAll(replacement))
                throw new IOException("PipeWire did not confirm the stream's replacement links");
        } catch (Exception failure) {
            for (PipeWireRouteManager.Link link : connected) try {
                command.run(List.of("pw-link", "-d", link.output(), link.input()), Duration.ofSeconds(2));
            } catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            for (PipeWireRouteManager.Link link : previous) try {
                command.run(List.of("pw-link", link.output(), link.input()), Duration.ofSeconds(3));
            } catch (Exception restoreFailure) { failure.addSuppressed(restoreFailure); }
            throw failure;
        }
    }

    static List<String> portsForNode(String listing, String nodeName) {
        String prefix = nodeName + ":";
        List<String> ports = new ArrayList<>();
        for (String line : listing.split("\\R")) {
            String port = line.trim();
            if (port.startsWith(prefix) && !port.contains(" -> ") && !port.contains(" <- ")) ports.add(port);
        }
        return List.copyOf(ports);
    }

    static List<PipeWireRouteManager.Link> linksFromNode(String listing, String nodeName) {
        List<PipeWireRouteManager.Link> links = new ArrayList<>();
        String current = "";
        for (String line : listing.split("\\R")) {
            if (!line.startsWith(" ") && !line.startsWith("\t") && line.contains(":")) {
                current = line.trim();
            } else {
                String edge = line.trim();
                if (edge.startsWith("|->") && current.startsWith(nodeName + ":"))
                    links.add(new PipeWireRouteManager.Link(current, edge.substring(3).trim()));
                else if (edge.startsWith("|<-")) {
                    String source = edge.substring(3).trim();
                    if (source.startsWith(nodeName + ":")) links.add(new PipeWireRouteManager.Link(source, current));
                }
            }
        }
        return List.copyOf(new LinkedHashSet<>(links));
    }

    static List<PipeWireRouteManager.Link> allLinks(String listing) {
        List<PipeWireRouteManager.Link> links = new ArrayList<>();
        String current = "";
        for (String line : listing.split("\\R")) {
            if (!line.startsWith(" ") && !line.startsWith("\t") && line.contains(":")) {
                current = line.trim();
                continue;
            }
            String edge = line.trim();
            if (edge.startsWith("|->") && current.contains(":"))
                links.add(new PipeWireRouteManager.Link(current, edge.substring(3).trim()));
            else if (edge.startsWith("|<-") && current.contains(":"))
                links.add(new PipeWireRouteManager.Link(edge.substring(3).trim(), current));
        }
        return List.copyOf(new LinkedHashSet<>(links));
    }
}
