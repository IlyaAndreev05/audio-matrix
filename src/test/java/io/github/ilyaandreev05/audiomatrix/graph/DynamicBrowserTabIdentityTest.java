package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DynamicBrowserTabIdentityTest {
    private static final List<String> STEREO = List.of("FL", "FR");

    @Test void sessionTokenKeepsTabNodeIdsStableAcrossTabIdChangesAndReappearance() {
        DynamicGeneratorNode tabs = new DynamicGeneratorNode("tabs", "Chrome tabs", "browser:chrome", STEREO,
                Map.of("registry", "browser_tabs", "template", "default-tab"));
        AudioGraph graph = new AudioGraph("route-tabs", "Browser tabs", List.of(tabs), List.of());
        DynamicInstanceRegistry.InstanceSnapshot firstTab = DynamicInstanceRegistry.browserTab("chrome", "browser-1",
                "Default", "session-token", 12, "Documentation", true, false, "metadata", "");
        DynamicInstanceRegistry.InstanceSnapshot sameTabAfterNavigation = DynamicInstanceRegistry.browserTab("chrome",
                "browser-1", "Default", "session-token", 93, "Reference", true, false, "metadata", "");
        assertEquals(firstTab.stableKey(), sameTabAfterNavigation.stableKey());

        DynamicInstanceRegistry.ReconcileResult first = DynamicInstanceRegistry.reconcile(graph,
                Map.of("tabs", List.of(firstTab)));
        DynamicInstanceRegistry.RuntimeInstance before = first.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("tabs", firstTab.stableKey()));
        String sourceId = before.nodeIds().get("source");
        String gainId = before.nodeIds().get("gain");
        DynamicInstanceRegistry.ReconcileResult missing = DynamicInstanceRegistry.reconcile(first.configuredGraph(), Map.of());
        DynamicInstanceRegistry.RuntimeInstance offline = missing.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("tabs", firstTab.stableKey()));
        assertFalse(offline.available());
        assertEquals(sourceId, offline.nodeIds().get("source"));
        assertEquals(gainId, offline.nodeIds().get("gain"));

        DynamicInstanceRegistry.ReconcileResult returned = DynamicInstanceRegistry.reconcile(missing.configuredGraph(),
                Map.of("tabs", List.of(sameTabAfterNavigation)));
        DynamicInstanceRegistry.RuntimeInstance after = returned.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("tabs", sameTabAfterNavigation.stableKey()));
        assertFalse(returned.configChanged());
        assertTrue(after.available());
        assertEquals(sourceId, after.nodeIds().get("source"));
        assertEquals(gainId, after.nodeIds().get("gain"));
        assertEquals(93, after.runtimeBinding().get("tabId"));
    }
    @Test void applicationIdentitySurvivesTransientPipeWireIdReplacementAndReappearance() {
        DynamicGeneratorNode applications = new DynamicGeneratorNode("apps", "Applications",
                "application:unassigned", STEREO);
        AudioGraph graph = new AudioGraph("route-app", "Application identity", List.of(applications), List.of());
        DynamicInstanceRegistry.InstanceSnapshot firstStream = DynamicInstanceRegistry.application(
                stream(7, "org.example.music", "music", "Music"));
        DynamicInstanceRegistry.InstanceSnapshot reconnectedStream = DynamicInstanceRegistry.application(
                stream(91, "org.example.music", "music", "Music"));
        assertEquals(firstStream.stableKey(), reconnectedStream.stableKey());

        DynamicInstanceRegistry.ReconcileResult first = DynamicInstanceRegistry.reconcile(graph,
                Map.of("apps", List.of(firstStream)));
        DynamicInstanceRegistry.RuntimeInstance before = first.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("apps", firstStream.stableKey()));
        DynamicInstanceRegistry.ReconcileResult missing = DynamicInstanceRegistry.reconcile(first.configuredGraph(), Map.of());
        DynamicInstanceRegistry.RuntimeInstance offline = missing.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("apps", firstStream.stableKey()));
        DynamicInstanceRegistry.ReconcileResult returned = DynamicInstanceRegistry.reconcile(missing.configuredGraph(),
                Map.of("apps", List.of(reconnectedStream)));
        DynamicInstanceRegistry.RuntimeInstance after = returned.instances().get(
                new DynamicInstanceRegistry.InstanceAddress("apps", reconnectedStream.stableKey()));

        assertFalse(offline.available());
        assertEquals(before.nodeIds(), offline.nodeIds());
        assertEquals(before.nodeIds(), after.nodeIds());
        assertTrue(after.available());
        assertEquals(91, ((Map<?, ?>) ((List<?>) after.runtimeBinding().get("streams")).getFirst()).get("nodeId"));
    }

    private static AudioNode stream(int id, String appId, String process, String name) {
        return new AudioNode(id, "stream-" + id, name, "Stream/Output/Audio", "running", name, process,
                appId, 2, List.of(48_000), 1.0, false, null);
    }
}
