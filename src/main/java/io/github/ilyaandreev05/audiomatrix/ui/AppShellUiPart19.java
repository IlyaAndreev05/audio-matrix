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
    private final DynamicRouteRuntime dynamicRuntime;
    private final DspReferenceWorkflow dspReference = new DspReferenceWorkflow(this);
    private final AtomicReference<CompletableFuture<Void>> activeApply =
            new AtomicReference<>(CompletableFuture.completedFuture(null));

    protected AppShellUiPart19(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
        dynamicRuntime = new DynamicRouteRuntime(store);
    }

    protected void maybeApplyConfiguredRoutes(Map<String, Object> config, PipeWireBackend.Snapshot current) {
        dynamicRuntime.watch(browserAdapters, this::browserStateChanged);
        boolean autoApply = Boolean.TRUE.equals(map(config.get("engine")).get("apply_on_start")) && !autoApplySuppressedUntilRestart;
        if (!current.connected() || (!routesRequested && !autoApply)) {
            if (!current.connected() && routeManager.active()) applyConfiguredRoutes(config, current, false, "Audio Matrix route apply failed while PipeWire was offline: ");
            else refreshDynamicGraph(config, current);
            return;
        }
        applyConfiguredRoutes(config, current, backend.connectionGeneration() != lastAppliedConnectionGeneration, "Audio Matrix automatic route apply failed: ");
    }

    private void applyConfiguredRoutes(Map<String,Object> config, PipeWireBackend.Snapshot current, boolean reconnect, String failure) {
        CompletableFuture<PipeWireRouteManager.ApplyReport> apply = requestRouteApply(config, current, false, reconnect);
        if (apply == null) return;
        apply.whenComplete((report, error) -> runRuntimeAudio(() -> {
            RouteApplyGate.Completion completion = finishRouteApply(error == null);
            if (error != null) {
                routesRequested = true; System.err.println(failure + rootMessage(error));
                runOnFx(() -> setNotice(t("Не удалось обновить применённый граф: ", "Could not update the applied graph: ") + rootMessage(error), true));
            } else {
                routesRequested = routeManager.active();
                String status = routeManager.status();
                runOnFx(() -> { if (currentPage == Page.ROUTES) setNotice(status, false); });
            }
            retryPendingRouteApply(completion);
            requestUiRefresh();
        }));
    }

    protected CompletableFuture<PipeWireRouteManager.ApplyReport> requestRouteApply(
            Map<String, Object> config, PipeWireBackend.Snapshot current, boolean force, boolean reconnect) {
        return beginRouteApply(config, current, force, reconnect, true);
    }

    private CompletableFuture<PipeWireRouteManager.ApplyReport> beginRouteApply(
            Map<String,Object> config, PipeWireBackend.Snapshot current, boolean force,
            boolean reconnect, boolean persistRegistryChanges) {
        long generation = backend.connectionGeneration();
        try {
            DynamicRouteRuntime.Prepared prepared = dynamicRuntime.prepareNow(config, current, browserAdapters, generation);
            if (!routeApplyGate.request(prepared.signature(), generation, force)) return null;
            activeApply.set(new CompletableFuture<>());
            return dynamicRuntime.persist(config, prepared, persistRegistryChanges).thenCompose(ignored -> {
                Map<String,Object> runtime = applyDspReference(prepared.runtimeConfig(), current);
                CompletableFuture<PipeWireRouteManager.ApplyReport> graph = reconnect ? routeManager.reapplyAfterReconnect(runtime, current, prepared.bindings()) : routeManager.apply(runtime, current, prepared.bindings());
                CompletableFuture<Void> rules = streamRuleApplier.apply(current, maps(runtime.get("applications")), prepared.bindings(), force,
                        (stream, error) -> runOnFx(() -> setNotice(t("Не удалось применить правило приложения ", "Could not apply application rule for ") + stream.applicationName() + ": " + rootMessage(error), true)));
                return graph.thenCombine(rules, (report, ignoredRules) -> report);
            }).thenApply(report -> {
                if (prepared.reconcile() != null) setDynamicGraphState(prepared.routeId(), prepared.reconcile());
                publishGnomeState();
                String warning = dynamicRuntime.diagnostic();
                if (!warning.isBlank()) runOnFx(() -> setNotice(warning, true));
                return report;
            });
        } catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
    }

    protected CompletableFuture<?> applyRuntimeGraph(Map<String,Object> config, PipeWireBackend.Snapshot current, boolean force, boolean reconnect, boolean persistRegistryChanges) {
        return onRuntimeAudio(() -> applyRuntimeGraphAwait(config, current, force, reconnect, persistRegistryChanges));
    }

    private CompletableFuture<PipeWireRouteManager.ApplyReport> applyRuntimeGraphAwait(Map<String,Object> config, PipeWireBackend.Snapshot current, boolean force, boolean reconnect, boolean persistRegistryChanges) {
        CompletableFuture<PipeWireRouteManager.ApplyReport> apply = beginRouteApply(config, current, force, reconnect, persistRegistryChanges);
        if (apply != null) return apply.whenComplete((report, error) -> finishRuntimeGraphApply(config, error == null));
        CompletableFuture<Void> pending = activeApply.get();
        return pending.isDone() ? CompletableFuture.completedFuture(null) : pending.thenCompose(ignored -> applyRuntimeGraphAwait(config, current, force, reconnect, persistRegistryChanges));
    }

    private void finishRuntimeGraphApply(Map<String,Object> config, boolean succeeded) {
        RouteApplyGate.Completion completion = completeRouteApply(succeeded, config, backend.current());
        retryPendingRouteApply(completion);
    }

    private void browserStateChanged() {
        runRuntimeAudio(() -> maybeApplyConfiguredRoutes(store.snapshot(), snapshot));
    }

    private void refreshDynamicGraph(Map<String,Object> config, PipeWireBackend.Snapshot current) {
        dynamicRuntime.reconcileOnly(config, current, browserAdapters, backend.connectionGeneration()).whenComplete((result, error) -> {
            if (error != null) { System.err.println("Audio Matrix dynamic graph reconciliation failed: " + rootMessage(error)); return; }
            if (result == null) return;
            runRuntimeAudio(() -> {
                setDynamicGraphState(result.configuredGraph().id(), result);
                publishGnomeState();
                String diagnostic = dynamicRuntime.diagnostic();
                if (!diagnostic.isBlank()) runOnFx(() -> setNotice(diagnostic, true));
            });
        });
    }

    protected RouteApplyGate.Completion finishRouteApply(boolean succeeded) { return completeRouteApply(succeeded, store.snapshot(), backend.current()); }

    private RouteApplyGate.Completion completeRouteApply(boolean succeeded, Map<String,Object> config, PipeWireBackend.Snapshot current) {
        long generation = backend.connectionGeneration();
        RouteApplyGate.Completion completion = routeApplyGate.complete(succeeded, routeInputSignature(config, current, generation), generation);
        lastAppliedConnectionGeneration = generation; activeApply.getAndSet(CompletableFuture.completedFuture(null)).complete(null);
        return completion;
    }

    protected void retryPendingRouteApply(RouteApplyGate.Completion completion) {
        if (completion.forcePending()) runRuntimeAudio(this::applyRoutes);
        else if (completion.retryPending()) runRuntimeAudio(() -> maybeApplyConfiguredRoutes(store.snapshot(), snapshot));
    }

    protected void setDspReferenceMode(boolean referenceMode) { dspReference.setReferenceMode(referenceMode); }

    protected Map<String, Object> runtimeDspConfig(Map<String, Object> config, PipeWireBackend.Snapshot current) { return applyDspReference(runtimeGraphConfig(config), current); }

    private Map<String,Object> applyDspReference(Map<String,Object> graphConfig, PipeWireBackend.Snapshot current) {
        if (!dspReferenceMode) return graphConfig;
        try { return DspComparisonConfig.reference(graphConfig, Math.max(0, routeManager.maximumEqBoostDb(graphConfig, current, dspReferenceSink()))); }
        catch (Exception error) { throw new CompletionException(error); }
    }

    protected Map<String,Object> runtimeGraphConfig(Map<String,Object> config) {
        return dynamicRuntime.prepareNow(config, snapshot, browserAdapters, backend.connectionGeneration()).runtimeConfig();
    }

    protected io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry.ReconcileResult currentMixerGraph(
            Map<String,Object> config) {
        return dynamicRuntime.reconcileNow(config, snapshot, browserAdapters, backend.connectionGeneration());
    }

    protected String routeInputSignature(Map<String, Object> config, PipeWireBackend.Snapshot current, long generation) { return dynamicRuntime.signature(config, current, browserAdapters, generation); }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closeFuture != null) return closeFuture;
        closing = true;
        meterRefresh.stop();
        if (routeRefresh != null) routeRefresh.cancel(false);
        backend.monitorOutput(null);
        backend.monitorInput(null);
        backend.cancelCalibrationCapture();
        archiveIo.shutdownNow();
        shutdownRuntimeLanes();
        dynamicRuntime.close();
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
