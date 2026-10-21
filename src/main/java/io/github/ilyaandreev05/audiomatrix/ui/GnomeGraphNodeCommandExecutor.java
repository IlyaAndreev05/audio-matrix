package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.graph.AudioGraph;
import io.github.ilyaandreev05.audiomatrix.graph.DeviceSlotNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicGeneratorNode;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;
import io.github.ilyaandreev05.audiomatrix.graph.GainNode;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphNode;
import io.github.ilyaandreev05.audiomatrix.graph.SourceNode;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeOutputState;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/** Resolves API v5 graph targets to canonical saved or live instance controls. */
final class GnomeGraphNodeCommandExecutor {
    private GnomeGraphNodeCommandExecutor() {}

    private enum Kind { GRAPH_GAIN, SAVED_DEVICE, GENERATED_APPLICATION, GENERATED_DEVICE, GENERATED_TAB }
    private record ResolvedTarget(Kind kind, String routeId, String nodeId, String instanceKey,
                                  GraphNode node, DynamicInstanceRegistry.RuntimeInstance instance,
                                  GainNode gain, Map<String, Object> runtimeBinding, String bindingId,
                                  String applicationSelector) {}
    record PropertyValue(String targetIdentity, Object value) {}

    static Object propertyValue(Map<String, Object> config, GnomeNodeCommandHandler.PropertyKey key,
            DynamicInstanceRegistry.ReconcileResult registry, PipeWireBackend.Snapshot snapshot,
            List<AudioNode> sinks) {
        ResolvedTarget target = resolve(config, target(key), key.property(), registry, snapshot, sinks);
        String property = key.property();
        Object value;
        String identity;
        switch (target.kind()) {
            case GRAPH_GAIN -> {
                value = GraphNodeCommand.propertyValue(config, key.routeId(), key.nodeId(), property);
                identity = "graph:" + key.routeId() + ":" + key.nodeId();
            }
            case SAVED_DEVICE -> {
                value = GraphNodeCommand.propertyValue(config, key.routeId(), key.nodeId(), property);
                identity = "device:" + target.bindingId();
            }
            case GENERATED_APPLICATION -> {
                Map<String, Object> rule = applicationRule(config, target.applicationSelector(),
                        target.runtimeBinding());
                value = rule.getOrDefault("volume".equals(property) ? "volume" : "mute",
                        "volume".equals(property) ? 1.0 : false);
                identity = "application:" + rule.getOrDefault("id", "");
            }
            case GENERATED_DEVICE -> {
                Map<String, Object> binding = GraphNodeCommand.deviceBinding(config, target.bindingId());
                value = binding.getOrDefault("volume".equals(property) ? "volume" : "mute",
                        "volume".equals(property) ? 1.0 : false);
                identity = "device:" + target.bindingId();
            }
            case GENERATED_TAB -> {
                String field = "volume".equals(property) ? "gain" : "muted";
                Optional<Object> override = DynamicInstanceRegistry.instanceOverride(config,
                        key.routeId(), key.nodeId(), key.instanceKey(), field);
                value = override.orElseGet(() -> "volume".equals(property)
                        ? DynamicInstanceRegistry.instanceGainBaseline(config, key.routeId(), key.nodeId())
                        : DynamicInstanceRegistry.instanceMutedBaseline(config, key.routeId(), key.nodeId()));
                identity = "tab:" + key.routeId() + ":" + key.nodeId() + ":" + key.instanceKey()
                        + ":" + target.gain().id();
            }
            default -> throw new IllegalStateException("Unsupported node target");
        }
        return new PropertyValue(identity, value);
    }
    static Object propertyAfter(Object current, Object intended) {
        if (!(current instanceof PropertyValue property))
            throw new IllegalArgumentException("Resolved node property is unavailable");
        return new PropertyValue(property.targetIdentity(), intended);
    }

