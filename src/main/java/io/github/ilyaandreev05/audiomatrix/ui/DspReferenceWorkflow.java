package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteManager;
import io.github.ilyaandreev05.audiomatrix.audio.RouteApplyGate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A/B reference-mode workflow: PipeWire refresh, one gate-owned route apply, and FX-only notices.
 *
 * <p>Audio work stays on {@code runtimeAudio}; the confirmation and retry of the same route-apply
 * gate also run there, so the JavaFX thread only owns widgets, notices, and page repaints.</p>
 */
final class DspReferenceWorkflow {
    private final AppShellUiPart19 shell;

    DspReferenceWorkflow(AppShellUiPart19 shell) {
        this.shell = shell;
    }

    void setReferenceMode(boolean referenceMode) {
        if (shell.dspReferenceUpdateInProgress) return;
        if (!shell.routeManager.active()) {
            shell.setNotice(shell.t("Сначала примените граф маршрутов.", "Apply the route graph first."), true);
            shell.showPage(shell.currentPage);
            return;
        }
        boolean previous = shell.dspReferenceMode;
        shell.dspReferenceUpdateInProgress = true;
        shell.dspReferenceMode = referenceMode;
        shell.referenceSinkForDsp(shell.selectedSink);
        if (shell.dspReferenceToggle != null) shell.dspReferenceToggle.setDisable(true);
        shell.setNotice(shell.t("Обновляю PipeWire для A/B…", "Updating PipeWire for A/B…"), false);
        AtomicBoolean requested = new AtomicBoolean();
        shell.onRuntimeAudio(() -> {
            CompletableFuture<Boolean> outcome = new CompletableFuture<>();
            shell.backend.refresh().whenComplete((current, refreshError) -> shell.runRuntimeAudio(() -> {
                if (refreshError != null) {
                    outcome.completeExceptionally(refreshError);
                    return;
                }
                if (current.capturedAtMillis() >= shell.snapshot.capturedAtMillis()) shell.snapshot = current;
                CompletableFuture<PipeWireRouteManager.ApplyReport> apply = shell.requestRouteApply(shell.store.snapshot(), current, true, false);
                if (apply == null) {
                    outcome.complete(false);
                    return;
                }
                requested.set(true);
                apply.whenComplete((report, error) -> {
                    if (error != null) outcome.completeExceptionally(error);
                    else outcome.complete(true);
                });
            }));
            return outcome;
        }).whenComplete((applied, error) -> shell.runRuntimeAudio(() -> {
            RouteApplyGate.Completion completion = requested.get() ? shell.finishRouteApply(error == null) : null;
            shell.dspReferenceUpdateInProgress = false;
            if (error != null) {
                shell.dspReferenceMode = previous;
                shell.routesRequested = true;
                System.err.println("Audio Matrix A/B route apply failed: " + AppShellBase.rootMessage(error));
            } else if (Boolean.FALSE.equals(applied)) {
                shell.dspReferenceMode = previous;
            } else {
                shell.routesRequested = shell.routeManager.active();
            }
            if (completion != null) shell.retryPendingRouteApply(completion);
            shell.runOnFx(() -> {
                if (shell.dspReferenceToggle != null) shell.dspReferenceToggle.setDisable(false);
                if (error != null) shell.setNotice(shell.t("A/B не применён: ", "A/B was not applied: ") + AppShellBase.rootMessage(error), true);
                else if (Boolean.FALSE.equals(applied))
                    shell.setNotice(shell.t("Граф уже обновляется; режим A/B не изменён.", "The graph is already updating; A/B mode was not changed."), true);
                else shell.setNotice(referenceMode
                        ? shell.t("B включён: EQ обойдён, уровень снижен по расчётной верхней оценке.", "B is on: EQ bypassed and level reduced by the conservative gain estimate.")
                        : shell.t("A включён: обработанная EQ-цепочка восстановлена.", "A is on: the processed EQ chain is restored."), false);
                shell.showPage(shell.currentPage);
            });
        }));
    }
}
