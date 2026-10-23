package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.gnome.BrowserAdapter;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds browser-tab observations and applies the configured activity/personal-pin policy. */
final class DynamicBrowserTabSnapshots {
    private DynamicBrowserTabSnapshots() {}

    static List<DynamicInstanceRegistry.InstanceSnapshot> collect(String routeId,Map<String,Object> config,
            DynamicGeneratorNode generator,PipeWireBackend.Snapshot snapshot,List<BrowserAdapter> adapters,
            Map<String,String> ruleIds) {
        List<Map<String,Object>> pins=QuickSettingsConfig.pins(config);
        List<DynamicInstanceRegistry.InstanceSnapshot> result=new ArrayList<>();
        for(DynamicInstanceRegistry.InstanceSnapshot tab:observed(snapshot,adapters,ruleIds)) {
            boolean individual=pins.stream().anyMatch(pin->routeId.equals(pin.get("route_id"))
                    &&generator.id().equals(pin.get("node_id"))&&tab.stableKey().equals(pin.get("instance_key"))
                    &&!Boolean.FALSE.equals(pin.get("pinned")));
            if(generator.instances().get(tab.stableKey()) instanceof Map<?,?> record
                    &&record.get("overrides") instanceof Map<?,?> overrides
                    &&Boolean.TRUE.equals(overrides.get("pinned")))individual=true;
            Map<String,Object> binding=tab.runtimeBinding();
            if(generator.acceptsBrowserTabActivity(Boolean.TRUE.equals(binding.get("audible")),
                    Boolean.TRUE.equals(binding.get("muted")),"captured".equals(binding.get("volumeCapability")),individual)) {
                result.add(tab);
            }
        }
        return result;
    }

    private static List<DynamicInstanceRegistry.InstanceSnapshot> observed(PipeWireBackend.Snapshot snapshot,
            List<BrowserAdapter> adapters,Map<String,String> ruleIds) {
        List<DynamicInstanceRegistry.InstanceSnapshot> result=new ArrayList<>();
        List<AudioNode> parents=snapshot.streams().stream().filter(stream->!DynamicRouteRuntime.excluded(stream)).toList();
        for(BrowserAdapter adapter:adapters)for(BrowserAdapter.BrowserSnapshot browser:adapter.snapshots()) {
            if(!adapter.id().equals(browser.browser()))continue;
            AudioNode parent=parents.stream().filter(stream->(!stream.applicationId().isBlank()||!stream.processName().isBlank())
                    &&adapter.matches(stream.applicationId(),stream.processName(),stream.applicationName())).findFirst().orElse(null);
            String ruleId=parent==null?"":ruleIds.getOrDefault(DynamicRouteRuntime.applicationKey(parent),"");
            for(BrowserAdapter.BrowserTab tab:browser.tabs()) {
                result.add(DynamicInstanceRegistry.browserTab(adapter.id(),browser.instanceId(),browser.profileId(),
                        tab.sessionToken(),tab.tabId(),tab.title(),tab.audible(),tab.muted(),tab.volumeCapability(),tab.captureStreamKey(),
                        ruleId,parent==null?"":text(parent.applicationId()),parent==null?"":text(parent.processName())));
            }
        }
        return result;
    }

    private static String text(Object value) {
        return value instanceof String text?text:"";
    }
}