    static CompletableFuture<Void> execute(GnomeNodeCommandHandler.Payload payload, ConfigStore store,
            PipeWireBackend backend, PipeWireBackend.Snapshot snapshot, List<AudioNode> sinks,
            DynamicInstanceRegistry.ReconcileResult registry, boolean graphRuntimeActive,
            Consumer<Map<String, Object>> propertyGuard,
            Function<Map<String, Object>, CompletableFuture<?>> applyGraphRuntime,
            Function<Map<String, Object>, CompletableFuture<?>> rollbackGraphRuntime,
            Function<Map<String, Object>, CompletableFuture<?>> applyApplicationRuntime,
            Function<Map<String, Object>, CompletableFuture<?>> rollbackApplicationRuntime,
            Consumer<Throwable> saveFailed, Runnable saved) {
        if (payload instanceof GnomeNodeCommandHandler.SetRouteEnabled route) {
            Consumer<Map<String, Object>> change = config -> GraphNodeCommand.setRouteEnabled(config,
                    route.routeId(), route.switchNodeId(), route.linkId(), route.enabled());
            return GnomeNodeCommandHandler.applyRuntimeThenStore(store, change, graphRuntimeActive,
                    applyGraphRuntime, rollbackGraphRuntime, saveFailed, saved);
        }
        GnomeNodeCommandHandler.Target requested;
        String property;
        Object value;
        if (payload instanceof GnomeNodeCommandHandler.SetVolume volume) {
            requested = volume.target();
            property = "volume";
            value = volume.value();
        } else if (payload instanceof GnomeNodeCommandHandler.SetMuted muted) {
            requested = muted.target();
            property = "muted";
            value = muted.muted();
        } else {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported graph command"));
        }

        Map<String, Object> current = store.snapshot();
        ResolvedTarget target;
        try { target = resolve(current, requested, property, registry, snapshot, sinks); }
        catch (RuntimeException stale) { return CompletableFuture.failedFuture(stale); }

        Consumer<Map<String, Object>> change;
        Function<Map<String, Object>, CompletableFuture<?>> applyRuntime;
        Function<Map<String, Object>, CompletableFuture<?>> applyRollback;
        boolean runtimeActive;
        switch (target.kind()) {
            case GRAPH_GAIN -> {
                change = config -> {
                    if ("volume".equals(property))
                        GraphNodeCommand.setVolume(config, requested.routeId(), requested.nodeId(), (double) value);
                    else GraphNodeCommand.setMuted(config, requested.routeId(), requested.nodeId(), (boolean) value);
                };
                applyRuntime = applyGraphRuntime;
                runtimeActive = graphRuntimeActive;
                applyRollback = rollbackGraphRuntime;
            }
            case SAVED_DEVICE -> {
                change = config -> GraphNodeCommand.setDeviceBindingLevel(config, requested.routeId(),
                        requested.nodeId(), "volume".equals(property) ? (double) value : null,
                        "muted".equals(property) ? (boolean) value : null);
                AudioNode output;
                try { output = resolveSavedOutput(target, current, snapshot, sinks); }
                catch (RuntimeException stale) { return CompletableFuture.failedFuture(stale); }
                runtimeActive = snapshot.connected();
                if (runtimeActive && output == null)
                    return CompletableFuture.failedFuture(new IllegalStateException("Bound output is unavailable"));
                applyRuntime = candidate -> output == null ? CompletableFuture.completedFuture(null)
                        : applyDeviceLevel(backend, output.id(), candidate, target.bindingId(), property);
                applyRollback = applyRuntime;
            }
            case GENERATED_APPLICATION -> {
                change = config -> {
                    Map<String, Object> rule = applicationRule(config, target.applicationSelector(),
                            target.runtimeBinding());
                    rule.put("volume".equals(property) ? "volume" : "mute", value);
                };
                applyRuntime = candidate -> applyApplicationRuntime.apply(
                        onlyApplicationRule(candidate, target.applicationSelector(), target.runtimeBinding()));
                runtimeActive = true;
                applyRollback = candidate -> rollbackApplicationRuntime.apply(
                        onlyApplicationRule(candidate, target.applicationSelector(), target.runtimeBinding()));
            }
            case GENERATED_DEVICE -> {
                change = config -> GraphNodeCommand.deviceBinding(config, target.bindingId())
                        .put("volume".equals(property) ? "volume" : "mute", value);
                AudioNode output;
                try { output = resolveGeneratedOutput(target, current, snapshot, sinks); }
                catch (RuntimeException stale) { return CompletableFuture.failedFuture(stale); }
                if (output == null) return CompletableFuture.failedFuture(
                        new IllegalStateException("Generated output instance is unavailable"));
                runtimeActive = true;
                applyRuntime = candidate -> applyDeviceLevel(backend, output.id(), candidate,
                        target.bindingId(), property);
                applyRollback = applyRuntime;
            }
            case GENERATED_TAB -> {
                String field = "volume".equals(property) ? "gain" : "muted";
                change = config -> DynamicInstanceRegistry.setInstanceOverride(config,
                        requested.routeId(), requested.nodeId(), requested.instanceKey(), field, value);
                if (!graphRuntimeActive || !snapshot.connected())
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Generated tab route is not active"));
                applyRuntime = applyGraphRuntime;
                runtimeActive = true;
                applyRollback = rollbackGraphRuntime;
            }
            default -> {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported node target"));
            }
        }
        Consumer<Map<String, Object>> update = change;
        change = config -> {
            if (propertyGuard != null) propertyGuard.accept(config);
            update.accept(config);
        };
        return GnomeNodeCommandHandler.applyRuntimeThenStore(store, change, runtimeActive,
                applyRuntime, applyRollback, saveFailed, saved);
    }

