package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/** Resolves API v5 graph targets to canonical saved or live instance controls. */
final class GnomeGraphNodeCommandExecutor {
    private GnomeGraphNodeCommandExecutor() {}

    record PropertyValue(String targetIdentity, Object value) {
        PropertyValue {
            if (value instanceof Number number) value = number.doubleValue();
        }
    }
    @FunctionalInterface
    interface DeviceLevelRuntime {
        CompletableFuture<?> apply(int nodeId, Map<String, Object> config, String bindingId, String property);
    }

    static Object propertyValue(Map<String, Object> config, GnomeNodeCommandHandler.PropertyKey key,
            DynamicInstanceRegistry.ReconcileResult registry, PipeWireBackend.Snapshot snapshot,
            List<AudioNode> sinks) {
        return GnomeGraphNodeTargetResolver.propertyValue(config, key, registry, snapshot, sinks);
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
        return execute(payload, store, snapshot, sinks, registry, graphRuntimeActive, propertyGuard,
                applyGraphRuntime, rollbackGraphRuntime, applyApplicationRuntime, rollbackApplicationRuntime,
                (nodeId, candidate, bindingId, property) ->
                        GnomeGraphNodeDeviceResolver.applyLevel(backend, nodeId, candidate,
                                bindingId), saveFailed, saved);
    }

    static CompletableFuture<Void> execute(GnomeNodeCommandHandler.Payload payload, ConfigStore store,
            PipeWireBackend.Snapshot snapshot, List<AudioNode> sinks,
            DynamicInstanceRegistry.ReconcileResult registry, boolean graphRuntimeActive,
            Consumer<Map<String, Object>> propertyGuard,
            Function<Map<String, Object>, CompletableFuture<?>> applyGraphRuntime,
            Function<Map<String, Object>, CompletableFuture<?>> rollbackGraphRuntime,
            Function<Map<String, Object>, CompletableFuture<?>> applyApplicationRuntime,
            Function<Map<String, Object>, CompletableFuture<?>> rollbackApplicationRuntime,
            DeviceLevelRuntime applyDeviceRuntime, Consumer<Throwable> saveFailed, Runnable saved) {
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
        GnomeGraphNodeTargetResolver.ResolvedTarget target;
        try {
            target = GnomeGraphNodeTargetResolver.resolve(current, requested, property, registry, snapshot, sinks);
        } catch (RuntimeException stale) {
            return CompletableFuture.failedFuture(stale);
        }

        Consumer<Map<String, Object>> change;
        Function<Map<String, Object>, CompletableFuture<?>> applyRuntime;
        Function<Map<String, Object>, CompletableFuture<?>> applyRollback;
        boolean runtimeActive = false;
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
                        "muted".equals(property) ? (boolean) value : false);
                AudioNode output;
                try { output = GnomeGraphNodeDeviceResolver.resolveSavedOutput(target, current, snapshot, sinks); }
                catch (RuntimeException stale) { return CompletableFuture.failedFuture(stale); }
                runtimeActive = snapshot.connected();
                if (runtimeActive && output == null)
                    return CompletableFuture.failedFuture(new IllegalStateException("Bound output is unavailable"));
                applyRuntime = candidate -> output == null ? CompletableFuture.completedFuture(null)
                        : applyDeviceRuntime.apply(output.id(), candidate, target.bindingId(), property);
                applyRollback = applyRuntime;
            }
            case GENERATED_APPLICATION -> {
                change = config -> {
                    Map<String, Object> rule = GnomeApplicationRuleResolver.ensureResolved(config,
                            target.applicationSelector(), target.runtimeBinding(), snapshot);
                    rule.put("volume".equals(property) ? "volume" : "mute", value);
                    if ("volume".equals(property)) rule.put("mute", false);
                };
                applyRuntime = candidate -> applyApplicationRuntime.apply(
                        GnomeApplicationRuleResolver.only(candidate,
                                target.applicationSelector(), target.runtimeBinding(), snapshot));
                applyRollback = candidate -> rollbackApplicationRuntime.apply(
                        GnomeApplicationRuleResolver.only(candidate,
                                target.applicationSelector(), target.runtimeBinding(), snapshot));
                runtimeActive = true;
            }
            case GENERATED_DEVICE -> {
                change = config -> {
                    Map<String, Object> binding = GraphNodeCommand.deviceBinding(config, target.bindingId());
                    binding.put("volume".equals(property) ? "volume" : "mute", value);
                    if ("volume".equals(property)) binding.put("mute", false);
                };
                AudioNode output;
                try { output = GnomeGraphNodeDeviceResolver.resolveGeneratedOutput(target, current,
                        snapshot, sinks); }
                catch (RuntimeException stale) { return CompletableFuture.failedFuture(stale); }
                if (output == null) return CompletableFuture.failedFuture(
                        new IllegalStateException("Generated output instance is unavailable"));
                applyRuntime = candidate -> applyDeviceRuntime.apply(output.id(), candidate,
                        target.bindingId(), property);
                applyRollback = applyRuntime;
                runtimeActive = true;
            }
            case GENERATED_TAB -> {
                String field = "volume".equals(property) ? "gain" : "muted";
                change = config -> {
                    DynamicInstanceRegistry.setInstanceOverride(config, requested.routeId(),
                            requested.nodeId(), requested.instanceKey(), field, value);
                    if ("volume".equals(property))
                        DynamicInstanceRegistry.setInstanceOverride(config, requested.routeId(),
                                requested.nodeId(), requested.instanceKey(), "muted", false);
                };
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


}
