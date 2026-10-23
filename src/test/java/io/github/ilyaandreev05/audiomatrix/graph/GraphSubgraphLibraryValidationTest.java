package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.ConfigValidator;
import io.github.ilyaandreev05.audiomatrix.config.DefaultConfig;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphSubgraphLibraryValidationTest {

    @Test void validationChecksHierarchyAndDirectMembership() {
        String autoKey = "app:auto";
        String autoId = DynamicInstanceRegistry.stableCompositeId("generator", autoKey);
        AudioGraph graph = GraphSubgraphLibraryFixtures.graphWithGenerator(Map.of(autoKey, autoId));
        GraphSubgraph root = new GraphSubgraph("root", "Root", List.of("generator", "gain-a"), true);
        GraphSubgraph child = new GraphSubgraph("child", "Child", List.of("gain-b", "output"), true, root.id(), null);
        GraphSubgraph container = new GraphSubgraph(autoId, "Automatic", List.of(), true, child.id(), "generator");
        Map<String,Object> config = GraphSubgraphLibraryFixtures.configWithGroups(graph, GraphSubgraphLibrary.encoded(List.of(root, child, container)));
        assertTrue(ConfigValidator.validate(config).isEmpty());

        GraphSubgraph orphan = new GraphSubgraph("orphan", "Orphan", List.of("generator", "gain-a"), true, "missing", null);
        assertTrue(ConfigValidator.validate(GraphSubgraphLibraryFixtures.configWithGroups(GraphSubgraphLibraryFixtures.graphWithGenerator(Map.of()), GraphSubgraphLibrary.encoded(List.of(orphan))))
                .stream().anyMatch(problem -> problem.contains("missing parent")));

        GraphSubgraph cycleA = new GraphSubgraph("cycle-a", "Cycle A", List.of("generator", "gain-a"), true, "cycle-b", null);
        GraphSubgraph cycleB = new GraphSubgraph("cycle-b", "Cycle B", List.of("gain-b", "output"), true, "cycle-a", null);
        assertTrue(ConfigValidator.validate(GraphSubgraphLibraryFixtures.configWithGroups(GraphSubgraphLibraryFixtures.graphWithGenerator(Map.of()), GraphSubgraphLibrary.encoded(List.of(cycleA, cycleB))))
                .stream().anyMatch(problem -> problem.contains("parent cycle")));

        GraphSubgraph duplicateId = new GraphSubgraph("root", "Duplicate", List.of("gain-b", "output"), true);
        assertTrue(ConfigValidator.validate(GraphSubgraphLibraryFixtures.configWithGroups(GraphSubgraphLibraryFixtures.graphWithGenerator(Map.of()), GraphSubgraphLibrary.encoded(List.of(root, duplicateId))))
                .stream().anyMatch(problem -> problem.contains("duplicate id")));

        GraphSubgraph overlapping = new GraphSubgraph("overlap", "Overlap", List.of("generator", "gain-b"), true);
        assertTrue(ConfigValidator.validate(GraphSubgraphLibraryFixtures.configWithGroups(GraphSubgraphLibraryFixtures.graphWithGenerator(Map.of()), GraphSubgraphLibrary.encoded(List.of(root, overlapping))))
                .stream().anyMatch(problem -> problem.contains("more than one subgraph")));

        GraphSubgraph missingNode = new GraphSubgraph("missing-node", "Missing node", List.of("unknown", "gain-a"), true);
        assertTrue(ConfigValidator.validate(GraphSubgraphLibraryFixtures.configWithGroups(GraphSubgraphLibraryFixtures.graphWithGenerator(Map.of()), GraphSubgraphLibrary.encoded(List.of(missingNode))))
                .stream().anyMatch(problem -> problem.contains("references a missing node")));

        String wrongGeneratorId = DynamicInstanceRegistry.stableCompositeId("missing-generator", "app:wrong");
        GraphSubgraph wrongGenerator = new GraphSubgraph(wrongGeneratorId, "Wrong generator", List.of(), true, null, "missing-generator");
        assertTrue(ConfigValidator.validate(GraphSubgraphLibraryFixtures.configWithGroups(GraphSubgraphLibraryFixtures.graphWithGenerator(Map.of()), GraphSubgraphLibrary.encoded(List.of(wrongGenerator))))
                .stream().anyMatch(problem -> problem.contains("generator")));

        String unassociatedId = DynamicInstanceRegistry.stableCompositeId("generator", "app:unassociated");
        GraphSubgraph unassociated = new GraphSubgraph(unassociatedId, "Unassociated", List.of(), true, null, "generator");
        assertTrue(ConfigValidator.validate(GraphSubgraphLibraryFixtures.configWithGroups(GraphSubgraphLibraryFixtures.graphWithGenerator(Map.of()), GraphSubgraphLibrary.encoded(List.of(unassociated))))
                .stream().anyMatch(problem -> problem.contains("exactly one")));

        String duplicateCompositeId = DynamicInstanceRegistry.stableCompositeId("generator", "app:duplicate");
        GraphSubgraph duplicateAssociation = new GraphSubgraph(duplicateCompositeId, "Duplicate association", List.of(), true, null, "generator");
        AudioGraph duplicateInstances = GraphSubgraphLibraryFixtures.graphWithGenerator(Map.of("app:first", duplicateCompositeId, "app:second", duplicateCompositeId));
        assertTrue(ConfigValidator.validate(GraphSubgraphLibraryFixtures.configWithGroups(duplicateInstances, GraphSubgraphLibrary.encoded(List.of(duplicateAssociation))))
                .stream().anyMatch(problem -> problem.contains("exactly one")));
        String missingGroupId = DynamicInstanceRegistry.stableCompositeId("generator", "app:missing-group");
        Map<String,Object> missingGroupConfig = DefaultConfig.create();
        GraphRouteLibrary.saveRoute(missingGroupConfig,
                GraphSubgraphLibraryFixtures.graphWithGenerator(Map.of("app:missing-group", missingGroupId)), "");
        assertTrue(ConfigValidator.validate(missingGroupConfig).stream()
                .anyMatch(problem -> problem.contains("references a missing subgraph")));

        Map<String,Object> unassociatedEmpty = Map.of("id", "empty", "name", "Empty", "node_ids", List.of(), "collapsed", true);
        assertTrue(ConfigValidator.validate(GraphSubgraphLibraryFixtures.configWithGroups(GraphSubgraphLibraryFixtures.graphWithGenerator(Map.of()), List.of(unassociatedEmpty)))
                .stream().anyMatch(problem -> problem.contains("generator")));
    }

    @Test void malformedCompositeReturnsValidationDiagnostic() {
        AudioGraph graph = GraphSubgraphLibraryFixtures.graphWithGenerator(Map.of());
        Map<String, Object> malformed = Map.of("id", "empty", "name", "Empty", "node_ids", List.of(), "collapsed", true);
        Map<String, Object> config = GraphSubgraphLibraryFixtures.configWithGroups(graph, List.of(malformed));
        QuickSettingsConfig.pinNode(config, graph.id(), "empty", "applications", 0, "DEFAULT");

        List<String> diagnostics = ConfigValidator.validate(config);
        assertTrue(diagnostics.stream().anyMatch(problem ->
                problem.contains("is invalid") && problem.contains("empty subgraph")));
    }
}
