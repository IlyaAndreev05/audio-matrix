package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Small parsers for the port and link listing formats produced by pw-link. */
final class PipeWireLinkOperations {
    private PipeWireLinkOperations() {}

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
