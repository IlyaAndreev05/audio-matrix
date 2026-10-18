package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;

/** One source can feed any selected output branches. */
public final class SwitchNode extends RouterNode {
    public SwitchNode(String id, String name, List<String> channels, List<String> activeLinks) {
        this(id,name,channels,List.of("out"),activeLinks);
    }
    public SwitchNode(String id,String name,List<String> channels,List<String> outputPorts,List<String> activeLinks){
        super(id,name,List.of("in"),outputPorts,channels,activeLinks,true);
    }
    @Override public String type() { return "switch"; }
}
