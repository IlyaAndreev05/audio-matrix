package io.github.ilyaandreev05.audiomatrix.gnome;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.QuickSettingsConfig;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.OutputNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GnomeQuickSettingsGeneratedPins {
    private GnomeQuickSettingsGeneratedPins() {}

    static Map<String, Object> savedPin(Map<String, Object> pin, String routeId, AudioGraph graph,
                                        PipeWireBackend.Snapshot snapshot, Map<String, Object> config,
                                        List<Map<String, Object>> applications, List<Map<String, Object>> outputs,
                                        Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> instances) {
        String generatorId = String.valueOf(pin.getOrDefault("node_id", ""));
        String instanceKey = String.valueOf(pin.getOrDefault("instance_key", ""));
        DynamicInstanceRegistry.RuntimeInstance instance = instances.get(
                new DynamicInstanceRegistry.InstanceAddress(generatorId, instanceKey));
        DynamicGeneratorNode generator = generator(graph, generatorId);
        if (instance == null || generator == null) return null;
        return state(pin, generator, instance, routeId, graph, snapshot, config, applications, outputs);
    }

    static void appendAutoPins(List<Map<String, Object>> result, Map<String, Object> config, String routeId,
                               AudioGraph graph, PipeWireBackend.Snapshot snapshot,
                               List<Map<String, Object>> applications, List<Map<String, Object>> outputs,
                               Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> instances) {
        if (graph == null || !routeId.equals(graph.id())) return;
        List<DynamicInstanceRegistry.RuntimeInstance> ordered = instances.values().stream()
                .filter(instance -> routeId.equals(graph.id()))
                .sorted(Comparator.comparing(DynamicInstanceRegistry.RuntimeInstance::generatorId)
                        .thenComparing(DynamicInstanceRegistry.RuntimeInstance::instanceKey)).toList();
        for (DynamicInstanceRegistry.RuntimeInstance instance : ordered) {
            DynamicGeneratorNode generator = generator(graph, instance.generatorId());
            if (generator == null || !generator.autoPin()) continue;
            if (Boolean.FALSE.equals(instance.overrides().get("pinned"))) continue;
            if (QuickSettingsConfig.pins(config).stream().anyMatch(pin -> routeId.equals(pin.get("route_id"))
                    && instance.generatorId().equals(pin.get("node_id"))
                    && instance.instanceKey().equals(pin.get("instance_key")))) continue;
            Map<String, Object> automatic = new LinkedHashMap<>();
            automatic.put("id", "generated-pin:" + instance.generatorId() + ":" + instance.instanceKey());
            automatic.put("route_id", routeId);
            automatic.put("node_id", instance.generatorId());
            automatic.put("instance_key", instance.instanceKey());
            automatic.put("group_id", instance.overrides().getOrDefault("group_id", generator.groupId()));
            automatic.put("order", instance.overrides().getOrDefault("order", nextOrder(config, result,
                    String.valueOf(automatic.get("group_id")))));
            automatic.put("view", Boolean.FALSE.equals(instance.overrides().get("expanded")) ? "COMPACT" : "EXPANDED");
            Map<String, Object> published = state(automatic, generator, instance, routeId, graph,
                    snapshot, config, applications, outputs);
            if (published != null) result.add(published);
        }
    }

    private static Map<String, Object> state(Map<String, Object> pin, DynamicGeneratorNode generator,
                                              DynamicInstanceRegistry.RuntimeInstance instance, String routeId,
                                              AudioGraph graph, PipeWireBackend.Snapshot snapshot,
                                              Map<String, Object> config, List<Map<String, Object>> applications,
                                              List<Map<String, Object>> outputs) {
        boolean showOffline = Boolean.TRUE.equals(pin.get("show_when_offline"))
                || Boolean.TRUE.equals(instance.overrides().get("show_when_offline"));
        boolean hapticOutput = "outputs".equals(generator.registry())
                && "haptic_output".equals(instance.runtimeBinding().get("role"));
        boolean controllable = hapticOutput ? instance.available() && instance.bindingAvailable()
                : instance.gainControlAvailable();
        if (!controllable && !showOffline) return null;
        GainNode gain = generatedGain(graph, instance);
        if (gain == null) return null;
        String displayName = displayName(pin, instance, applications);
        String groupId = String.valueOf(pin.getOrDefault("group_id", defaultGroup(generator.registry())));
        final String requestedGroup = groupId;
        if (QuickSettingsConfig.groups(config).stream().noneMatch(group -> requestedGroup.equals(group.get("id"))))
            groupId = defaultGroup(generator.registry());
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", pin.get("id"));
        state.put("routeId", routeId);
        state.put("nodeId", generator.id());
        state.put("instanceKey", instance.instanceKey());
        state.put("registry", generator.registry());
        state.put("name", displayName);
        state.put("nodeType", "dynamic_generator");
        state.put("groupId", groupId);
        state.put("order", pin.getOrDefault("order", 0));
        state.put("view", pin.getOrDefault("view", "EXPANDED"));
        state.put("pinned", true);
        state.put("available", controllable && instance.available());
        state.put("showWhenOffline", showOffline);
        state.put("showChildren", Boolean.TRUE.equals(pin.get("show_children"))
                || Boolean.TRUE.equals(instance.overrides().get("show_children")));
        Object icon = pin.get("icon");
        if (icon instanceof String text && !text.isBlank()) state.put("icon", text);
        if (hapticOutput) {
            Map<String, Object> sharedGain = GnomeQuickSettingsHapticGain.targetState(config);
            if (sharedGain == null) return null;
            state.put("sharedGain", sharedGain);
        } else {
            state.put("gain", Map.of("routeId", routeId, "nodeId", gain.id(),
                    "volume", gain.gain(), "muted", gain.muted()));
        }
        state.put("applications", applicationGroup(generator, instance, applications, controllable));
        Map<String, Object> tab = browserTab(instance, applications);
        if (tab != null) state.put("browserTabs", List.of(tab));
        if ("outputs".equals(generator.registry())) {
            GraphNode output = graph.nodes().stream().filter(node -> node.id().equals(instance.nodeIds().get("output")))
                    .findFirst().orElse(null);
            if (output instanceof OutputNode outputNode)
                state.put("output", GnomeQuickSettingsPinDetails.outputFor(outputNode, graph,
                        snapshot, config, outputs));
        }
        return state;
    }

    private static String displayName(Map<String, Object> pin,
                                      DynamicInstanceRegistry.RuntimeInstance instance,
                                      List<Map<String, Object>> applications) {
        Object configured = pin.get("display_name");
        if (configured instanceof String text && !text.isBlank()) return text;
        Object override = instance.overrides().get("display_name");
        if (override instanceof String text && !text.isBlank()) return text;
        Map<String, Object> app = applicationGroupFor(instance, applications);
        if (app != null && app.get("name") instanceof String name && !name.isBlank()) return name;
        Map<String, Object> tab = browserTab(instance, applications);
        if (tab != null && tab.get("title") instanceof String title && !title.isBlank()) return title;
        return generatedGainName(instance);
    }

    private static String generatedGainName(DynamicInstanceRegistry.RuntimeInstance instance) {
        Object name = instance.runtimeBinding().get("applicationName");
        if (name instanceof String value && !value.isBlank()) return value;
        Object process = instance.runtimeBinding().get("processName");
        if (process instanceof String value && !value.isBlank()) return value;
        return instance.instanceKey();
    }

    private static List<Map<String, Object>> applicationGroup(DynamicGeneratorNode generator,
                                                               DynamicInstanceRegistry.RuntimeInstance instance,
                                                               List<Map<String, Object>> applications,
                                                               boolean controllable) {
        if ("browser_tabs".equals(generator.registry())) {
            Map<String, Object> group = applicationGroupFor(instance, applications);
            return group == null ? List.of() : List.of(group);
        }
        return List.of();
    }

    private static Map<String, Object> applicationGroupFor(DynamicInstanceRegistry.RuntimeInstance instance,
                                                           List<Map<String, Object>> applications) {
        Map<String, Object> binding = instance.runtimeBinding();
        String applicationId = text(binding.get("applicationId"));
        String process = text(binding.get("processName"));
        String browser = text(binding.get("browser"));
        return applications.stream().filter(app -> {
            if (!applicationId.isBlank() && applicationId.equals(text(app.get("application_id")))) return true;
            if (!process.isBlank() && process.equals(text(app.get("process")))) return true;
            if (browser.isBlank()) return false;
            Object raw = app.get("browserAdapter");
            return raw instanceof Map<?, ?> adapter && browser.equals(text(adapter.get("browser")));
        }).findFirst().orElse(null);
    }

    private static Map<String, Object> browserTab(DynamicInstanceRegistry.RuntimeInstance instance,
                                                   List<Map<String, Object>> applications) {
        Map<String, Object> binding = instance.runtimeBinding();
        String sessionToken = text(binding.get("sessionToken"));
        String browser = text(binding.get("browser"));
        String instanceId = text(binding.get("instanceId"));
        String profileId = text(binding.get("profileId"));
        if (sessionToken.isBlank() || browser.isBlank() || instanceId.isBlank() || profileId.isBlank()) return null;
        for (Map<String, Object> app : applications) {
            for (Map<String, Object> tab : maps(app.get("browserTabs"))) {
                if (browser.equals(text(tab.get("browser"))) && instanceId.equals(text(tab.get("instanceId")))
                        && profileId.equals(text(tab.get("profileId")))
                        && sessionToken.equals(text(tab.get("sessionToken")))) return tab;
            }
        }
        return null;
    }

    private static DynamicGeneratorNode generator(AudioGraph graph, String generatorId) {
        if (graph == null) return null;
        return graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).filter(node -> node.id().equals(generatorId))
                .findFirst().orElse(null);
    }

    private static GainNode generatedGain(AudioGraph graph, DynamicInstanceRegistry.RuntimeInstance instance) {
        String id = instance.nodeIds().get("gain");
        return graph.nodes().stream().filter(GainNode.class::isInstance).map(GainNode.class::cast)
                .filter(node -> node.id().equals(id)).findFirst().orElse(null);
    }

    private static int nextOrder(Map<String, Object> config, List<Map<String, Object>> current, String groupId) {
        int saved = QuickSettingsConfig.pins(config).stream().filter(pin -> groupId.equals(pin.get("group_id")))
                .mapToInt(pin -> number(pin.get("order"))).max().orElse(-1);
        int published = current.stream().filter(pin -> groupId.equals(pin.get("groupId")))
                .mapToInt(pin -> number(pin.get("order"))).max().orElse(-1);
        return Math.max(saved, published) + 1;
    }

    private static String defaultGroup(String registry) {
        return "outputs".equals(registry) ? "outputs" : "applications";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
