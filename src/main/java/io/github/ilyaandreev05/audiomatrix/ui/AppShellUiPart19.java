package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.*;
import io.github.ilyaandreev05.audiomatrix.config.*;
import io.github.ilyaandreev05.audiomatrix.gnome.*;
import io.github.ilyaandreev05.audiomatrix.graph.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.transform.Scale;
import javafx.stage.*;
import javafx.util.Duration;
import javafx.util.StringConverter;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

abstract class AppShellUiPart19 extends AppShellUiPart18 {
    protected AppShellUiPart19(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }
    private final Object browserMonitorLock = new Object();
    private final Object registryWriteLock = new Object();
    private final Map<BrowserAdapter, Consumer<BrowserAdapter.BrowserEvent>> browserEventListeners = new IdentityHashMap<>();
    private final Map<DynamicGraphWriteKey, CompletableFuture<Void>> registryWrites = new HashMap<>();
    private Timeline dynamicReconcileRefresh;


    protected void maybeApplyConfiguredRoutes(Map<String, Object> config, PipeWireBackend.Snapshot current) {
        ensureBrowserRuntimeMonitoring();
        boolean autoApply = Boolean.TRUE.equals(map(config.get("engine")).get("apply_on_start")) && !autoApplySuppressedUntilRestart;
        if (!current.connected()) {
            if (routeManager.active()) {
                CompletableFuture<PipeWireRouteManager.ApplyReport> apply = requestRouteApply(config, current, false, false);
                if (apply == null) return;
                apply.whenComplete((ignored, error) -> Platform.runLater(() -> {
                    RouteApplyGate.Completion completion = finishRouteApply(error == null);
                    routesRequested = true;
                    if (error != null) System.err.println("Audio Matrix route apply failed while PipeWire was offline: " + rootMessage(error));
                    retryPendingRouteApply(completion);
                    showPage(currentPage);
                }));
            } else {
                reconcileDynamicGraphOnly(config, current);
            }
            return;
        }
        if (!routesRequested && !autoApply) {
            reconcileDynamicGraphOnly(config, current);
            return;
        }
        long connectionGeneration = backend.connectionGeneration();
        boolean reconnected = connectionGeneration != lastAppliedConnectionGeneration;
        CompletableFuture<PipeWireRouteManager.ApplyReport> apply = requestRouteApply(config, current, false, reconnected);
        if (apply == null) return;
        apply.whenComplete((report, error) -> Platform.runLater(() -> {
            RouteApplyGate.Completion completion = finishRouteApply(error == null);
            if (error != null) {
                routesRequested = true;
                System.err.println("Audio Matrix automatic route apply failed: " + rootMessage(error));
                setNotice(t("Не удалось обновить применённый граф: ", "Could not update the applied graph: ") + rootMessage(error), true);
            } else {
                routesRequested = routeManager.active();
                if (currentPage == Page.ROUTES) setNotice(routeManager.status(), false);
            }
            retryPendingRouteApply(completion);
            showPage(currentPage);
        }));
    }

    protected CompletableFuture<PipeWireRouteManager.ApplyReport> requestRouteApply(
            Map<String, Object> config, PipeWireBackend.Snapshot current, boolean force, boolean reconnect) {
        return requestRuntimeRouteApply(config, current, force, reconnect, true);
    }

    protected CompletableFuture<PipeWireRouteManager.ApplyReport> applyRuntimeGraph(
            Map<String, Object> config, PipeWireBackend.Snapshot current, boolean force, boolean reconnect) {
        return applyRuntimeGraph(config, current, force, reconnect, true);
    }

    protected CompletableFuture<PipeWireRouteManager.ApplyReport> applyRuntimeGraph(
            Map<String, Object> config, PipeWireBackend.Snapshot current, boolean force, boolean reconnect,
            boolean persistRegistry) {
        CompletableFuture<PipeWireRouteManager.ApplyReport> apply =
                requestRuntimeRouteApply(config, current, force, reconnect, persistRegistry);
        if (apply == null)
            return CompletableFuture.failedFuture(new IllegalStateException("Another route apply is in progress"));
        CompletableFuture<PipeWireRouteManager.ApplyReport> finished = new CompletableFuture<>();
        apply.whenComplete((report, error) -> Platform.runLater(() -> {
            RouteApplyGate.Completion completion = finishRouteApply(error == null);
            retryPendingRouteApply(completion);
            if (error == null) finished.complete(report);
            else finished.completeExceptionally(error);
        }));
        return finished;
    }

