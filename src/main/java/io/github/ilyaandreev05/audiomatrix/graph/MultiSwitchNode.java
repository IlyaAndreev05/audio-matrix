package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;

/** One source can feed several selected output branches. */
public final class MultiSwitchNode extends RouterNode {
    public MultiSwitchNode(String id, String name, List<String> channels, List<String> activeLinks) {
        this(id,name,channels,List.of("out"),activeLinks);
    }
    public MultiSwitchNode(String id,String name,List<String> channels,List<String> outputPorts,List<String> activeLinks){
        super(id,name,List.of("in"),outputPorts,channels,activeLinks,true);
    }
    @Override public String type() { return "multi_switch"; }
}
