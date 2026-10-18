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

abstract class AppShellUiPart20 extends AppShellUiPart19 {
    protected AppShellUiPart20(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected void confirmArchiveImport(ConfigArchive.PreparedImport prepared, long expectedRevision) {
        List<ConfigArchive.ImportedDevice> unresolved = unresolvedImportedDevices(prepared);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION); confirm.initOwner(getScene().getWindow());
        confirm.setTitle(t("Предпросмотр импорта", "Import preview")); confirm.setHeaderText(t("Архив проверен", "Archive validated"));
        String deviceNote = unresolved.isEmpty() ? "" : "\n\n" + t("Устройства, не найденные однозначно: ", "Devices not found uniquely: ")
                + unresolved.stream().map(device -> device.name() + " (" + device.id() + ")").reduce((a, b) -> a + ", " + b).orElse("")
                + ". " + t("После подтверждения можно сопоставить их с текущими устройствами или оставить offline.", "After confirming, you can map them to current devices or leave them offline.");
        confirm.setContentText(t("Профилей: ", "Profiles: ") + prepared.profileCount() + "\n" + t("Маршрутов: ", "Routes: ") + prepared.routeCount() + "\n" + t("Файлов измерений: ", "Measurement files: ") + prepared.measurementCount() + "\n\n" +
                t("Продолжить? config.json будет заменён после проверки конфликта записи. При совпадении правил могут измениться громкость/mute устройств и потоков; если граф маршрутов сейчас применён или в архиве включено применение при запуске, маршруты могут обновиться.",
                        "Continue? config.json will be replaced after checking for concurrent edits. Matching rules may change device/stream volume or mute; an already-applied route graph or an archive with apply-on-start may refresh routes.") + deviceNote);
        confirm.showAndWait().filter(button -> button == javafx.scene.control.ButtonType.OK).ifPresentOrElse(button -> {
            if (unresolved.isEmpty()) installPreparedImport(prepared, Map.of(), List.of(), expectedRevision);
            else reviewDeviceRemapping(prepared, unresolved, expectedRevision);
        }, prepared::close);
    }

    protected List<ConfigArchive.ImportedDevice> unresolvedImportedDevices(ConfigArchive.PreparedImport prepared) {
        return prepared.deviceMappings().stream().filter(device -> matchingNodes(device).size() != 1).toList();
    }

    protected List<AudioNode> matchingNodes(ConfigArchive.ImportedDevice device) {
        Map<String,Object> selector = Map.of("kind", device.selectorKind(), "value", device.selectorValue());
        String direction = deviceDirection(device.role());
        List<AudioNode> candidates = switch (direction) {
            case "output" -> physicalSinks();
            case "input" -> snapshot.sources();
            default -> { List<AudioNode> all = new ArrayList<>(physicalSinks()); all.addAll(snapshot.sources()); yield all; }
        };
        return DeviceSelectorMatcher.findAll(candidates, selector);
    }

    protected List<AudioNode> matchingNodes(Map<String, Object> device) {
        if (device.get("identity") instanceof Map<?, ?>) {
            List<AudioNode> all = new ArrayList<>(physicalSinks());
            all.addAll(snapshot.sources());
            AudioNode resolved = DeviceDiscovery.resolve(all, device).node();
            return resolved == null ? List.of() : List.of(resolved);
        }
        Map<String, Object> selector = map(device.get("selector"));
        return matchingNodes(new ConfigArchive.ImportedDevice(String.valueOf(device.getOrDefault("id", "")),
                String.valueOf(device.getOrDefault("name", device.getOrDefault("id", "Device"))),
                string(device.get("role"), ""), string(selector.get("kind"), ""), string(selector.get("value"), "")));
    }

    protected List<ConfigArchive.DeviceTarget> availableDeviceTargets() {
        List<ConfigArchive.DeviceTarget> targets = new ArrayList<>();
        physicalSinks().forEach(node -> targets.add(new ConfigArchive.DeviceTarget("output", node.name(), node.identity())));
        snapshot.sources().forEach(node -> targets.add(new ConfigArchive.DeviceTarget("input", node.name(), node.identity())));
        return List.copyOf(targets);
    }

    protected List<ConfigArchive.DeviceTarget> deviceTargets(String role, List<ConfigArchive.DeviceTarget> all) {
        String direction = deviceDirection(role);
        return direction.isBlank() ? all : all.stream().filter(target -> direction.equals(target.kind())).toList();
    }

