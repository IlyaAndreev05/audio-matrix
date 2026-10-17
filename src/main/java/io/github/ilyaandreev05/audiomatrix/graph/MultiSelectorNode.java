package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;

/** Selects and sums any subset of available inputs into one output. */
public final class MultiSelectorNode extends RouterNode {
    public MultiSelectorNode(String id, String name, List<String> channels, List<String> activeLinks) {
        this(id, name, channels, List.of("in"), activeLinks);
    }

    public MultiSelectorNode(String id, String name, List<String> channels,
                            List<String> inputPorts, List<String> activeLinks) {
        super(id, name, inputPorts, List.of("out"), channels, activeLinks, true);
    }

    @Override
    public String type() {
        return "multi_selector";
    }
}
