package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;

/** N-to-M routing table. The editor presents each enabled link as one matrix cell. */
public final class MatrixNode extends RouterNode {
    public MatrixNode(String id, String name, List<String> channels, List<String> activeLinks) {
        this(id,name,channels,List.of("in"),List.of("out"),activeLinks);
    }
    public MatrixNode(String id,String name,List<String> channels,List<String> inputPorts,List<String> outputPorts,List<String> activeLinks){
        super(id,name,inputPorts,outputPorts,channels,activeLinks,true);
    }
    @Override public String type() { return "matrix"; }
}