    private static GnomeNodeCommandHandler.Target target(GnomeNodeCommandHandler.PropertyKey key) {
        return new GnomeNodeCommandHandler.Target(key.routeId(), key.nodeId(), key.instanceKey());
    }

    private static ResolvedTarget resolve(Map<String, Object> config, GnomeNodeCommandHandler.Target target,
            String property, DynamicInstanceRegistry.ReconcileResult registry, PipeWireBackend.Snapshot snapshot,
            List<AudioNode> sinks) {
        AudioGraph graph = GraphCodec.fromMap(GraphUiRoutes.route(config, target.routeId()));
        if (target.instanceKey().isEmpty()) {
            GraphNode node = graph.nodes().stream().filter(item -> item.id().equals(target.nodeId())
                            && (item instanceof GainNode || item instanceof DeviceSlotNode))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Volume node no longer exists"));
            if (node instanceof GainNode)
                return new ResolvedTarget(Kind.GRAPH_GAIN, target.routeId(), target.nodeId(), "",
                        node, null, (GainNode) node, Map.of(), "", "");
            DeviceSlotNode slot = (DeviceSlotNode) node;
            String bindingId = deviceId(slot.deviceId());
            GraphNodeCommand.deviceBinding(config, bindingId);
            return new ResolvedTarget(Kind.SAVED_DEVICE, target.routeId(), target.nodeId(), "",
                    node, null, null, Map.of(), bindingId, "");
        }

        if (!(graph.nodes().stream().filter(node -> node.id().equals(target.nodeId())).findFirst()
                .orElse(null) instanceof DynamicGeneratorNode))
            throw new IllegalArgumentException("Generated node no longer exists");
        if (registry == null || !graph.id().equals(registry.configuredGraph().id()))
            throw new IllegalArgumentException("Generated target registry is unavailable or stale");
        DynamicInstanceRegistry.RuntimeInstance instance = DynamicInstanceRegistry.requireInstance(
                registry, target.nodeId(), target.instanceKey(), true);
        if (!instance.bindingAvailable())
            throw new IllegalArgumentException("Generated target has no live runtime binding");
        if ("volume".equals(property) && !instance.gainControlAvailable())
            throw new IllegalArgumentException("Generated target gain control is unavailable");
        if ("muted".equals(property) && !instance.muteControlAvailable())
            throw new IllegalArgumentException("Generated target mute control is unavailable");
        GainNode gain = DynamicInstanceRegistry.generatedGain(registry, target.nodeId(), target.instanceKey());
        Map<String, Object> binding = instance.runtimeBinding();
        if (binding.containsKey("streams")) {
            verifyApplicationStreams(binding, snapshot);
            String sourceId = DynamicInstanceRegistry.generatedNodeId(
                    registry, target.nodeId(), target.instanceKey(), "source");
            SourceNode source = registry.effectiveGraph().nodes().stream()
                    .filter(node -> node.id().equals(sourceId) && node instanceof SourceNode)
                    .map(SourceNode.class::cast).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Generated application source is unavailable"));
            applicationRule(config, source.selector(), binding);
            return new ResolvedTarget(Kind.GENERATED_APPLICATION, target.routeId(), target.nodeId(),
                    target.instanceKey(), null, instance, gain, binding, "", source.selector());
        }
        if (binding.containsKey("deviceId")) {
            String bindingId = deviceId(String.valueOf(binding.get("deviceId")));
            GraphNodeCommand.deviceBinding(config, bindingId);
            return new ResolvedTarget(Kind.GENERATED_DEVICE, target.routeId(), target.nodeId(),
                    target.instanceKey(), null, instance, gain, binding, bindingId, "");
        }
        if (binding.containsKey("browser") || binding.containsKey("tabId")
                || binding.containsKey("sessionToken")) {
            if (!snapshot.connected())
                throw new IllegalArgumentException("Generated tab instance is unavailable");
            return new ResolvedTarget(Kind.GENERATED_TAB, target.routeId(), target.nodeId(),
                    target.instanceKey(), null, instance, gain, binding, "", "");
        }
        throw new IllegalArgumentException("Generated instance has no supported runtime binding");
    }

