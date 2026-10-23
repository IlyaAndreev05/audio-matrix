package io.github.ilyaandreev05.audiomatrix.config;

import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class QuickSettingsConfigTest {
    @Test void defaultsHaveFourStableRussianGroups() {
        List<Map<String, Object>> groups = QuickSettingsConfig.groups(DefaultConfig.create());

        assertEquals(List.of("applications", "outputs", "routing", "events"),
                groups.stream().map(group -> String.valueOf(group.get("id"))).toList());
        assertEquals(List.of("Приложения", "Выходные устройства", "Маршрутизация", "Звуки событий"),
                groups.stream().map(group -> String.valueOf(group.get("name"))).toList());
        assertTrue(groups.stream().noneMatch(group -> Boolean.TRUE.equals(group.get("collapsed"))));
    }

    @Test void defaultPinsExposeMainSourcesAndControlsButNotTheInternalMerge() {
        Map<String, Object> config = DefaultConfig.create();
        List<Map<String, Object>> pins = QuickSettingsConfig.pins(config);

        assertEquals(4, pins.size());
        assertTrue(pins.stream().anyMatch(pin -> "octine-main-source".equals(pin.get("node_id"))
                && "applications".equals(pin.get("group_id"))));
        assertTrue(pins.stream().anyMatch(pin -> "octine-main-switch".equals(pin.get("node_id"))
                && "routing".equals(pin.get("group_id"))));
        assertTrue(pins.stream().anyMatch(pin -> "octine-vibro-gain".equals(pin.get("node_id"))
                && "outputs".equals(pin.get("group_id"))));
        assertTrue(pins.stream().anyMatch(pin -> "octine-vibro-events-volume".equals(pin.get("node_id"))
                && "events".equals(pin.get("group_id"))));
        assertFalse(pins.stream().anyMatch(pin -> Set.of("octine-vibro-mixer", "octine-vibro-limiter",
                "octine-vibro-music-volume", "octine-bass-low-pass").contains(pin.get("node_id"))));
    }

    @Test void migrationDropsBassControlsFromMenuAndKeepsThemInTheArchive() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("schema_version", 9);
        config.put("common_vibro_graph", io.github.ilyaandreev05.audiomatrix.graph.GraphCodec.toMap(
                GraphCommonVibro.defaults()));
        config.put("quick_controls", new ArrayList<>(List.of(DefaultConfig.map(
                "id", "bass-frequency", "route_id", "octine-common-vibro",
                "node_id", "octine-bass-low-pass", "parameter_id", "frequency_hz",
                "section", "Details", "order", 1))));
        config.put("quick_settings", DefaultConfig.map("groups", List.of(
                DefaultConfig.map("id", "sources", "name", "Sources", "order", 0, "collapsed", false),
                DefaultConfig.map("id", "vibro", "name", "Vibro", "order", 1, "collapsed", false)),
                "pins", List.of()));

        ConfigMigration.upgradeConfig(config);

        assertEquals(List.of("applications", "outputs", "routing", "events"),
                QuickSettingsConfig.groups(config).stream().map(group -> String.valueOf(group.get("id"))).toList());
        assertTrue(QuickSettingsConfig.pins(config).stream().noneMatch(pin ->
                "octine-bass-low-pass".equals(pin.get("node_id")) || "octine-vibro-mixer".equals(pin.get("node_id"))));
        assertTrue(ConfigValidationValues.optionalList(config.get("quick_controls"), "quick_controls", new ArrayList<>()).isEmpty());
        Map<String, Object> archive = (Map<String, Object>) config.get("migration_archive");
        Map<String, Object> prior = (Map<String, Object>) archive.get("prior_config");
        assertTrue(prior.toString().contains("bass-frequency"));
        assertEquals(List.of(), ConfigValidator.validate(config));
    }

    @Test void aUserControlOnAnExistingNodeKeepsItsIdAndGroupDuringMigration() {
        Map<String, Object> config = DefaultConfig.create();
        config.put("schema_version", 9);
        config.put("quick_controls", new ArrayList<>(List.of(
                DefaultConfig.map("id", "my-overall-strength", "route_id", "main", "node_id", "octine-main-switch",
                        "parameter_id", "active_links", "section", "My vibration", "group_id", "routing", "order", 4),
                DefaultConfig.map("id", "my-vibro-level", "route_id", "main", "node_id", "octine-vibro-gain",
                        "parameter_id", "gain", "section", "My vibration", "group_id", "outputs", "order", 5))));

        ConfigMigration.upgradeConfig(config);

        Map<String, Object> routing = QuickSettingsConfig.groups(config).stream()
                .filter(group -> "routing".equals(group.get("id"))).findFirst().orElseThrow();
        Map<String, Object> outputs = QuickSettingsConfig.groups(config).stream()
                .filter(group -> "outputs".equals(group.get("id"))).findFirst().orElseThrow();
        List<Map<String, Object>> controls = ConfigValidationValues.optionalList(config.get("quick_controls"),
                "quick_controls", new ArrayList<>());
        Map<String, Object> control = controls.stream()
                .filter(item -> "my-overall-strength".equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, Object> level = controls.stream()
                .filter(item -> "my-vibro-level".equals(item.get("id"))).findFirst().orElseThrow();
        assertEquals("Маршрутизация", routing.get("name"));
        assertEquals("Выходные устройства", outputs.get("name"));
        assertEquals("routing", control.get("group_id"));
        assertEquals("outputs", level.get("group_id"));
        assertTrue(QuickSettingsConfig.pins(config).stream().anyMatch(pin ->
                "octine-main-switch".equals(pin.get("node_id"))
                        && ((List<?>) pin.get("control_ids")).contains("my-overall-strength")));
        assertTrue(QuickSettingsConfig.pins(config).stream().anyMatch(pin ->
                "octine-vibro-gain".equals(pin.get("node_id"))
                        && ((List<?>) pin.get("control_ids")).contains("my-vibro-level")));
    }
    @Test void generatedInstancePinsUseGeneratorIdentityAndUnpinStoresSparseFalse() {
        Map<String, Object> config = DefaultConfig.create();
        String generatorId = "application-generator";
        String instanceKey = "app:org.example.Player";
        Map<String, Object> record = io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry
                .seedInstanceRecord(generatorId, instanceKey, List.of("source", "gain"));
        Map<String, Object> generator = DefaultConfig.map("id", generatorId, "type", "dynamic_generator",
                "name", "Applications", "channels", List.of("FL", "FR"),
                "parameters", DefaultConfig.map("registry", "applications",
                        "instances", new LinkedHashMap<>(Map.of(instanceKey, record))));
        Map<String, Object> route = ((List<Map<String, Object>>) config.get("route_library")).stream()
                .filter(item -> "main".equals(item.get("id"))).findFirst().orElseThrow();
        List<Map<String, Object>> nodes = new ArrayList<>((List<Map<String, Object>>) route.get("nodes"));
        nodes.add(generator);
        route.put("nodes", nodes);

        QuickSettingsConfig.pinNode(config, "main", generatorId, instanceKey, "applications", 20, "EXPANDED");
        QuickSettingsConfig.updatePin(config, "main", generatorId, instanceKey, "applications", 20, "EXPANDED",
                "Player", "audio-x-generic-symbolic", true, true);
        Map<String, Object> pin = QuickSettingsConfig.pins(config).stream()
                .filter(item -> generatorId.equals(item.get("node_id")) && instanceKey.equals(item.get("instance_key")))
                .findFirst().orElseThrow();
        assertEquals("main", pin.get("route_id"));
        assertEquals("Player", pin.get("display_name"));
        assertEquals("audio-x-generic-symbolic", pin.get("icon"));
        assertEquals(true, pin.get("show_children"));
        assertEquals(true, pin.get("show_when_offline"));

        QuickSettingsConfig.unpinNode(config, "main", generatorId, instanceKey);
        assertTrue(QuickSettingsConfig.pins(config).stream().noneMatch(item ->
                generatorId.equals(item.get("node_id")) && instanceKey.equals(item.get("instance_key"))));
        assertEquals(java.util.Optional.of(false),
                io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry.instanceOverride(
                        config, "main", generatorId, instanceKey, "pinned"));
        assertTrue(((List<Map<String, Object>>) route.get("nodes")).stream()
                .anyMatch(item -> generatorId.equals(item.get("id"))),
                "Unpinning presentation must not remove the graph generator");
    }

    @Test void deletingAGroupTransfersPresentationWithoutChangingTheGraph() {
        Map<String, Object> config = DefaultConfig.create();
        Map<String, Object> custom = QuickSettingsConfig.createGroup(config, "Custom", 4);
        String customId = String.valueOf(custom.get("id"));
        QuickSettingsConfig.pinNode(config, "main", "preserved-node", customId, 0, "DEFAULT");
        ((List<Map<String, Object>>) config.get("quick_controls")).add(DefaultConfig.map(
                "id", "preserved-control", "route_id", "main", "node_id", "preserved-node",
                "group_id", customId, "section", "Custom"));
        String graphBefore = String.valueOf(config.get("route_library"));

        QuickSettingsConfig.removeGroup(config, customId);

        assertFalse(QuickSettingsConfig.groups(config).stream().anyMatch(group -> customId.equals(group.get("id"))));
        assertEquals("applications", QuickSettingsConfig.pins(config).stream()
                .filter(pin -> "preserved-node".equals(pin.get("node_id"))).findFirst().orElseThrow().get("group_id"));
        assertEquals("applications", ((List<Map<String, Object>>) config.get("quick_controls")).stream()
                .filter(control -> "preserved-control".equals(control.get("id"))).findFirst().orElseThrow().get("group_id"));
        assertEquals(graphBefore, String.valueOf(config.get("route_library")));
    }

    @Test void removingTheLastGroupCreatesAStableUnassignedGroup() {
        Map<String, Object> config = DefaultConfig.create();
        Map<String, Object> only = DefaultConfig.map("id", "only-group", "name", "Only", "order", 0,
                "collapsed", false);
        Map<String, Object> settings = (Map<String, Object>) config.get("quick_settings");
        settings.put("groups", new ArrayList<>(List.of(only)));

        QuickSettingsConfig.removeGroup(config, "only-group");

        assertEquals(1, QuickSettingsConfig.groups(config).size());
        Map<String, Object> replacement = QuickSettingsConfig.groups(config).getFirst();
        assertEquals("Без группы", replacement.get("name"));
        assertNotEquals("only-group", replacement.get("id"));
    }

    @Test void generatedChildrenKeepIndependentPinsAndUnpinDoesNotHideTheirInstance() {
        Map<String,Object> config=DefaultConfig.create();
        QuickSettingsConfig.pinNode(config,"main","generator","app:player","","applications",0,"EXPANDED");
        QuickSettingsConfig.updatePin(config,"main","generator","app:player","gain","applications",1,"EXPANDED",
                "Громкость проигрывателя","multimedia-player-symbolic",false,true,false);
        QuickSettingsConfig.pinNode(config,"main","generator","app:player","source","applications",2,"DEFAULT");

        assertNotSame(QuickSettingsConfig.findPin(config,"main","generator","app:player","gain"),
                QuickSettingsConfig.findPin(config,"main","generator","app:player","source"));
        QuickSettingsConfig.unpinNode(config,"main","generator","app:player","gain");
        Map<String,Object> hidden=QuickSettingsConfig.findPin(config,"main","generator","app:player","gain");
        assertEquals(false,hidden.get("pinned"));
        assertEquals("Громкость проигрывателя",hidden.get("display_name"));
        assertEquals(false,hidden.get("expanded"));
        assertFalse(Boolean.FALSE.equals(QuickSettingsConfig.findPin(config,"main","generator","app:player","").get("pinned")));
        assertFalse(Boolean.FALSE.equals(QuickSettingsConfig.findPin(config,"main","generator","app:player","source").get("pinned")));

        QuickSettingsConfig.pinNode(config,"main","generator","app:player","gain","applications",1,"EXPANDED");
        assertFalse(hidden.containsKey("pinned"));
        assertEquals("Громкость проигрывателя",hidden.get("display_name"));
    }

    @Test void movingGroupsProducesAnUnambiguousContiguousOrder() {
        Map<String,Object> config=DefaultConfig.create();
        QuickSettingsConfig.moveGroup(config,"events",0);
        assertEquals(List.of("events","applications","outputs","routing"),
                QuickSettingsConfig.groups(config).stream().map(group->group.get("id")).toList());
        QuickSettingsConfig.updateGroup(config,"outputs","Мои устройства",0,true);
        assertEquals(List.of("outputs","events","applications","routing"),
                QuickSettingsConfig.groups(config).stream().map(group->group.get("id")).toList());
        assertEquals(List.of(0,1,2,3),QuickSettingsConfig.groups(config).stream().map(group->group.get("order")).toList());
        assertTrue(Boolean.TRUE.equals(QuickSettingsConfig.groups(config).getFirst().get("collapsed")));
    }

    @Test void aSavedCompositeCanBePinnedWithoutPretendingToBeAGraphNode() {
        Map<String,Object> config=DefaultConfig.create();
        QuickSettingsConfig.pins(config).clear();
        var graph=new io.github.ilyaandreev05.audiomatrix.graph.AudioGraph("composite-test","Тест",
                List.of(new io.github.ilyaandreev05.audiomatrix.graph.GainNode("first","Первый",List.of("FL","FR"),1,false),
                        new io.github.ilyaandreev05.audiomatrix.graph.GainNode("second","Второй",List.of("FL","FR"),1,false)),List.of());
        var composite=io.github.ilyaandreev05.audiomatrix.graph.GraphSubgraphLibrary.create(config,graph,"Составной блок",List.of("first","second"));
        QuickSettingsConfig.pinNode(config,graph.id(),composite.id(),"routing",0,"EXPANDED");
        List<String> errors=new ArrayList<>();
        ConfigPinnedNodeRules.validate(config,Map.of(graph.id(),graph),List.of(),errors);
        assertEquals(List.of(),errors);
    }
}
