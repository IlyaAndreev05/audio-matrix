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

abstract class AppShellUiPart25 extends AppShellUiPart24 {
    protected AppShellUiPart25(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected void createRouteChain() {
        TextInputDialog dialog = new TextInputDialog("route-eq");
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle(t("Создать route EQ chain", "Create route EQ chain"));
        dialog.setHeaderText(t("Имя цепочки — латинские буквы, цифры, _ или -.", "Chain ID may use letters, digits, _ or -."));
        dialog.setContentText(t("ID цепочки", "Chain ID"));
        dialog.showAndWait().map(String::trim).filter(id -> !id.isBlank()).ifPresent(rawId -> {
            String id = rawId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
            if (id.isBlank()) { setNotice(t("ID цепочки должен содержать буквы или цифры.", "Chain ID must contain letters or digits."), true); return; }
            Map<String, Object> current = store.snapshot();
            if (map(map(current.get("dsp")).get("route_chains")).containsKey(id)) {
                setNotice(t("Цепочка с таким ID уже существует.", "A chain with that ID already exists."), true); return;
            }
            updateConfig(config -> map(map(config.get("dsp")).get("route_chains")).put(id,
                    DefaultConfigMap.map("enabled", true, "filters", new ArrayList<>())));
        });
    }

    protected void deleteRouteChain(String id) {
        updateConfig(config -> {
            map(map(config.get("dsp")).get("route_chains")).remove(id);
            for (Map<String, Object> route : maps(config.get("routes")))
                if (("dsp.route_chains." + id).equals(route.get("filters_ref"))) route.remove("filters_ref");
            for (Map<String, Object> application : maps(config.get("applications")))
                if (("dsp.route_chains." + id).equals(application.get("filters_ref"))) application.remove("filters_ref");
            for (Map<String, Object> device : maps(config.get("devices")))
                if (("dsp.route_chains." + id).equals(device.get("filters_ref"))) device.remove("filters_ref");
        });
    }

    protected void updateRouteChain(String id, Consumer<Map<String, Object>> change) {
        updateConfig(config -> {
            Object value = map(map(config.get("dsp")).get("route_chains")).get(id);
            if (value instanceof Map<?, ?>) change.accept(map(value));
        });
    }

    protected void editRouteFilter(String chainId, int index, Map<String, Object> existing) {
        Map<String, Object> source = existing == null
                ? DefaultConfigMap.map("type", "notch", "frequency_hz", 80, "gain_db", -2.0, "q", 2.0, "enabled", true)
                : new LinkedHashMap<>(existing);
        Dialog<javafx.scene.control.ButtonType> dialog = new Dialog<>();
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle(existing == null ? t("Добавить фильтр", "Add filter") : t("Изменить фильтр", "Edit filter"));
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        ComboBox<String> type = new ComboBox<>();
        type.getItems().setAll("peaking", "notch", "low_shelf", "high_shelf", "high_pass", "low_pass", "band_pass");
        type.setValue(string(source.get("type"), "notch"));
        type.setAccessibleText(t("Тип фильтра", "Filter type"));
        Label typeLabel = label(t("Тип фильтра", "Filter type"), "muted"); typeLabel.setLabelFor(type);
        TextField frequency = field(String.valueOf(number(source.get("frequency_hz"), 80)), t("Частота (Hz)", "Frequency (Hz)"));
        Label frequencyLabel = label(t("Частота в Hz (0–96000)", "Frequency in Hz (0–96000)"), "muted"); frequencyLabel.setLabelFor(frequency);
        double maximumEqGain = limiterEnabled(store.snapshot()) ? 24 : 0;
        TextField gain = field(String.valueOf(number(source.get("gain_db"), -2)), maximumEqGain > 0
                ? t("Gain (dB, от -24 до +24)", "Gain (-24 to +24 dB)")
                : t("Срез (dB, от -24 до 0)", "Cut (-24 to 0 dB)"));
        Label gainLabel = label(t("Gain (dB)", "Gain (dB)"), "muted"); gainLabel.setLabelFor(gain);
        Label gainHelp = label(maximumEqGain > 0
                ? t("Положительный gain использует цифровой limiter на выходе", "Positive gain uses the digital output limiter")
                : t("Положительный gain доступен только с включённым limiter", "Positive gain is available only with the limiter enabled"), "muted");
        String sourceType = string(source.get("type"), "notch");
        double defaultQ = isPassFilter(sourceType) ? 0.70710678 : 1.0;
        TextField q = field(String.valueOf(number(source.get("q"), defaultQ)), "Q");
        Label qLabel = label(t("Q (0–20)", "Q (0–20)"), "muted"); qLabel.setLabelFor(q);
        ComboBox<Integer> slope = new ComboBox<>();
        slope.getItems().setAll(6, 12, 18, 24, 36, 48);
        slope.setValue((int) number(source.get("slope_db_oct"), 12));
        slope.setAccessibleText(t("Наклон high-pass/low-pass в dB на октаву", "High-pass/low-pass slope in dB per octave"));
        Label slopeLabel = label(t("Наклон (dB/oct)", "Slope (dB/oct)"), "muted"); slopeLabel.setLabelFor(slope);
        ComboBox<String> channel = new ComboBox<>();
        channel.getItems().setAll("all", "FL", "FR", "MONO");
        List<String> sourceChannels = stringList(source.get("channels"));
        channel.setValue(sourceChannels.size() == 1 ? sourceChannels.getFirst() : "all");
        channel.setAccessibleText(t("Канал применения фильтра", "Filter channel scope"));
        Label channelLabel = label(t("Канал: все или один", "Channel: all or one"), "muted"); channelLabel.setLabelFor(channel);
        CheckBox enabled = new CheckBox(t("Фильтр включён", "Filter enabled"));
        enabled.setSelected(!Boolean.FALSE.equals(source.get("enabled")));
        Label error = label("", "danger");
        VBox content = new VBox(8,
                typeLabel, type, frequencyLabel, frequency, gainLabel, gainHelp, gain,
                slopeLabel, slope, qLabel, q, channelLabel, channel, enabled, error);
        dialog.getDialogPane().setContent(content);
        styleDialog(dialog);
        Button save = (Button) dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK);
        Runnable updateFilterControls = () -> {
            boolean pass = isPassFilter(type.getValue());
            boolean hasSlope = pass;
            slopeLabel.setVisible(hasSlope); slopeLabel.setManaged(hasSlope);
            slope.setVisible(hasSlope); slope.setManaged(hasSlope);
            gainLabel.setVisible(!pass); gainLabel.setManaged(!pass);
            gainHelp.setVisible(!pass); gainHelp.setManaged(!pass);
            gain.setVisible(!pass); gain.setManaged(!pass);
            boolean qApplies = !pass || Integer.valueOf(12).equals(slope.getValue());
            boolean qVisible = !pass || !Integer.valueOf(6).equals(slope.getValue());
            qLabel.setVisible(qVisible); qLabel.setManaged(qVisible);
            q.setVisible(qVisible); q.setManaged(qVisible);
            q.setDisable(!qApplies);
            qLabel.setText(pass && !qApplies
                    ? t("Q задан профилем Butterworth", "Q is set by the Butterworth slope")
                    : t("Q (0–20)", "Q (0–20)"));
        };
        Runnable validate = () -> {
            String issue = "";
            try {
                double hz = Double.parseDouble(frequency.getText().trim());
                boolean pass = isPassFilter(type.getValue());
                boolean qApplies = !pass || Integer.valueOf(12).equals(slope.getValue());
                double db = pass ? 0 : Double.parseDouble(gain.getText().trim());
                double quality = q.isDisabled() ? number(source.get("q"), defaultQ) : Double.parseDouble(q.getText().trim());
                if (type.getValue() == null) issue = t("Выберите тип фильтра.", "Choose a filter type.");
                else if (!Double.isFinite(hz) || hz <= 0 || hz > 96000) issue = t("Частота должна быть больше 0 и не выше 96000 Hz.", "Frequency must be above 0 and at most 96000 Hz.");
                else if (pass && slope.getValue() == null) issue = t("Выберите наклон среза.", "Choose a filter slope.");
                else if (!Double.isFinite(db) || db < -24 || db > maximumEqGain) issue = maximumEqGain > 0
                        ? t("Gain должен быть от -24 до +24 dB.", "Gain must be between -24 and +24 dB.")
                        : t("Срез должен быть от -24 до 0 dB.", "Cut must be between -24 and 0 dB.");
                else if (qApplies && (!Double.isFinite(quality) || quality <= 0 || quality > 20)) issue = t("Q должен быть больше 0 и не выше 20.", "Q must be above 0 and at most 20.");
            } catch (NumberFormatException invalid) { issue = t("Частота, срез и Q должны быть числами.", "Frequency, cut, and Q must be numbers."); }
            error.setText(issue); error.setManaged(!issue.isEmpty()); error.setVisible(!issue.isEmpty()); save.setDisable(!issue.isEmpty());
        };
        type.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (isPassFilter(newValue) && !isPassFilter(oldValue)) q.setText("0.70710678");
            updateFilterControls.run(); validate.run();
        });
        slope.valueProperty().addListener((observable, oldValue, newValue) -> { updateFilterControls.run(); validate.run(); });
        frequency.textProperty().addListener((observable, oldValue, newValue) -> validate.run());
        gain.textProperty().addListener((observable, oldValue, newValue) -> validate.run());
        q.textProperty().addListener((observable, oldValue, newValue) -> validate.run());
        updateFilterControls.run();
        validate.run();
        dialog.showAndWait().filter(javafx.scene.control.ButtonType.OK::equals).ifPresent(result -> {
            double hz = Double.parseDouble(frequency.getText().trim());
            boolean pass = isPassFilter(type.getValue());
            Map<String, Object> filter = DefaultConfigMap.map("type", type.getValue(), "frequency_hz", hz,
                    "enabled", enabled.isSelected());
            if (pass) {
                filter.put("slope_db_oct", slope.getValue());
                if (Integer.valueOf(12).equals(slope.getValue())) filter.put("q", Double.parseDouble(q.getText().trim()));
            } else {
                filter.put("gain_db", Double.parseDouble(gain.getText().trim()));
                filter.put("q", Double.parseDouble(q.getText().trim()));
            }
            if (channel.getValue() != null && !"all".equals(channel.getValue())) filter.put("channels", List.of(channel.getValue()));
            updateRouteChain(chainId, chain -> {
                List<Map<String, Object>> filters = maps(chain.get("filters"));
                if (index < 0) filters.add(filter);
                else if (index < filters.size()) filters.set(index, filter);
            });
        });
    }

    protected void updateFilter(int index, String key, Object value) {
        updateConfig(config -> { List<Map<String, Object>> filters = maps(map(map(config.get("dsp")).get("global_eq")).get("filters")); if (index >= 0 && index < filters.size()) filters.get(index).put(key, value); });
    }

    protected void removeFilter(int index) {
        updateConfig(config -> { List<Map<String, Object>> filters = maps(map(map(config.get("dsp")).get("global_eq")).get("filters")); if (index >= 0 && index < filters.size()) filters.remove(index); });
    }

    protected void setEqEnabled(boolean enabled) { updateConfig(config -> map(map(config.get("dsp")).get("global_eq")).put("enabled", enabled)); }

    protected void activateProfile(String id) {
        Map<String,Object> config=store.snapshot();Map<String, Object> profile = maps(config.get("profiles")).stream().filter(p -> id.equals(p.get("id"))).findFirst().orElse(null);
        if (profile == null) return;
        updateRouteConfig(t("Активировать профиль и его маршрут", "Activate profile and its route"),updated -> selectedGraphRouteId=GraphProfileLibrary.activateProfile(updated,id));
        AudioNode output=DeviceSelectorMatcher.resolve(physicalSinks(),string(profile.get("default_output"),"system_default"),maps(config.get("devices")));
        if(output!=null)selectedSink=output;
    }

    protected void copyProfile(String sourceId) {
        Map<String,Object> source=maps(store.snapshot().get("profiles")).stream().filter(profile->sourceId.equals(profile.get("id"))).findFirst().orElse(null);
        if(source==null)return;
        TextInputDialog dialog=new TextInputDialog(string(source.get("name"),"Profile")+" (копия)");
        dialog.setTitle(t("Дублировать профиль", "Duplicate profile"));
        dialog.setHeaderText(t("Создать отдельные копии его групп и графов", "Create separate copies of its groups and graphs"));
        dialog.setContentText(t("Название", "Name"));styleDialog(dialog);
        dialog.showAndWait().map(String::trim).filter(name->!name.isBlank()).ifPresent(name->updateRouteConfig(t("Дублировать профиль и графы", "Duplicate profile and graphs"),config->GraphProfileLibrary.duplicateProfile(config,sourceId,name)));
    }
}
