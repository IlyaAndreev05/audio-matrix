package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ConfigValidator;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GraphRouteLibraryGeneratorCopyTest {

    @Test void duplicateAndRestoreRemapDynamicGeneratorStateAndNestedSubgraphs(){
        Map<String,Object> config=DefaultConfig.create();
        String instanceKey="browser:chrome:tab-1";
        Map<String,Object> seeded=DynamicInstanceRegistry.seedInstanceRecord("tabs",instanceKey,List.of("source","gain"));
        Map<String,Object> overrides=Map.of("gain",0.37,"muted",true,"display_name","Saved player","pinned",false);
        seeded.put("overrides",overrides);
        DynamicGeneratorNode generator=new DynamicGeneratorNode("tabs","Browser tabs","browser:*",GraphRouteLibraryFixtures.STEREO,
                Map.of("registry","browser_tabs","instances",Map.of(instanceKey,seeded)));
        AudioGraph route=new AudioGraph("generated-route","Generated browser tabs",List.of(
                new SourceNode("browser-source","Browser","application:unassigned",GraphRouteLibraryFixtures.STEREO),
                generator,
                new GainNode("gain-a","Input gain",GraphRouteLibraryFixtures.STEREO,.8,false),
                new GainNode("gain-b","Output gain",GraphRouteLibraryFixtures.STEREO,.6,false),
                new OutputNode("output","Monitor","device:monitor",GraphRouteLibraryFixtures.STEREO)),List.of(
                new GraphConnection("one","browser-source","out","gain-a","in",true,1),
                new GraphConnection("two","gain-a","out","gain-b","in",true,1),
                new GraphConnection("three","gain-b","out","output","in",true,1)));
        GraphRouteLibrary.saveRoute(config,route,"");

        GraphSubgraph parent=GraphSubgraphLibrary.create(config,route,"Applications",List.of(generator.id(),"gain-a"));
        GraphSubgraph composite=new GraphSubgraph(String.valueOf(seeded.get("composite_id")),"Generated player",List.of(),
                true,parent.id(),generator.id());
        GraphSubgraphLibrary.mutableByRoute(config).put(route.id(),GraphSubgraphLibrary.encoded(List.of(parent,composite)));
        Map<String,Object> pinGroup=QuickSettingsConfig.findOrCreateGroup(config,"Generators");
        Map<String,Object> originalPin=QuickSettingsConfig.pinNode(config,route.id(),generator.id(),
                String.valueOf(pinGroup.get("id")),0,"EXPANDED");
        String savedCopyId=GraphRouteLibrary.saveInactiveCopy(config,route.id(),"Generator snapshot");

        AudioGraph duplicate=GraphRouteLibrary.duplicateRoute(config,route.id(),"Duplicated generator");
        AudioGraph restored=GraphRouteLibrary.restoreSavedCopy(config,savedCopyId,"Restored generator");

        DynamicGeneratorNode originalGenerator=GraphRouteLibraryFixtures.dynamicGenerator(route);
        DynamicGeneratorNode duplicateGenerator=GraphRouteLibraryFixtures.dynamicGenerator(duplicate);
        DynamicGeneratorNode restoredGenerator=GraphRouteLibraryFixtures.dynamicGenerator(restored);
        assertNotEquals(originalGenerator.id(),duplicateGenerator.id());
        assertNotEquals(originalGenerator.id(),restoredGenerator.id());
        assertNotEquals(duplicateGenerator.id(),restoredGenerator.id());

        Map<?,?> originalInstance=(Map<?,?>)originalGenerator.instances().get(instanceKey);
        Map<?,?> originalNodeIds=(Map<?,?>)originalInstance.get("node_ids");
        Map<?,?> duplicateInstance=(Map<?,?>)duplicateGenerator.instances().get(instanceKey);
        Map<?,?> duplicateNodeIds=(Map<?,?>)duplicateInstance.get("node_ids");
        Map<?,?> restoredInstance=(Map<?,?>)restoredGenerator.instances().get(instanceKey);
        Map<?,?> restoredNodeIds=(Map<?,?>)restoredInstance.get("node_ids");
        for(String role:List.of("source","gain")){
            assertNotEquals(originalNodeIds.get(role),duplicateNodeIds.get(role));
            assertNotEquals(originalNodeIds.get(role),restoredNodeIds.get(role));
            assertNotEquals(duplicateNodeIds.get(role),restoredNodeIds.get(role));
            assertEquals(DynamicInstanceRegistry.stableNodeId(duplicateGenerator.id(),instanceKey,role),duplicateNodeIds.get(role));
            assertEquals(DynamicInstanceRegistry.stableNodeId(restoredGenerator.id(),instanceKey,role),restoredNodeIds.get(role));
        }
        assertEquals(1,duplicateGenerator.instances().size());
        assertEquals(1,restoredGenerator.instances().size());
        assertTrue(duplicateGenerator.instances().containsKey(instanceKey));
        assertTrue(restoredGenerator.instances().containsKey(instanceKey));
        assertEquals(overrides,duplicateInstance.get("overrides"));
        assertEquals(overrides,restoredInstance.get("overrides"));
        assertNotEquals(originalInstance.get("composite_id"),duplicateInstance.get("composite_id"));
        assertNotEquals(originalInstance.get("composite_id"),restoredInstance.get("composite_id"));
        assertNotEquals(duplicateInstance.get("composite_id"),restoredInstance.get("composite_id"));
        assertEquals(DynamicInstanceRegistry.stableCompositeId(duplicateGenerator.id(),instanceKey),
                duplicateInstance.get("composite_id"));
        assertEquals(DynamicInstanceRegistry.stableCompositeId(restoredGenerator.id(),instanceKey),
                restoredInstance.get("composite_id"));

        GraphSubgraph originalParent=GraphRouteLibraryFixtures.subgraph(config,route.id(),"Applications");
        GraphSubgraph originalComposite=GraphRouteLibraryFixtures.subgraph(config,route.id(),"Generated player");
        GraphSubgraph duplicateParent=GraphRouteLibraryFixtures.subgraph(config,duplicate.id(),"Applications");
        GraphSubgraph duplicateComposite=GraphRouteLibraryFixtures.subgraph(config,duplicate.id(),"Generated player");
        GraphSubgraph restoredParent=GraphRouteLibraryFixtures.subgraph(config,restored.id(),"Applications");
        GraphSubgraph restoredComposite=GraphRouteLibraryFixtures.subgraph(config,restored.id(),"Generated player");
        assertNotEquals(originalParent.id(),duplicateParent.id());
        assertNotEquals(originalParent.id(),restoredParent.id());
        assertNotEquals(duplicateParent.id(),restoredParent.id());
        assertNotEquals(originalComposite.id(),duplicateComposite.id());
        assertNotEquals(originalComposite.id(),restoredComposite.id());
        assertNotEquals(duplicateComposite.id(),restoredComposite.id());
        assertEquals(duplicateInstance.get("composite_id"),duplicateComposite.id());
        assertEquals(restoredInstance.get("composite_id"),restoredComposite.id());
        assertEquals(duplicateParent.id(),duplicateComposite.parentId());
        assertEquals(restoredParent.id(),restoredComposite.parentId());
        assertEquals(duplicateGenerator.id(),duplicateComposite.generatorId());
        assertEquals(restoredGenerator.id(),restoredComposite.generatorId());
        assertTrue(duplicate.nodes().stream().map(GraphNode::id).toList().containsAll(duplicateParent.nodeIds()));
        assertTrue(restored.nodes().stream().map(GraphNode::id).toList().containsAll(restoredParent.nodeIds()));
        assertNotEquals(originalParent.nodeIds(),duplicateParent.nodeIds());
        assertNotEquals(originalParent.nodeIds(),restoredParent.nodeIds());

        Map<String,Object> duplicatePin=GraphRouteLibraryFixtures.pin(config,duplicate.id(),duplicateGenerator.id());
        Map<String,Object> restoredPin=GraphRouteLibraryFixtures.pin(config,restored.id(),restoredGenerator.id());
        assertEquals(duplicateGenerator.id(),duplicatePin.get("node_id"));
        assertEquals(restoredGenerator.id(),restoredPin.get("node_id"));
        assertNotEquals(originalPin.get("id"),duplicatePin.get("id"));
        assertNotEquals(originalPin.get("id"),restoredPin.get("id"));
        assertNotEquals(duplicatePin.get("id"),restoredPin.get("id"));
        assertEquals(List.of(),ConfigValidator.validate(config));
    }
}