    private static void verifyApplicationStreams(Map<String, Object> binding,
            PipeWireBackend.Snapshot snapshot) {
        if (!snapshot.connected()) throw new IllegalArgumentException("Generated application stream is unavailable");
        Object raw = binding.get("streams");
        if (!(raw instanceof List<?> streams) || streams.isEmpty())
            throw new IllegalArgumentException("Generated application has no live stream binding");
        for (Object stream : streams) {
            if (!(stream instanceof Map<?, ?> item) || !(item.get("nodeId") instanceof Number id)
                    || snapshot.streams().stream().noneMatch(candidate -> candidate.id() == id.intValue()))
                throw new IllegalArgumentException("Generated application stream binding is stale");
        }
    }

    private static Map<String, Object> onlyApplicationRule(Map<String, Object> config, String selector,
            Map<String, Object> binding) {
        return Map.of("applications", List.of(applicationRule(config, selector, binding)));
    }

    private static Map<String, Object> applicationRule(Map<String, Object> config, String selector,
            Map<String, Object> binding) {
        String appId = text(binding.get("applicationId"));
        String process = text(binding.get("processName"));
        String forcedAppId = null;
        String forcedProcess = null;
        if (selector.startsWith("application:")) {
            String selected = selector.substring("application:".length());
            if (!selected.isBlank() && !"unassigned".equals(selected) && !"*".equals(selected))
                forcedAppId = selected;
        } else if (selector.startsWith("process:")) {
            forcedProcess = selector.substring("process:".length());
        }
        if (forcedAppId != null) appId = forcedAppId;
        if (forcedProcess != null) process = forcedProcess;
        for (Map<String, Object> rule : maps(config.get("applications"))) {
            Map<String, Object> match = object(rule.get("match"));
            String ruleApp = text(match.get("app_id"));
            String ruleProcess = text(match.get("process"));
            if (ruleApp.isBlank() && ruleProcess.isBlank()) continue;
            if (forcedAppId != null && !forcedAppId.equals(ruleApp)) continue;
            if (forcedProcess != null && !forcedProcess.equals(ruleProcess)) continue;
            if (!ruleApp.isBlank() && !ruleApp.equals(appId)) continue;
            if (!ruleProcess.isBlank() && !ruleProcess.equals(process)) continue;
            return rule;
        }
        throw new IllegalArgumentException("Generated application has no existing application rule");
    }

    private static AudioNode resolveSavedOutput(ResolvedTarget target, Map<String, Object> config,
            PipeWireBackend.Snapshot snapshot, List<AudioNode> sinks) {
        if (!snapshot.connected()) return null;
        DeviceSlotNode slot = (DeviceSlotNode) target.node();
        return GnomeOutputState.resolve(sinks, slot.deviceId(), maps(config.get("devices")));
    }

    private static AudioNode resolveGeneratedOutput(ResolvedTarget target, Map<String, Object> config,
            PipeWireBackend.Snapshot snapshot, List<AudioNode> sinks) {
        if (!snapshot.connected()) return null;
        Object rawId = target.runtimeBinding().get("nodeId");
        if (!(rawId instanceof Number id))
            throw new IllegalArgumentException("Generated output has no live device binding");
        AudioNode output = sinks.stream().filter(node -> node.id() == id.intValue()).findFirst().orElse(null);
        if (output == null) return null;
        AudioNode configured = GnomeOutputState.resolve(sinks, "device:" + target.bindingId(),
                maps(config.get("devices")));
        if (configured == null || configured.id() != output.id())
            throw new IllegalArgumentException("Generated output device binding is stale");
        return output;
    }

    private static CompletableFuture<?> applyDeviceLevel(PipeWireBackend backend, int nodeId,
            Map<String, Object> config, String bindingId, String property) {
        Map<String, Object> binding = GraphNodeCommand.deviceBinding(config, bindingId);
        if ("volume".equals(property))
            return backend.setVolume(nodeId, number(binding.get("volume"), 1.0));
        double savedVolume = number(binding.get("volume"), 1.0);
        return backend.setMute(nodeId, Boolean.TRUE.equals(binding.get("mute")), savedVolume);
    }


    private static String deviceId(String reference) {
        String id = reference != null && reference.startsWith("device:")
                ? reference.substring("device:".length()) : reference;
        if (id == null || id.isBlank() || "system_default".equals(id))
            throw new IllegalArgumentException("Device output has no saved binding");
        return id;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