    private CompletableFuture<PipeWireRouteManager.ApplyReport> requestRuntimeRouteApply(
            Map<String, Object> config, PipeWireBackend.Snapshot current, boolean force, boolean reconnect,
            boolean persistRegistry) {
        ensureBrowserRuntimeMonitoring();
        long generation = backend.connectionGeneration();
        String signature = routeInputSignature(config, current, generation);
        if (!routeApplyGate.request(signature, generation, force)) return null;
        try {
            return prepareRuntimeGraph(config, current, persistRegistry)
                    .thenCompose(plan -> applyRuntimePlan(plan, current, reconnect));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }



    protected RouteApplyGate.Completion finishRouteApply(boolean succeeded) {
        PipeWireBackend.Snapshot current = backend.current();
        Map<String, Object> config = store.snapshot();
        long generation = backend.connectionGeneration();
        String observedSignature = routeInputSignature(config, current, generation);
        lastAppliedConnectionGeneration = generation;
        return routeApplyGate.complete(succeeded, observedSignature, generation);
    }

    protected void retryPendingRouteApply(RouteApplyGate.Completion completion) {
        if (completion.forcePending()) {
            Platform.runLater(this::applyRoutes);
        } else if (completion.retryPending()) {
            Platform.runLater(() -> maybeApplyConfiguredRoutes(store.snapshot(), backend.current()));
        }
    }

    protected void setDspReferenceMode(boolean referenceMode) {
        if (dspReferenceUpdateInProgress) return;
        if (!routeManager.active()) {
            setNotice(t("Сначала примените граф маршрутов.", "Apply the route graph first."), true);
            showPage(currentPage);
            return;
        }
        boolean previous = dspReferenceMode;
        dspReferenceUpdateInProgress = true;
        if (dspReferenceToggle != null) dspReferenceToggle.setDisable(true);
        dspReferenceMode = referenceMode;
        Map<String, Object> config = store.snapshot();
        setNotice(t("Обновляю PipeWire для A/B…", "Updating PipeWire for A/B…"), false);
        backend.refresh().thenAccept(current -> {
            snapshot = current;
            updateMeterTarget();
            CompletableFuture<PipeWireRouteManager.ApplyReport> apply = requestRouteApply(config, current, true, false);
            if (apply == null) {
                Platform.runLater(() -> {
                    dspReferenceUpdateInProgress = false;
                    dspReferenceMode = previous;
                    setNotice(t("Граф уже обновляется; режим A/B не изменён.", "The graph is already updating; A/B mode was not changed."), true);
                    showPage(currentPage);
                });
                return;
            }
            apply.whenComplete((report, error) -> Platform.runLater(() -> {
                RouteApplyGate.Completion completion = finishRouteApply(error == null);
                dspReferenceUpdateInProgress = false;
                if (error != null) {
                    dspReferenceMode = previous;
                    routesRequested = true;
                    System.err.println("Audio Matrix A/B route apply failed: " + rootMessage(error));
                    setNotice(t("A/B не применён: ", "A/B was not applied: ") + rootMessage(error), true);
                } else {
                    routesRequested = routeManager.active();
                    setNotice(referenceMode
                            ? t("B включён: EQ обойдён, уровень снижен по расчётной верхней оценке.", "B is on: EQ bypassed and level reduced by the conservative gain estimate.")
                            : t("A включён: обработанная EQ-цепочка восстановлена.", "A is on: the processed EQ chain is restored."), false);
                }
                retryPendingRouteApply(completion);
                showPage(currentPage);
            }));
        });
    }

    protected Map<String, Object> runtimeDspConfig(Map<String, Object> config, PipeWireBackend.Snapshot current) {
        Map<String, Object> graphConfig = runtimeGraphConfig(config);
        return dspReferenceConfig(graphConfig, current);
    }

    protected Map<String, Object> runtimeGraphConfig(Map<String, Object> config) {
        String routeId = string(config.get("active_route"), "");
        if (routeId.isBlank()) return config;
        Map<String, Object> route = dynamicRoute(config, routeId);
        AudioGraph configuredGraph = GraphCodec.fromMap(route);
        DynamicInstanceRegistry.ReconcileResult result = dynamicGraphState(routeId);
        if (result == null || !result.configuredGraph().equals(configuredGraph))
            throw new IllegalStateException("The active dynamic graph must be reconciled before runtime compilation");
        return GraphPipeWireCompiler.compile(result, config);
    }

    private Map<String, Object> dspReferenceConfig(Map<String, Object> graphConfig, PipeWireBackend.Snapshot current) {
        if (!dspReferenceMode) return graphConfig;
        double matchDb;
        try { matchDb = Math.max(0, routeManager.maximumEqBoostDb(graphConfig, current, selectedSink)); }
        catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        return DspComparisonConfig.reference(graphConfig, matchDb);
    }

    protected String routeInputSignature(Map<String, Object> config, PipeWireBackend.Snapshot current, long generation) {
        return RouteInputSignature.create(config, current, generation) + ":" + browserSnapshotSignature(browserSnapshots());
    }


    @Override
    protected void applyConfiguredStreamRules(PipeWireBackend.Snapshot current, Map<String, Object> config) {
        RuntimeGraphPlan plan;
        try {
            plan = runtimePlanFromSharedState(config);
        } catch (RuntimeException error) {
            reportDynamicGraphFailure(error);
            return;
        }
        applyStreamRules(current, plan.runtimeConfig(), plan.capturedBindings())
                .whenComplete((ignored, error) -> {
                    if (error != null) reportDynamicGraphFailure(error);
                });
    }

    private CompletableFuture<RuntimeGraphPlan> prepareRuntimeGraph(
            Map<String, Object> config, PipeWireBackend.Snapshot current, boolean persistRegistry) {
        List<BrowserAdapter.BrowserSnapshot> browserSnapshots = browserSnapshots();
        DynamicGraphReconciliation reconciliation;
        RuntimeGraphPlan plan;
        try {
            reconciliation = reconcileDynamicGraph(config, current, browserSnapshots);
            if (reconciliation == null)
                return CompletableFuture.completedFuture(new RuntimeGraphPlan(config, Map.of(), null));
            Map<String, Object> runtimeConfig = GraphPipeWireCompiler.compile(reconciliation.result(), config);
            plan = new RuntimeGraphPlan(runtimeConfig, capturedStreamBindings(runtimeConfig), reconciliation);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
        if (!persistRegistry) return CompletableFuture.completedFuture(plan);
        return persistCanonicalDynamicGraph(reconciliation, current).thenApply(ignored -> plan);
    }

    private CompletableFuture<PipeWireRouteManager.ApplyReport> applyRuntimePlan(
            RuntimeGraphPlan plan, PipeWireBackend.Snapshot current, boolean reconnect) {
        Map<String, Object> runtimeConfig;
        try {
            runtimeConfig = dspReferenceConfig(plan.runtimeConfig(), current);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
        CompletableFuture<PipeWireRouteManager.ApplyReport> applied = applyStreamRules(
                current, runtimeConfig, plan.capturedBindings()).thenCompose(ignored -> {
            try {
                return reconnect
                        ? routeManager.reapplyAfterReconnect(runtimeConfig, current, plan.capturedBindings())
                        : routeManager.apply(runtimeConfig, current, plan.capturedBindings());
            } catch (RuntimeException error) {
                return CompletableFuture.failedFuture(error);
            }
        });
        return applied.whenComplete((report, error) -> publishDynamicGraphState(plan.reconciliation()));
    }

    private CompletableFuture<Void> applyStreamRules(
            PipeWireBackend.Snapshot current, Map<String, Object> runtimeConfig,
            Map<String, PipeWireRouteTypes.CapturedStreamBinding> bindings) {
        return streamRuleApplier.apply(current, maps(runtimeConfig.get("applications")), bindings,
                (stream, error) -> Platform.runLater(() -> setNotice(
                        t("Не удалось применить правило потока ", "Could not apply stream rule for ")
                                + stream.applicationName() + ": " + rootMessage(error), true)));
    }

    private RuntimeGraphPlan runtimePlanFromSharedState(Map<String, Object> config) {
        String routeId = string(config.get("active_route"), "");
        if (routeId.isBlank()) return new RuntimeGraphPlan(config, Map.of(), null);
        Map<String, Object> route = dynamicRoute(config, routeId);
        AudioGraph configuredGraph = GraphCodec.fromMap(route);
        DynamicInstanceRegistry.ReconcileResult result = dynamicGraphState(routeId);
        if (result == null || !result.configuredGraph().equals(configuredGraph))
            return new RuntimeGraphPlan(config, Map.of(), null);
        Map<String, Object> runtimeConfig = GraphPipeWireCompiler.compile(result, config);
        return new RuntimeGraphPlan(runtimeConfig, capturedStreamBindings(runtimeConfig),
                new DynamicGraphReconciliation(routeId, GraphRouteLibrary.activeProfileId(config),
                        configuredGraph, result, browserSnapshots()));
    }

    private CompletableFuture<Void> reconcileDynamicGraphOnly(
            Map<String, Object> config, PipeWireBackend.Snapshot current) {
        try {
            DynamicGraphReconciliation reconciliation = reconcileDynamicGraph(config, current, browserSnapshots());
            if (reconciliation == null) return CompletableFuture.completedFuture(null);
            return persistCanonicalDynamicGraph(reconciliation, current).whenComplete((ignored, error) -> {
                if (error == null) publishDynamicGraphState(reconciliation);
                else reportDynamicGraphFailure(error);
            });
        } catch (RuntimeException error) {
            reportDynamicGraphFailure(error);
            return CompletableFuture.failedFuture(error);
        }
    }

    private DynamicGraphReconciliation reconcileDynamicGraph(
            Map<String, Object> config, PipeWireBackend.Snapshot current,
            List<BrowserAdapter.BrowserSnapshot> browserSnapshots) {
        String routeId = string(config.get("active_route"), "");
        if (routeId.isBlank()) return null;
        String profileId = GraphRouteLibrary.activeProfileId(config);
        Map<String, Object> route = dynamicRoute(config, routeId);
        AudioGraph configuredGraph = GraphCodec.fromMap(route);
        Map<String, List<DynamicInstanceRegistry.InstanceSnapshot>> snapshotsByGenerator =
                dynamicSnapshots(configuredGraph, config, current, browserSnapshots);
        DynamicInstanceRegistry.ApplicationGainResolver applicationGainResolver =
                (selector, instance) -> applicationGainState(selector, instance, config);
        DynamicInstanceRegistry.EndpointGainResolver endpointGainResolver =
                (instanceKey, observation) -> endpointGainState(instanceKey, observation, config);
        DynamicInstanceRegistry.ReconcileResult result = DynamicInstanceRegistry.reconcile(
                configuredGraph, snapshotsByGenerator, Map.of(), applicationGainResolver, endpointGainResolver);
        Map<String, String> parentCompositeIds = browserParentCompositeIds(configuredGraph, result);
        if (!parentCompositeIds.isEmpty())
            result = DynamicInstanceRegistry.reconcile(configuredGraph, snapshotsByGenerator,
                    parentCompositeIds, applicationGainResolver, endpointGainResolver);
        DynamicInstanceRegistry.ReconcileResult previous = dynamicGraphState(routeId);
        if (result.equals(previous)) result = previous;
        return new DynamicGraphReconciliation(routeId, profileId, configuredGraph, result, browserSnapshots);
    }

    private Map<String, List<DynamicInstanceRegistry.InstanceSnapshot>> dynamicSnapshots(
            AudioGraph graph, Map<String, Object> config, PipeWireBackend.Snapshot current,
            List<BrowserAdapter.BrowserSnapshot> browserSnapshots) {
        List<DynamicGeneratorNode> generators = graph.nodes().stream()
                .filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).toList();
        List<DynamicInstanceRegistry.InstanceSnapshot> applications = new ArrayList<>();
        Set<String> liveBrowserApplications = new HashSet<>();
        for (AudioNode stream : current.streams()) {
            if (!ordinaryApplicationStream(stream)) continue;
            BrowserAdapter adapter = chromeAdapterFor(stream);
            if (adapter != null) {
                applications.add(DynamicInstanceRegistry.InstanceSnapshot.application(
                        stream, adapter.applicationId(), adapter.displayName()));
                liveBrowserApplications.add(adapter.id());
            } else {
                applications.add(DynamicInstanceRegistry.InstanceSnapshot.application(stream));
            }
        }
        Set<String> browserParents = new LinkedHashSet<>();
        for (DynamicGeneratorNode generator : generators) {
            Map<String, Object> parameters = generator.parameters();
            if (!"browser_tabs".equals(string(parameters.get("registry"), "applications"))) continue;
            String browser = browserForGenerator(generator);
            if ("chrome".equals(browser)) browserParents.add(browser);
        }
        for (String browser : browserParents) {
            if (liveBrowserApplications.contains(browser)) continue;
            BrowserAdapter adapter = browserAdapter(browser);
            String displayName = adapter == null ? "Chrome" : adapter.displayName();
            applications.add(DynamicInstanceRegistry.InstanceSnapshot.browserParentApplication(
                    browser, "", "", displayName));
        }

        List<AudioNode> outputs = current.sinks().stream()
                .filter(node -> "output".equals(node.kind()))
                .filter(node -> !node.name().startsWith("audiomatrix_"))
                .toList();
        List<DynamicInstanceRegistry.InstanceSnapshot> browserTabs =
                browserTabSnapshots(config, current, browserSnapshots);
        Map<String, List<DynamicInstanceRegistry.InstanceSnapshot>> result = new LinkedHashMap<>();
        for (DynamicGeneratorNode generator : generators) {
            Map<String, Object> parameters = generator.parameters();
            String registry = string(parameters.get("registry"), "applications");
            List<DynamicInstanceRegistry.InstanceSnapshot> candidates = switch (registry) {
                case "applications" -> applications;
                case "outputs" -> outputs.stream().map(output -> {
                    boolean autoSelectHdmi = Boolean.TRUE.equals(
                            parameters.get("auto_select_trusted_hdmi_on_first_discovery"));
                    boolean selected = autoSelectHdmi && trustedInitialHdmi(output);
                    return DynamicInstanceRegistry.InstanceSnapshot.output(
                            output, selected, safeObservedEndpointGain(output));
                }).toList();
                case "browser_tabs" -> browserTabs;
                default -> List.of();
            };
            result.put(generator.id(), candidates);
        }
        return result;
    }

    private List<DynamicInstanceRegistry.InstanceSnapshot> browserTabSnapshots(
            Map<String, Object> config, PipeWireBackend.Snapshot current,
            List<BrowserAdapter.BrowserSnapshot> browserSnapshots) {
        List<DynamicInstanceRegistry.InstanceSnapshot> result = new ArrayList<>();
        List<Map<String, Object>> rules = maps(config.get("applications"));
        for (BrowserAdapter.BrowserSnapshot browser : browserSnapshots) {
            if (!"chrome".equals(browser.browser())) continue;
            BrowserAdapter adapter = browserAdapter(browser.browser());
            if (adapter == null) continue;
            AudioNode parent = current.streams().stream()
                    .filter(this::ordinaryApplicationStream)
                    .filter(stream -> adapter.matches(stream.applicationId(), stream.processName(), stream.applicationName()))
                    .findFirst().orElse(null);
            Map<String, Object> parentRule = parent == null ? null : ApplicationRuleMatcher.find(rules, parent);
            String ruleId = parentRule == null ? "" : string(parentRule.get("id"), "");
            String parentApplicationId = parent == null ? "" : safe(parent.applicationId());
            String parentProcessName = parent == null ? "" : safe(parent.processName());
            for (BrowserAdapter.BrowserTab tab : browser.tabs()) {
                if (safe(browser.instanceId()).isBlank() || safe(browser.profileId()).isBlank()
                        || safe(tab.sessionToken()).isBlank()) continue;
                result.add(DynamicInstanceRegistry.InstanceSnapshot.browserTab(
                        browser.browser(), browser.instanceId(), browser.profileId(), tab.sessionToken(),
                        tab.tabId(), safe(tab.title()), tab.audible(), tab.muted(),
                        safe(tab.volumeCapability()), safe(tab.captureStreamKey()), ruleId,
                        parentApplicationId, parentProcessName));
            }
        }
        return List.copyOf(result);
    }

    private Map<String, String> browserParentCompositeIds(
            AudioGraph graph, DynamicInstanceRegistry.ReconcileResult result) {
        List<DynamicGeneratorNode> applicationGenerators = graph.nodes().stream()
                .filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast)
                .filter(generator -> "applications".equals(
                        string(generator.parameters().get("registry"), "applications"))).toList();
        Map<String, String> parentIds = new LinkedHashMap<>();
        for (GraphNode node : graph.nodes()) {
            if (!(node instanceof DynamicGeneratorNode generator)
                    || !"browser_tabs".equals(string(generator.parameters().get("registry"), "applications"))) continue;
            String browser = browserForGenerator(generator);
            if (!"chrome".equals(browser)) continue;
            String key = "browser-parent:" + browser;
            for (DynamicGeneratorNode applicationGenerator : applicationGenerators) {
                DynamicInstanceRegistry.RuntimeInstance parent = result.instances().get(
                        new DynamicInstanceRegistry.InstanceAddress(applicationGenerator.id(), key));
                if (parent != null && !parent.compositeId().isBlank()) {
                    parentIds.put(generator.id(), parent.compositeId());
                    break;
                }
            }
        }
        return parentIds;
    }

    private DynamicInstanceRegistry.GainState applicationGainState(
            String selector, DynamicInstanceRegistry.InstanceSnapshot instance, Map<String, Object> config) {
        Map<String, Object> binding = map(instance.runtimeBinding());
        String applicationId = string(binding.get("applicationId"), "");
        String processName = string(binding.get("processName"), "");
        if (applicationId.isBlank() && selector != null && selector.startsWith("application:")
                && !selector.equals("application:unassigned") && !selector.equals("application:*"))
            applicationId = selector.substring("application:".length());
        if (processName.isBlank() && selector != null && selector.startsWith("process:"))
            processName = selector.substring("process:".length());
        for (Map<String, Object> rule : maps(config.get("applications"))) {
            Map<String, Object> match = map(rule.get("match"));
            String ruleApplicationId = string(match.get("app_id"), "");
            String ruleProcessName = string(match.get("process"), "");
            if (ruleApplicationId.isBlank() && ruleProcessName.isBlank()) continue;
            if (!ruleApplicationId.isBlank() && !ruleApplicationId.equals(applicationId)) continue;
            if (!ruleProcessName.isBlank() && !ruleProcessName.equals(processName)) continue;
            return configuredGain(rule.get("volume"), rule.get("mute"));
        }
        return null;
    }

    private DynamicInstanceRegistry.GainState endpointGainState(
            String instanceKey, DynamicInstanceRegistry.InstanceSnapshot observation, Map<String, Object> config) {
        String fingerprint = instanceKey != null && instanceKey.startsWith("device:")
                ? instanceKey.substring("device:".length()) : "";
        for (Map<String, Object> device : maps(config.get("devices"))) {
            if (!(device.get("identity") instanceof Map<?, ?>)) continue;
            DeviceIdentity identity = DeviceIdentity.fromMap(map(device.get("identity")));
            if (!identity.reliable() || !identity.fingerprint().equals(fingerprint)) continue;
            DynamicInstanceRegistry.GainState saved = configuredGain(device.get("volume"), device.get("mute"));
            if (saved != null) return saved;
        }
        if (observation == null) return null;
        Map<String, Object> binding = map(observation.runtimeBinding());
        DeviceIdentity observedIdentity = DeviceIdentity.fromMap(map(binding.get("identity")));
        if (!observedIdentity.reliable() || !observedIdentity.fingerprint().equals(fingerprint)) return null;
        return configuredGain(binding.get("volume"), binding.getOrDefault("muted", binding.get("mute")));
    }

    private static DynamicInstanceRegistry.GainState safeObservedEndpointGain(AudioNode output) {
        if (output.identity() == null || !output.identity().reliable()
                || !Double.isFinite(output.volume()) || output.volume() < 0 || output.volume() > 1) return null;
        return new DynamicInstanceRegistry.GainState(output.volume(), output.muted());
    }

    private static DynamicInstanceRegistry.GainState configuredGain(Object rawGain, Object rawMuted) {
        double gain = number(rawGain, Double.NaN);
        if (!Double.isFinite(gain) || gain < 0 || gain > 1) return null;
        return new DynamicInstanceRegistry.GainState(gain, Boolean.TRUE.equals(rawMuted));
    }

    private CompletableFuture<Void> persistCanonicalDynamicGraph(
            DynamicGraphReconciliation reconciliation, PipeWireBackend.Snapshot current) {
        Map<String, Object> canonical = store.snapshot();
        String routeId = reconciliation.routeId();
        String profileId = reconciliation.profileId();
        if (!routeId.equals(string(canonical.get("active_route"), ""))
                || !profileId.equals(GraphRouteLibrary.activeProfileId(canonical)))
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The active profile route changed during dynamic graph reconciliation"));
        Map<String, Object> canonicalRoute = dynamicRoute(canonical, routeId);
        AudioGraph canonicalGraph = GraphCodec.fromMap(canonicalRoute);
        AudioGraph expectedGraph;
        AudioGraph updatedGraph;
        if (canonicalGraph.equals(reconciliation.sourceGraph())) {
            if (!reconciliation.result().configChanged()) return CompletableFuture.completedFuture(null);
            expectedGraph = canonicalGraph;
            updatedGraph = reconciliation.result().configuredGraph();
        } else {
            DynamicGraphReconciliation canonicalReconciliation = reconcileDynamicGraph(
                    canonical, current, reconciliation.browserSnapshots());
            if (canonicalReconciliation == null || !canonicalReconciliation.result().configChanged())
                return CompletableFuture.completedFuture(null);
            expectedGraph = canonicalReconciliation.sourceGraph();
            updatedGraph = canonicalReconciliation.result().configuredGraph();
        }
        if (expectedGraph.equals(updatedGraph)) return CompletableFuture.completedFuture(null);
        return persistConfiguredGraph(routeId, profileId, expectedGraph, updatedGraph);
    }

    private CompletableFuture<Void> persistConfiguredGraph(
            String routeId, String profileId, AudioGraph expectedGraph, AudioGraph updatedGraph) {
        if (routeGraphEquals(store.snapshot(), routeId, profileId, updatedGraph))
            return CompletableFuture.completedFuture(null);
        DynamicGraphWriteKey key = new DynamicGraphWriteKey(routeId, profileId, expectedGraph, updatedGraph);
        synchronized (registryWriteLock) {
            CompletableFuture<Void> pending = registryWrites.get(key);
            if (pending != null) return pending;
            if (routeGraphEquals(store.snapshot(), routeId, profileId, updatedGraph))
                return CompletableFuture.completedFuture(null);
            CompletableFuture<Void> write = store.updateAsync(config -> {
                if (!routeId.equals(string(config.get("active_route"), ""))
                        || !profileId.equals(GraphRouteLibrary.activeProfileId(config)))
                    throw new IllegalStateException("The active profile route changed before dynamic graph persistence");
                Map<String, Object> route = dynamicRoute(config, routeId);
                AudioGraph actualGraph = GraphCodec.fromMap(route);
                if (actualGraph.equals(updatedGraph)) return;
                if (!actualGraph.equals(expectedGraph))
                    throw new IllegalStateException("The configured route changed before dynamic graph persistence");
                Map<String, Object> encoded = GraphCodec.toMap(updatedGraph);
                route.put("name", encoded.get("name"));
                route.put("nodes", encoded.get("nodes"));
                route.put("connections", encoded.get("connections"));
            });
            registryWrites.put(key, write);
            write.whenComplete((ignored, error) -> {
                synchronized (registryWriteLock) {
                    registryWrites.remove(key, write);
                }
            });
            return write;
        }
    }

    private static boolean routeGraphEquals(
            Map<String, Object> config, String routeId, String profileId, AudioGraph expected) {
        if (!routeId.equals(string(config.get("active_route"), ""))
                || !profileId.equals(GraphRouteLibrary.activeProfileId(config))) return false;
        try {
            return GraphCodec.fromMap(dynamicRoute(config, routeId)).equals(expected);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private void publishDynamicGraphState(DynamicGraphReconciliation reconciliation) {
        if (reconciliation == null) return;
        DynamicInstanceRegistry.ReconcileResult previous = dynamicGraphState(reconciliation.routeId());
        if (Objects.equals(previous, reconciliation.result())) return;
        setDynamicGraphState(reconciliation.routeId(), reconciliation.result());
        Platform.runLater(() -> {
            if (closing) return;
            publishGnomeState();
            if (currentPage == Page.GRAPH) showPage(currentPage);
        });
    }

    private void reportDynamicGraphFailure(Throwable error) {
        System.err.println("Audio Matrix dynamic graph reconciliation failed: " + rootMessage(error));
        Platform.runLater(() -> setNotice(
                t("Не удалось обновить динамический граф: ", "Could not reconcile the dynamic graph: ")
                        + rootMessage(error), true));
    }

    private void ensureBrowserRuntimeMonitoring() {
        if (closing || browserAdapters == null || browserAdapters.isEmpty()) return;
        synchronized (browserMonitorLock) {
            for (BrowserAdapter adapter : browserAdapters) {
                if (browserEventListeners.containsKey(adapter)) continue;
                Consumer<BrowserAdapter.BrowserEvent> listener = this::browserEventObserved;
                browserEventListeners.put(adapter, listener);
                adapter.addListener(listener);
            }
        }
        Runnable startFallback = () -> {
            if (closing || dynamicReconcileRefresh != null) return;
            dynamicReconcileRefresh = new Timeline(new KeyFrame(Duration.seconds(5),
                    event -> maybeApplyConfiguredRoutes(store.snapshot(), backend.current())));
            dynamicReconcileRefresh.setCycleCount(Timeline.INDEFINITE);
            dynamicReconcileRefresh.play();
        };
        if (Platform.isFxApplicationThread()) startFallback.run();
        else Platform.runLater(startFallback);
    }

    private void browserEventObserved(BrowserAdapter.BrowserEvent event) {
        if (closing) return;
        Platform.runLater(() -> {
            if (!closing) maybeApplyConfiguredRoutes(store.snapshot(), backend.current());
        });
    }

    private void detachBrowserListeners() {
        synchronized (browserMonitorLock) {
            browserEventListeners.forEach(BrowserAdapter::removeListener);
            browserEventListeners.clear();
        }
    }

    private List<BrowserAdapter.BrowserSnapshot> browserSnapshots() {
        if (browserAdapters == null || browserAdapters.isEmpty()) return List.of();
        return browserAdapters.stream().flatMap(adapter -> adapter.snapshots().stream())
                .sorted(Comparator.comparing(BrowserAdapter.BrowserSnapshot::browser)
                        .thenComparing(BrowserAdapter.BrowserSnapshot::instanceId)
                        .thenComparing(BrowserAdapter.BrowserSnapshot::profileId))
                .toList();
    }

    private static String browserSnapshotSignature(List<BrowserAdapter.BrowserSnapshot> snapshots) {
        List<String> rows = new ArrayList<>();
        for (BrowserAdapter.BrowserSnapshot browser : snapshots) {
            StringBuilder row = new StringBuilder();
            appendSignatureField(row, browser.browser());
            appendSignatureField(row, browser.instanceId());
            appendSignatureField(row, browser.profileId());
            browser.tabs().stream().sorted(Comparator.comparing(BrowserAdapter.BrowserTab::sessionToken))
                    .forEach(tab -> {
                        appendSignatureField(row, tab.sessionToken());
                        appendSignatureField(row, Integer.toString(tab.tabId()));
                        appendSignatureField(row, tab.title());
                        appendSignatureField(row, Boolean.toString(tab.audible()));
                        appendSignatureField(row, Boolean.toString(tab.muted()));
                        appendSignatureField(row, tab.volumeCapability());
                        appendSignatureField(row, tab.captureStreamKey());
                    });
            rows.add(row.toString());
        }
        StringBuilder signature = new StringBuilder().append(rows.size()).append(':');
        rows.sort(String::compareTo);
        rows.forEach(row -> appendSignatureField(signature, row));
        return signature.toString();
    }

    private static void appendSignatureField(StringBuilder target, String value) {
        String field = safe(value);
        target.append(field.length()).append(':').append(field).append(';');
    }
    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean ordinaryApplicationStream(AudioNode stream) {
        return "stream".equals(stream.kind())
                && !AudioMatrixOwnedStream.matches(stream)
                && !EventSoundStudioSource.APPLICATION_ID.equals(stream.applicationId())
                && !ChromeBrowserAdapter.CAPTURE_APPLICATION_ID.equals(stream.applicationId())
                && !stream.name().startsWith("audio-matrix-chrome-");
    }

    private BrowserAdapter chromeAdapterFor(AudioNode stream) {
        if (browserAdapters == null) return null;
        return browserAdapters.stream().filter(adapter -> "chrome".equals(adapter.id())
                        && adapter.matches(stream.applicationId(), stream.processName(), stream.applicationName()))
                .findFirst().orElse(null);
    }

    private BrowserAdapter browserAdapter(String browser) {
        if (browserAdapters == null) return null;
        return browserAdapters.stream().filter(adapter -> adapter.id().equals(browser)).findFirst().orElse(null);
    }

    private static String browserForGenerator(DynamicGeneratorNode generator) {
        String selector = string(generator.parameters().get("selector"), "");
        if (selector.startsWith("browser:")) {
            String selected = selector.substring("browser:".length());
            return selected.equals("*") ? "chrome" : selected;
        }
        return "chrome";
    }

    private static boolean trustedInitialHdmi(AudioNode output) {
        return "alsa_output.pci-0000_09_00.1.hdmi-stereo".equals(output.name())
                && output.identity() != null && output.identity().reliable();
    }


    private static Map<String, PipeWireRouteTypes.CapturedStreamBinding> capturedStreamBindings(
            Map<String, Object> runtimeConfig) {
        Object raw = runtimeConfig.get("graph_capture_sources");
        if (raw == null) return Map.of();
        if (!(raw instanceof List<?> rows))
            throw new IllegalStateException("Runtime graph_capture_sources must be an array");
        Map<String, PipeWireRouteTypes.CapturedStreamBinding> bindings = new LinkedHashMap<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> rawRow))
                throw new IllegalStateException("Runtime capture source row must be an object");
            Map<String, Object> row = new LinkedHashMap<>();
            rawRow.forEach((key, value) -> row.put(String.valueOf(key), value));
            String sourceId = string(row.get("source_id"), "");
            String instanceKey = string(row.get("instance_key"), "");
            String captureStreamKey = string(row.get("capture_stream_key"), "");
            String nodeName = string(row.get("node_name"), "");
            if (sourceId.isBlank() || instanceKey.isBlank() || captureStreamKey.isBlank()
                    || nodeName.isBlank()
                    || !nodeName.equals("audio-matrix-chrome-" + captureStreamKey))
                throw new IllegalStateException("Runtime capture source row has an invalid exact PipeWire binding");
            double tabGain = number(row.get("tab_gain"), Double.NaN);
            if (!Double.isFinite(tabGain) || tabGain < 0 || tabGain > 1)
                throw new IllegalStateException("Runtime capture source has an invalid tab gain");
            if (!(row.get("tab_muted") instanceof Boolean tabMuted))
                throw new IllegalStateException("Runtime capture source has no tab mute state");
            PipeWireRouteTypes.CapturedStreamBinding binding = new PipeWireRouteTypes.CapturedStreamBinding(
                    nodeName, sourceId, string(row.get("application_rule_id"), ""),
                    string(row.get("parent_application_id"), ""), string(row.get("parent_process_name"), ""),
                    instanceKey, tabGain, tabMuted);
            if (bindings.putIfAbsent(nodeName, binding) != null)
                throw new IllegalStateException("Runtime graph contains duplicate capture node names");
        }
        return Map.copyOf(bindings);
    }

