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

    protected void maybeApplyConfiguredRoutes(Map<String, Object> config, PipeWireBackend.Snapshot current) {
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
            }
            return;
        }
        if (!routesRequested && !autoApply) return;
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
        long generation = backend.connectionGeneration();
        String signature = routeInputSignature(config, current, generation);
        if (!routeApplyGate.request(signature, generation, force)) return null;
        try {
            Map<String, Object> runtimeConfig = runtimeDspConfig(config, current);
            return reconnect ? routeManager.reapplyAfterReconnect(runtimeConfig, current)
                    : routeManager.apply(runtimeConfig, current);
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
        Map<String,Object> graphConfig=runtimeGraphConfig(config);
        if (!dspReferenceMode) return graphConfig;
        double matchDb;
        try { matchDb = Math.max(0, routeManager.maximumEqBoostDb(graphConfig, current, selectedSink)); }
        catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        return DspComparisonConfig.reference(graphConfig, matchDb);
    }

    protected Map<String,Object> runtimeGraphConfig(Map<String,Object> config){String active=string(config.get("active_route"),"");if(active.isBlank())return config;Map<String,Object> route=maps(config.get("route_library")).stream().filter(item->active.equals(item.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Active graph route is missing: "+active));return GraphPipeWireCompiler.compile(GraphCodec.fromMap(route),config);}

    protected String routeInputSignature(Map<String, Object> config, PipeWireBackend.Snapshot current, long generation) {
        return RouteInputSignature.create(config, current, generation);
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closeFuture != null) return closeFuture;
        closing = true;
        meterRefresh.stop();
        if (routeRefresh != null) routeRefresh.stop();
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
