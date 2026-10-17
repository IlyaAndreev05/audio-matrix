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

abstract class AppShellUiPart14 extends AppShellUiPart13 {
    protected AppShellUiPart14(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected void applyCalibrationFilterProposal(MeasurementPreview preview, CheckBox hardwareChecked) {
        try {
            if (preview == null || !calibrationPreviewSaved || preview.calibrationId() == null || preview.output() == null)
                throw new IllegalStateException(t("Сначала сохраните WAV и кривую калибровки.", "Save the calibration WAV and curve first."));
            if (!hardwareChecked.isSelected())
                throw new IllegalStateException(t("Сначала подтвердите проверку аппаратной цепи.", "Confirm the hardware-path check first."));
            if (selectedSink == null || selectedSink.id() != preview.output().id()
                    || physicalSinks().stream().noneMatch(node -> node.id() == preview.output().id()))
                throw new IllegalStateException(t("Выбранный выход изменился или отключён; выберите выход измерения снова.",
                        "The selected output changed or disconnected; reselect the measurement output."));
            if (preview.result().clipped() || calibrationFilterSuggestions.isEmpty())
                throw new IllegalStateException(t("Для этой кривой нет допустимых срезов.", "This curve has no eligible cuts."));
            String cuts = calibrationFilterSuggestions.stream().map(suggestion -> String.format(Locale.ROOT,
                    "%.1f Hz %+.1f dB Q %.1f", suggestion.frequencyHz(), suggestion.gainDb(), suggestion.q()))
                    .reduce((first, second) -> first + "; " + second).orElse("");
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.setTitle(t("Подтвердите коррекцию выхода", "Confirm output correction"));
            confirmation.setHeaderText(t("Сохранить и применить PEQ-срезы?", "Save and apply these PEQ cuts?"));
            confirmation.setContentText(t("Выход: %s\nСрезы: %s\nПредпросмотр — только математическая модель по относительной кривой. Если граф активен или настроено автоприменение, текущий звук на выходе может быть обработан этими фильтрами. Тестовый сигнал не воспроизводится. Усиление усилителя не измерено.",
                            "Output: %s\nCuts: %s\nThe preview is a mathematical model based on a relative response curve. If routes are active or configured for automatic application, current output audio may be processed with these filters. No test signal will play. Amplifier gain has not been measured.")
                    .formatted(preview.output().description(), cuts));
            if (getScene() != null && getScene().getWindow() != null) confirmation.initOwner(getScene().getWindow());
            confirmation.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            if (confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;

            String chainId = "calibration-" + UUID.randomUUID().toString().replace("-", "");
            String appliedRef = "dsp.route_chains." + chainId;
            CalibrationFilterRollback[] rollback = new CalibrationFilterRollback[1];
            setNotice(t("Сохраняю срезы в JSON-профиль…", "Saving cuts to the JSON profile…"), false);
            store.updateAsync(config -> {
                Map<String, Object> calibration = maps(config.get("calibrations")).stream()
                        .filter(item -> preview.calibrationId().equals(item.get("id"))).findFirst()
                        .orElseThrow(() -> new IllegalStateException("The saved calibration record no longer exists"));
                String deviceId = string(calibration.get("device"), "");
                Map<String, Object> device = maps(config.get("devices")).stream()
                        .filter(item -> deviceId.equals(item.get("id"))).findFirst()
                        .orElseThrow(() -> new IllegalStateException("The calibration output is not mapped to a saved device"));
                AudioNode resolved = DeviceSelectorMatcher.resolve(physicalSinks(), deviceId, maps(config.get("devices")));
                if (resolved == null || resolved.id() != preview.output().id())
                    throw new IllegalStateException("The saved device selector no longer resolves to the measured output");

                String previousRef = string(device.get("filters_ref"), "");
                Map<String, Object> dsp = map(config.get("dsp"));
                Map<String, Object> chains = map(dsp.get("route_chains"));
                dsp.put("route_chains", chains);
                Map<String, Object> previousChain = null;
                List<Map<String, Object>> combinedFilters = new ArrayList<>();
                if (!previousRef.isBlank()) {
                    String prefix = "dsp.route_chains.";
                    if (!previousRef.startsWith(prefix) || previousRef.length() == prefix.length())
                        throw new IllegalStateException("The output references an unsupported filter chain");
                    String previousId = previousRef.substring(prefix.length());
                    Map<String, Object> original = map(chains.get(previousId));
                    if (original.isEmpty()) throw new IllegalStateException("The output's existing filter chain is missing");
                    previousChain = copyCalibrationChain(original);
                    boolean originalEnabled = !Boolean.FALSE.equals(original.get("enabled"));
                    for (Map<String, Object> filter : maps(original.get("filters"))) {
                        Map<String, Object> copy = new LinkedHashMap<>(filter);
                        if (!originalEnabled) copy.put("enabled", false);
                        combinedFilters.add(copy);
                    }
                }
                for (CalibrationFilterAdvisor.Suggestion suggestion : calibrationFilterSuggestions) {
                    combinedFilters.add(DefaultConfigMap.map("type", "peaking",
                            "frequency_hz", Math.round(suggestion.frequencyHz() * 10.0) / 10.0,
                            "gain_db", suggestion.gainDb(), "q", suggestion.q(), "enabled", true));
                }
                Map<String, Object> appliedChain = DefaultConfigMap.map("enabled", true, "filters", combinedFilters);
                chains.put(chainId, appliedChain);
                device.put("filters_ref", appliedRef);
                calibration.put("filters_ref", appliedRef);
                calibration.put("rollback_filters_ref", previousRef.isBlank() ? null : previousRef);
                rollback[0] = new CalibrationFilterRollback(deviceId, preview.calibrationId(), previousRef,
                        previousChain, appliedRef, copyCalibrationChain(appliedChain), preview.output().description());
            }).whenComplete((ignoredResult, error) -> Platform.runLater(() -> {
                if (error != null) {
                    setNotice(t("Срезы не сохранены: ", "Cuts were not saved: ") + rootMessage(error), true);
                    return;
                }
                calibrationFilterRollback = rollback[0];
                setNotice(t("Срезы сохранены в JSON. Активный граф обновится по настройке применения маршрутов; тестовый сигнал не воспроизводился.",
                        "Cuts were saved in JSON. An active graph will refresh according to route-application settings; no test signal played."), false);
                showPage(Page.CALIBRATION);
            }));
        } catch (Exception invalid) { setNotice(rootMessage(invalid), true); }
    }

    protected void rollbackCalibrationFilterProposal() {
        CalibrationFilterRollback rollback = calibrationFilterRollback;
        if (rollback == null) return;
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle(t("Подтвердите откат фильтров", "Confirm filter rollback"));
        confirmation.setHeaderText(t("Вернуть прежнюю цепочку выхода?", "Restore the previous output filter chain?"));
        confirmation.setContentText(t("Выход: %s\nБудет восстановлена прежняя ссылка на фильтры. Если граф маршрутов активен, Audio Matrix обновит его после записи JSON.",
                        "Output: %s\nThe previous filter reference will be restored. If the route graph is active, Audio Matrix will refresh it after saving JSON.")
                .formatted(rollback.outputDescription()));
        if (getScene() != null && getScene().getWindow() != null) confirmation.initOwner(getScene().getWindow());
        confirmation.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        if (confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;

        setNotice(t("Возвращаю прежнюю цепочку…", "Restoring the previous filter chain…"), false);
        store.updateAsync(config -> {
            Map<String, Object> device = maps(config.get("devices")).stream()
                    .filter(item -> rollback.deviceId().equals(item.get("id"))).findFirst()
                    .orElseThrow(() -> new IllegalStateException("The calibration output device no longer exists"));
            if (!rollback.appliedRef().equals(device.get("filters_ref")))
                throw new IllegalStateException("The output filter reference changed after calibration; rollback was not applied");
            String chainId = rollback.appliedRef().substring("dsp.route_chains.".length());
            Map<String, Object> dsp = map(config.get("dsp"));
            Map<String, Object> chains = map(dsp.get("route_chains"));
            Map<String, Object> currentChain = map(chains.get(chainId));
            if (!Objects.equals(currentChain, rollback.appliedChain()))
                throw new IllegalStateException("The saved filter chain changed after calibration; rollback was not applied");
            if (rollback.previousRef().isBlank()) device.remove("filters_ref");
            else device.put("filters_ref", rollback.previousRef());
            Map<String, Object> calibration = maps(config.get("calibrations")).stream()
                    .filter(item -> rollback.calibrationId().equals(item.get("id"))).findFirst().orElse(null);
            if (calibration != null && rollback.appliedRef().equals(calibration.get("filters_ref"))) {
                if (rollback.previousRef().isBlank()) calibration.remove("filters_ref");
                else calibration.put("filters_ref", rollback.previousRef());
                calibration.remove("rollback_filters_ref");
            }
            if (!calibrationFilterRefUsedElsewhere(config, rollback.appliedRef(), rollback.deviceId(), rollback.calibrationId()))
                chains.remove(chainId);
        }).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) {
                setNotice(t("Откат не выполнен: ", "Rollback was not applied: ") + rootMessage(error), true);
                showPage(Page.CALIBRATION);
                return;
            }
            calibrationFilterRollback = null;
            setNotice(t("Прежняя цепочка восстановлена в JSON. Активный граф обновится согласно настройкам маршрутов.",
                    "The previous filter chain was restored in JSON. An active graph will refresh according to route settings."), false);
            showPage(Page.CALIBRATION);
        }));
    }

    protected void restoreCalibrationRollbackState(Map<String, Object> calibration) {
        if (!calibration.containsKey("rollback_filters_ref")) return;
        String appliedRef = string(calibration.get("filters_ref"), "");
        String previousRef = string(calibration.get("rollback_filters_ref"), "");
        String deviceId = string(calibration.get("device"), "");
        String calibrationId = string(calibration.get("id"), "");
        if (!appliedRef.startsWith("dsp.route_chains.") || deviceId.isBlank() || calibrationId.isBlank()) return;
        Map<String, Object> config = store.snapshot();
        Map<String, Object> device = maps(config.get("devices")).stream()
                .filter(item -> deviceId.equals(item.get("id"))).findFirst().orElse(null);
        if (device == null || !appliedRef.equals(device.get("filters_ref"))) return;
        String chainId = appliedRef.substring("dsp.route_chains.".length());
        Map<String, Object> chains = map(map(config.get("dsp")).get("route_chains"));
        Map<String, Object> appliedChain = map(chains.get(chainId));
        if (appliedChain.isEmpty()) return;
        Map<String, Object> oldChain = null;
        if (!previousRef.isBlank() && previousRef.startsWith("dsp.route_chains."))
            oldChain = map(chains.get(previousRef.substring("dsp.route_chains.".length())));
        AudioNode output = resolveDevice(config, device);
        String description = output == null
                ? string(map(device.get("selector")).get("value"), string(device.get("name"), deviceId))
                : output.description();
        calibrationFilterRollback = new CalibrationFilterRollback(deviceId, calibrationId, previousRef,
                oldChain == null ? null : copyCalibrationChain(oldChain), appliedRef,
                copyCalibrationChain(appliedChain), description);
    }

    protected void discardTemporaryCalibrationPreview() {
        if (calibrationPreview == null) return;
        if (calibrationPreview.temporaryDirectory() != null && !calibrationPreviewSaved) {
            deleteTemporaryCalibrationDirectory(calibrationPreview.temporaryDirectory());
            if (calibrationPreview.source().equals(calibrationRecordingPath)) calibrationRecordingPath = null;
        }
        calibrationPreview = null;
        calibrationPreviewSaved = false;
        calibrationComparisonPreview = null;
        calibrationFilterSuggestions = List.of(); calibrationFilterPreviewShown = false;
    }
}
