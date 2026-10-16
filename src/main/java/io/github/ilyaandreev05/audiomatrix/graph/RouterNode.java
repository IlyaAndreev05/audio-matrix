package io.github.ilyaandreev05.audiomatrix.graph;

import java.util.List;
import java.util.Map;

/** A labeled N×M connection table. Selection controls which cells are linked. */
public non-sealed class RouterNode extends GraphNode {
    private final List<String> activeLinks;
    private final boolean multiple;
    public RouterNode(String id, String name, List<String> inputChannels, List<String> outputChannels,
                      List<String> activeLinks, boolean multiple) {
        this(id,name,List.of("in"),List.of("out"),inputChannels,activeLinks,multiple);
        if(!inputChannels.equals(outputChannels))throw new IllegalArgumentException("Legacy router ports must use the same channel layout");
    }
    protected RouterNode(String id,String name,List<String> inputPortIds,List<String> outputPortIds,List<String> channels,
                         List<String> activeLinks,boolean multiple){
        super(id,name,ports(inputPortIds,outputPortIds,channels));
        this.activeLinks = List.copyOf(activeLinks);
        if (this.activeLinks.stream().distinct().count() != this.activeLinks.size())
            throw new IllegalArgumentException("Router links must be unique");
        if (!multiple && this.activeLinks.size() > 1)
            throw new IllegalArgumentException("A single-select router can enable only one link");
        if(!"router".equals(type()))for(String link:this.activeLinks){String[] endpoints=link.split("->",2);if(endpoints.length!=2||!inputPortIds.contains(endpoints[0])||!outputPortIds.contains(endpoints[1]))throw new IllegalArgumentException("Router link must name an existing input and output port: "+link);}
        this.multiple = multiple;
    }
    private static List<AudioPort> ports(List<String> inputIds,List<String> outputIds,List<String> channels) {
        if (inputIds == null || inputIds.isEmpty() || outputIds == null || outputIds.isEmpty())
            throw new IllegalArgumentException("A router needs at least one input and output");
        if(inputIds.stream().distinct().count()!=inputIds.size()||outputIds.stream().distinct().count()!=outputIds.size())throw new IllegalArgumentException("Router port ids must be unique");
        java.util.ArrayList<AudioPort> ports=new java.util.ArrayList<>();inputIds.forEach(id->ports.add(new AudioPort(id,AudioPort.Direction.INPUT,channels,false)));outputIds.forEach(id->ports.add(new AudioPort(id,AudioPort.Direction.OUTPUT,channels,false)));return List.copyOf(ports);
    }
    public List<String> activeLinks() { return activeLinks; }
    public boolean multiple() { return multiple; }
    @Override public String type() { return "router"; }
    @Override public Map<String, Object> parameters() { return Map.of("input_ports",inputs().stream().map(AudioPort::id).toList(),"output_ports",outputs().stream().map(AudioPort::id).toList(),"active_links", activeLinks, "multiple", multiple); }
}
