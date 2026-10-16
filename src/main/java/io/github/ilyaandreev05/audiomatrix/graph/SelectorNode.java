package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;

/** Several available sources, with exactly one selected route at a time. */
public final class SelectorNode extends RouterNode {
    public SelectorNode(String id, String name, List<String> channels, List<String> activeLinks) {
        this(id,name,channels,List.of("in"),activeLinks);
    }
    public SelectorNode(String id,String name,List<String> channels,List<String> inputPorts,List<String> activeLinks){
        super(id,name,inputPorts,List.of("out"),channels,activeLinks,false);
    }
    @Override public String type() { return "selector"; }
}