    private static Map<String, Object> dynamicRoute(Map<String, Object> config, String routeId) {
        Map<String, Object> route = maps(config.get("route_library")).stream()
                .filter(item -> routeId.equals(item.get("id"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active graph route is missing: " + routeId));
        String profileId = GraphRouteLibrary.activeProfileId(config);
        if (!profileId.equals(string(route.get("profile_id"), "")))
            throw new IllegalArgumentException("Active graph route does not belong to the active profile");
        return route;
    }

    private record DynamicGraphReconciliation(
            String routeId, String profileId, AudioGraph sourceGraph,
            DynamicInstanceRegistry.ReconcileResult result,
            List<BrowserAdapter.BrowserSnapshot> browserSnapshots) {}

    private record RuntimeGraphPlan(
            Map<String, Object> runtimeConfig,
            Map<String, PipeWireRouteTypes.CapturedStreamBinding> capturedBindings,
            DynamicGraphReconciliation reconciliation) {}

    private record DynamicGraphWriteKey(
            String routeId, String profileId, AudioGraph expectedGraph, AudioGraph updatedGraph) {}

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closeFuture != null) return closeFuture;
        closing = true;
        meterRefresh.stop();
        if (routeRefresh != null) routeRefresh.stop();
        if (dynamicReconcileRefresh != null) dynamicReconcileRefresh.stop();
        detachBrowserListeners();
        backend.monitorOutput(null);
        backend.monitorInput(null);
        backend.cancelCalibrationCapture();
        archiveIo.shutdownNow();
        gnomeBridge.close();
        CompletableFuture<MeasurementPreview> capture = calibrationCaptureTask;
        CompletableFuture<Void> captureDone = capture == null
                ? CompletableFuture.completedFuture(null)
                : capture.handle((preview, error) -> {
                    if (preview != null) deleteTemporaryCalibrationDirectory(preview.temporaryDirectory());
                    return null;
                });
        if (calibrationPreview != null) deleteTemporaryCalibrationDirectory(calibrationPreview.temporaryDirectory());
        closeFuture = CompletableFuture.allOf(routeManager.closeAsync(), captureDone).whenComplete((ignored, error) -> {
            calibrationIo.shutdownNow();
            if (calibrationPreview != null) deleteTemporaryCalibrationDirectory(calibrationPreview.temporaryDirectory());
        });
        return closeFuture;
    }

    public void close() { closeAsync(); }

    protected void exportArchive() {
        FileChooser chooser = new FileChooser(); chooser.setTitle(t("Экспортировать резервную копию", "Export backup"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Audio Matrix archive", "*.amx.zip")); chooser.setInitialFileName("audio-matrix.amx.zip");
        java.io.File selected = chooser.showSaveDialog(getScene().getWindow()); if (selected == null) return;
        Path destination = selected.toPath(); Map<String, Object> config = store.snapshot(); Path data = store.path().getParent();
        setNotice(t("Создаю ZIP-архив…", "Creating ZIP archive…"), false);
        archiveIo.execute(() -> { try { ConfigArchive.exportArchive(destination, config, data); Platform.runLater(() -> setNotice(t("Архив сохранён: ", "Archive saved: ") + destination, false)); }
            catch (Exception error) { Platform.runLater(() -> setNotice(t("Экспорт не выполнен: ", "Export failed: ") + rootMessage(error), true)); } });
    }

    protected void importArchive() {
        FileChooser chooser = new FileChooser(); chooser.setTitle(t("Выбрать резервную копию", "Choose backup"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Audio Matrix archive", "*.amx.zip", "*.zip"));
        java.io.File selected = chooser.showOpenDialog(getScene().getWindow()); if (selected == null) return;
        long expectedRevision = store.revision();
        Path archive = selected.toPath(); setNotice(t("Проверяю архив и измерения…", "Checking archive and measurements…"), false);
        archiveIo.execute(() -> { try { ConfigArchive.PreparedImport prepared = ConfigArchive.prepare(archive); Platform.runLater(() -> confirmArchiveImport(prepared, expectedRevision)); }
            catch (Exception error) { Platform.runLater(() -> setNotice(t("Архив не прошёл проверку: ", "Archive validation failed: ") + rootMessage(error), true)); } });
    }
}