    protected void reviewDeviceRemapping(ConfigArchive.PreparedImport prepared, List<ConfigArchive.ImportedDevice> unresolved, long expectedRevision) {
        List<ConfigArchive.DeviceTarget> available = availableDeviceTargets();
        Dialog<Map<String, ConfigArchive.DeviceTarget>> dialog = new Dialog<>(); dialog.initOwner(getScene().getWindow());
        dialog.setTitle(t("Сопоставление устройств", "Device remapping"));
        dialog.setHeaderText(t("Некоторые устройства не найдены однозначно", "Some devices are missing or ambiguous"));
        ButtonType importButton = new ButtonType(t("Импортировать", "Import"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(importButton, javafx.scene.control.ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(720);
        VBox content = new VBox(10); content.setPadding(new Insets(8, 4, 4, 4));
        content.getChildren().add(label(t("Выберите текущий sink/source для каждого selector. Пустой выбор сохранит устройство offline. Сопоставление меняет только config.json; применение правил описано в предыдущем подтверждении.",
                "Choose a current sink/source for each selector. Leaving a row blank keeps it offline. Mapping changes config.json; possible live rule application was described in the previous confirmation."), "muted"));
        Map<String, ComboBox<ConfigArchive.DeviceTarget>> choices = new LinkedHashMap<>();
        for (ConfigArchive.ImportedDevice imported : unresolved) {
            int matches = matchingNodes(imported).size();
            String state = matches == 0 ? t("Не найден", "Not found") : t("Неоднозначен", "Ambiguous");
            VBox source = new VBox(3, label(imported.name(), "card-title"), label(imported.id() + " · " + imported.selectorKind() + " = " + imported.selectorValue() + " · " + state, "muted"));
            ComboBox<ConfigArchive.DeviceTarget> target = new ComboBox<>(); target.getItems().setAll(deviceTargets(imported.role(), available));
            target.setPromptText(t("Оставить offline", "Keep offline")); target.setAccessibleText(t("Новое устройство для ", "Replacement device for ") + imported.name());
            target.setCellFactory(list -> deviceTargetCell()); target.setButtonCell(deviceTargetCell()); target.setPrefWidth(320);
            HBox row = new HBox(12, source, target); row.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(source, Priority.ALWAYS); content.getChildren().add(row); choices.put(imported.id(), target);
        }
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(button -> {
            if (button != importButton) return null;
            Map<String, ConfigArchive.DeviceTarget> mapping = new LinkedHashMap<>();
            choices.forEach((id, choice) -> { if (choice.getValue() != null) mapping.put(id, choice.getValue()); });
            return mapping;
        });
        dialog.showAndWait().ifPresentOrElse(mapping -> installPreparedImport(prepared, mapping, available, expectedRevision), () -> {
            prepared.close(); setNotice(t("Импорт отменён.", "Import cancelled."), false);
        });
    }

    protected javafx.scene.control.ListCell<ConfigArchive.DeviceTarget> deviceTargetCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(ConfigArchive.DeviceTarget item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                List<AudioNode> nodes = "output".equals(item.kind()) ? physicalSinks() : snapshot.sources();
                AudioNode node = nodes.stream().filter(candidate -> candidate.name().equals(item.name())).findFirst().orElse(null);
                setText(node == null ? item.kind() + " · " + item.name() : node.description() + " · " + node.name());
            }
        };
    }

    protected void installPreparedImport(ConfigArchive.PreparedImport prepared, Map<String, ConfigArchive.DeviceTarget> remapping,
                                       List<ConfigArchive.DeviceTarget> available, long expectedRevision) {
        setNotice(t("Восстанавливаю конфигурацию и файлы измерений…", "Restoring config and measurement files…"), false);
        archiveIo.execute(() -> { try {
            if (!remapping.isEmpty()) prepared.remapDeviceSelectors(remapping, available);
            ConfigArchive.install(prepared, store.path().getParent(), store, expectedRevision);
            Platform.runLater(() -> { setNotice(t("Импорт завершён.", "Import complete."), false); showPage(Page.PROFILES); });
        } catch (Exception error) { Platform.runLater(() -> setNotice(t("Импорт не выполнен: ", "Import failed: ") + rootMessage(error), true)); }
        finally { prepared.close(); } });
    }

    protected void syncSystemMasterFromDefaultSink(PipeWireBackend.Snapshot current, Map<String, Object> config) {
        AudioNode mixerSink = current.sinks().stream()
                .filter(node -> PipeWireBackend.SYSTEM_MIXER_SINK_NAME.equals(node.name()))
                .filter(node -> node.id() == current.defaultSinkId()).findFirst().orElse(null);
        if (mixerSink == null) {
            initializedSystemMixerSinkId = -1;
            return;
        }
        Map<String, Object> mixer = map(config.get("mixer"));
        if (initializedSystemMixerSinkId != mixerSink.id()) {
            initializedSystemMixerSinkId = mixerSink.id();
            double volume = number(mixer.get("global_volume"), 1.0);
            boolean muted = Boolean.TRUE.equals(mixer.get("global_mute"));
            if (Double.isFinite(volume)) backend.setVolume(mixerSink.id(), volume);
            backend.setMute(mixerSink.id(), muted);
            return;
        }
        if (!Double.isFinite(mixerSink.volume())) return;
        double volume = Math.max(0, Math.min(1, mixerSink.volume()));
        double configured = number(mixer.get("global_volume"), 1.0);
        boolean muted = Boolean.TRUE.equals(mixer.get("global_mute"));
        if (Math.abs(volume - configured) <= 0.005 && muted == mixerSink.muted()) return;
        updateConfig(candidate -> {
            Map<String, Object> nextMixer = map(candidate.get("mixer"));
            if (Math.abs(volume - number(nextMixer.get("global_volume"), 1.0)) > 0.005)
                nextMixer.put("global_volume", Math.round(volume * 100.0) / 100.0);
            if (muted != Boolean.TRUE.equals(nextMixer.get("global_mute"))) nextMixer.put("global_mute", muted);
        });
    }

    protected void setGlobalMasterVolume(double value) {
        double level = Math.max(0, Math.min(1, value));
        updateConfig(candidate -> map(candidate.get("mixer")).put("global_volume", level));
        AudioNode mixerSink = snapshot.sinks().stream()
                .filter(node -> PipeWireBackend.SYSTEM_MIXER_SINK_NAME.equals(node.name()))
                .filter(node -> node.id() == snapshot.defaultSinkId()).findFirst().orElse(null);
        if (mixerSink != null) backend.setVolume(mixerSink.id(), level).thenRun(this::refreshBackend);
    }

    protected void setGlobalMasterMute(boolean muted) {
        updateConfig(candidate -> map(candidate.get("mixer")).put("global_mute", muted));
        AudioNode mixerSink = snapshot.sinks().stream()
                .filter(node -> PipeWireBackend.SYSTEM_MIXER_SINK_NAME.equals(node.name()))
                .filter(node -> node.id() == snapshot.defaultSinkId()).findFirst().orElse(null);
        if (mixerSink != null) backend.setMute(mixerSink.id(), muted).thenRun(this::refreshBackend);
    }

    protected void refreshBackend() { backend.refresh().thenAccept(next -> Platform.runLater(() -> { snapshot = next;syncSystemMasterFromDefaultSink(next,store.snapshot());updateMeterTarget();applyConfiguredDeviceRules(next,store.snapshot());applyConfiguredStreamRules(next,store.snapshot());maybeApplyConfiguredRoutes(store.snapshot(),next);showPage(currentPage); })); }

    protected void saveDefaultOutput(AudioNode sink) {
        updateConfig(config -> {Map<String,Object> device=ensureDevice(config,sink);activeProfile(config).put("default_output",device.get("id"));});
    }

    protected void saveDeviceVolume(AudioNode sink, double volume) {
        backend.setMute(sink.id(),false).thenCompose(ignored->backend.setVolume(sink.id(), volume));
        updateConfig(config -> {
            Map<String, Object> device = ensureDevice(config, sink);
            device.put("volume", volume);
            device.put("mute", false);
        });
    }

    protected void saveDeviceMute(AudioNode sink, boolean muted) {
        updateConfig(config -> { Map<String, Object> device = ensureDevice(config, sink); device.put("mute", muted); });
    }

    protected void saveAppVolume(AudioNode stream, double volume) {
        updateConfig(config -> { Map<String, Object> app = ensureApplication(config, stream); app.put("volume", volume); });
    }

    protected void saveAppMute(AudioNode stream, boolean muted) {
        updateConfig(config -> { Map<String, Object> app = ensureApplication(config, stream); app.put("mute", muted); });
    }
}
