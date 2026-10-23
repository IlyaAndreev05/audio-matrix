package io.github.ilyaandreev05.audiomatrix.graph;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DynamicBrowserTabRegistryTest {
    private static final List<String> STEREO=List.of("FL","FR");
    @Test void explicitFalseOverridesRemainPresentAndApplicationGainCannotBeDuplicated() {
        DynamicGeneratorNode generator = generator("tabs", "browser:*", "browser_tabs", "default-tab", Map.of());
        AudioGraph graph = new AudioGraph("route-tabs", "Tabs", List.of(generator), List.of());
        DynamicInstanceRegistry.InstanceSnapshot tab = DynamicInstanceRegistry.browserTab("chrome", "profile-instance",
                "Default", "session-token", 99, "Documentation", true, false, "captured", "capture-key",
                "app-rule", "org.example.browser", "browser");
        DynamicInstanceRegistry.ReconcileResult reconciled = DynamicInstanceRegistry.reconcile(graph,
                Map.of("tabs", List.of(tab)));
        Map<String, Object> route = new LinkedHashMap<>(GraphCodec.toMap(reconciled.configuredGraph()));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("route_library", new ArrayList<>(List.of(route)));

        assertTrue(DynamicInstanceRegistry.setInstanceOverride(config, "route-tabs", "tabs",
                tab.stableKey(), "muted", false));
        assertEquals(false, DynamicInstanceRegistry.instanceOverride(config, "route-tabs", "tabs",
                tab.stableKey(), "muted").orElseThrow());
        assertFalse(DynamicInstanceRegistry.setInstanceOverride(config, "route-tabs", "tabs",
                tab.stableKey(), "muted", false));
        assertEquals(new DynamicInstanceRegistry.GainState(1.0, false),
                DynamicInstanceRegistry.instanceGainBaseline(config, "route-tabs", "tabs"));
        assertThrows(IllegalArgumentException.class, () -> DynamicInstanceRegistry.setInstanceOverride(config,
                "route-tabs", "tabs", tab.stableKey(), "gain", 1.1));
        assertTrue(DynamicInstanceRegistry.generatedGain(reconciled, "tabs", tab.stableKey()).id()
                .equals(reconciled.instances().get(new DynamicInstanceRegistry.InstanceAddress("tabs", tab.stableKey()))
                        .nodeIds().get("gain")));
    }
    @Test void browserTabsNestUnderAnOfflineLogicalParentWithoutInventingPcm() {
        DynamicGeneratorNode apps = generator("apps", "application:unassigned", "applications",
                "default-application", Map.of());
        DynamicGeneratorNode tabs = generator("tabs", "browser:*", "browser_tabs", "default-tab", Map.of());
        AudioGraph graph = new AudioGraph("route-browser", "Browser", List.of(apps, tabs), List.of());
        DynamicInstanceRegistry.InstanceSnapshot parent =
                DynamicInstanceRegistry.browserParentApplication("chrome", "", "", "Chrome");
        DynamicInstanceRegistry.InstanceSnapshot tab = DynamicInstanceRegistry.browserTab("chrome", "instance",
                "Default", "session-1", 12, "Docs", true, false, "captured", "capture-1");
        String parentComposite = DynamicInstanceRegistry.stableCompositeId("apps", "browser-parent:chrome");
        

        DynamicInstanceRegistry.ReconcileResult result = DynamicInstanceRegistry.reconcile(graph,
                Map.of("apps", List.of(parent), "tabs", List.of(tab)), Map.of("tabs", parentComposite), null);
        DynamicInstanceRegistry.RuntimeInstance parentState = result.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("apps", "browser-parent:chrome"));
        DynamicInstanceRegistry.RuntimeInstance tabState = result.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("tabs", tab.stableKey()));
        GraphSubgraph tabComposite = result.generatedSubgraphs().stream()
                .filter(group -> group.id().equals(tabState.compositeId())).findFirst().orElseThrow();

        assertFalse(parentState.available());
        assertFalse(parentState.bindingAvailable());
        assertTrue(parentState.runtimeBinding().get("streams") instanceof List<?> streams && streams.isEmpty());
        assertTrue(tabState.available());
        assertTrue(tabState.bindingAvailable());
        assertFalse(tabState.controlAvailable());
        assertEquals(parentComposite, tabComposite.parentId());
        assertEquals("tabs", tabComposite.generatorId());
    }
    @Test void browserTabGainAndExplicitFalseMuteSurviveReconciliation() {
        DynamicGeneratorNode tabs=generator("tabs","browser:*","browser_tabs","default-tab",Map.of());
        DynamicInstanceRegistry.InstanceSnapshot tab=DynamicInstanceRegistry.browserTab("chrome","instance","profile",
                "tab-session",5,"Музыка",true,false,"captured","stream-key");
        Map<String,Object> record=new LinkedHashMap<>(DynamicInstanceRegistry.seedInstanceRecord("tabs",tab.stableKey(),List.of("source","gain")));
        record.put("overrides",new LinkedHashMap<>(Map.of("gain",.31,"muted",true)));
        tabs=tabs.withInstances(Map.of(tab.stableKey(),record));
        AudioGraph graph=new AudioGraph("tab-gain","Вкладки",List.of(tabs),List.of());
        DynamicInstanceRegistry.ReconcileResult first=DynamicInstanceRegistry.reconcile(graph,Map.of("tabs",List.of(tab)));
        assertEquals(.31,DynamicInstanceRegistry.generatedGain(first,"tabs",tab.stableKey()).gain(),.0001);
        assertTrue(DynamicInstanceRegistry.generatedGain(first,"tabs",tab.stableKey()).muted());

        Map<String,Object> route=GraphCodec.toMap(first.configuredGraph());
        Map<String,Object> config=new LinkedHashMap<>(Map.of("route_library",new ArrayList<>(List.of(route))));
        DynamicInstanceRegistry.setInstanceOverride(config,"tab-gain","tabs",tab.stableKey(),"gain",.72);
        DynamicInstanceRegistry.setInstanceOverride(config,"tab-gain","tabs",tab.stableKey(),"muted",false);
        DynamicInstanceRegistry.ReconcileResult next=DynamicInstanceRegistry.reconcile(GraphCodec.fromMap(route),Map.of("tabs",List.of(tab)));
        assertEquals(.72,DynamicInstanceRegistry.generatedGain(next,"tabs",tab.stableKey()).gain(),.0001);
        assertFalse(DynamicInstanceRegistry.generatedGain(next,"tabs",tab.stableKey()).muted());
    }
    @Test void activeTabPolicyKeepsMuteCaptureAndIndividualOverridesHonest() {
        DynamicGeneratorNode tabs=generator("tabs","browser:*","browser_tabs","default-tab",Map.of());
        assertFalse(tabs.acceptsBrowserTabActivity(false,false,false,false));
        assertTrue(tabs.acceptsBrowserTabActivity(true,false,false,false));
        assertTrue(tabs.acceptsBrowserTabActivity(false,true,false,false));
        assertTrue(tabs.acceptsBrowserTabActivity(false,false,true,false));
        assertTrue(tabs.acceptsBrowserTabActivity(false,false,false,true));
        assertTrue(tabs.withParameter("only_audio_active_tabs",false).acceptsBrowserTabActivity(false,false,false,false));
    }

    private static DynamicGeneratorNode generator(String id,String selector,String registry,String template,
                                                   Map<String,Object> connectTo) {
        Map<String,Object> parameters=new LinkedHashMap<>();
        parameters.put("registry",registry);
        parameters.put("template",template);
        parameters.put("connect_to",connectTo);
        parameters.put("instances",Map.of());
        return new DynamicGeneratorNode(id,"Generator "+id,selector,STEREO,parameters);
    }
}
