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

abstract class AppShellUiPart08 extends AppShellUiPart07 {
    protected AppShellUiPart08(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }

    protected VBox dspPage() {
        VBox page = page(t("Конфигурация коррекции хранится отдельно по уровням.", "Correction settings are stored separately by level."));
        VBox status = card(t("Цепочка обработки", "Processing chain"));
        boolean processingActive = routeManager.active() && routeManager.status().contains("PipeWire filters");
        status.getChildren().addAll(label(t("Порядок по умолчанию: поток → шина → маршрут и EQ-chain → коррекция выхода → limiter.", "Default order: stream → bus → route and EQ chain → output correction → limiter."), ""),
                label(processingActive ? routeManager.status()
                                : routeManager.active()
                                ? t("PipeWire-граф активен; дополнительные фильтры выхода выключены.", "PipeWire graph is active; output processing is disabled.")
                                : t("Выходная filter-chain применяется вместе с активными маршрутами.", "The output filter-chain is applied with active routes."),
                        processingActive ? "accent" : "warning"),
                label(t("High/low-pass поддерживают уклоны 6, 12, 18, 24, 36 и 48 dB/oct; для уклонов выше 12 используются секции Butterworth. Limiter ограничивает цифровой пик, но не мощность усилителя.", "High/low-pass filters support 6, 12, 18, 24, 36, and 48 dB/oct; slopes above 12 use Butterworth sections. The limiter caps digital peaks, not amplifier power."), "muted"));
        page.getChildren().add(status);
        VBox meter = card(t("Фактический уровень PipeWire на выходе", "Measured PipeWire output level"));
        outputMeterValue = label(meterText(), "stat-value");
        meter.getChildren().addAll(outputMeterValue,
                label(t("Читается только monitor-поток выбранного sink; PCM не сохраняется. Это цифровой уровень PipeWire, не напряжение усилителя, мощность динамика или акустический SPL.",
                                "Reads only the selected sink's monitor stream; PCM is not stored. This is a PipeWire digital level, not amplifier voltage, transducer power, or acoustic SPL."), "muted"));
        page.getChildren().add(meter);
        VBox inputMeter = card(t("Фактический уровень входа", "Measured input level"));
        ComboBox<AudioNode> sources = new ComboBox<>(); sources.getItems().setAll(snapshot.sources());
        sources.setMaxWidth(Double.MAX_VALUE); sources.setPromptText(t("Выбрать вход", "Choose an input"));
        sources.setAccessibleText(t("Вход для цифрового level meter", "Input for digital level meter"));
        sources.setCellFactory(list -> nodeCell()); sources.setButtonCell(nodeCell());
        if (selectedInputSource != null) {
            AudioNode currentSource = snapshot.sources().stream().filter(node -> node.name().equals(selectedInputSource.name())).findFirst().orElse(null);
            if (currentSource != null) sources.getSelectionModel().select(currentSource);
        }
        sources.setOnAction(event -> {
            selectedInputSource = sources.getValue();
            if (inputMeterEnabled) backend.monitorInput(selectedInputSource);
        });
        CheckBox inputCapture = new CheckBox(t("Включить чтение входного уровня", "Enable input level monitoring"));
        inputCapture.setSelected(inputMeterEnabled);
        inputCapture.setOnAction(event -> {
            if (inputCapture.isSelected() && selectedInputSource == null) {
                inputCapture.setSelected(false);
                setNotice(t("Выберите вход перед включением meter-а.", "Choose an input before enabling the meter."), true);
                return;
            }
            inputMeterEnabled = inputCapture.isSelected();
            backend.monitorInput(inputMeterEnabled ? selectedInputSource : null);
            refreshMeterLabel();
        });
        inputMeterValue = label(inputMeterText(), "stat-value");
        inputMeter.getChildren().addAll(sources, inputCapture, inputMeterValue,
                label(t("Чтение входа запускается только по переключателю выше; PCM сразу отбрасывается и не сохраняется.",
                        "Input reading starts only when enabled above; PCM is discarded immediately and never stored."), "muted"));
        page.getChildren().add(inputMeter);
        Map<String, Object> config = store.snapshot(); Map<String, Object> dsp = map(config.get("dsp"));
        VBox headroom = card(t("Расчёт headroom", "Headroom estimate"));
        PipeWireRouteManager.HeadroomEstimate estimate;
        try { estimate = routeManager.estimateHeadroom(config, snapshot, selectedSink); }
        catch (Exception error) { estimate = new PipeWireRouteManager.HeadroomEstimate(false, Double.NaN, Double.NaN, Double.NaN, 0, rootMessage(error)); }
        if (estimate.available()) {
            headroom.getChildren().add(label(t("До limiter: ", "Before limiter: ") + String.format(Locale.ROOT, "%.1f dBFS", estimate.preLimiterPeakDbfs())
                    + " · " + t("после limiter (расчётный предел): ", "after limiter (estimated ceiling): ")
                    + String.format(Locale.ROOT, "%.1f dBFS", estimate.postLimiterPeakDbfs())
                    + (estimate.worstCaseReductionDb() > 0.05 ? " · " + t("снижение ", "reduction ") + String.format(Locale.ROOT, "%.1f dB", estimate.worstCaseReductionDb()) : ""), "stat-value"));
            headroom.getChildren().add(label(estimate.message(), "muted"));
        } else headroom.getChildren().add(label(estimate.message(), "muted"));
        headroom.getChildren().add(label(t("Это модель для полноуровневого входа по текущим настройкам; она не измеряет входной сигнал, напряжение или мощность. Реальный выходной peak/RMS показан отдельным monitor-метром.",
                        "This models a full-scale input using current settings; it does not measure the input signal, voltage, or power. The live output peak/RMS is shown separately by the monitor meter."), "muted"));
        page.getChildren().add(headroom);
        VBox comparison = card(t("Сравнение A/B", "A/B comparison"));
        CheckBox reference = new CheckBox(t("B · EQ bypass с выравниванием по верхней оценке gain", "B · EQ bypass with conservative gain matching"));
        dspReferenceToggle = reference;
        reference.setSelected(dspReferenceMode);
        reference.setDisable(!routeManager.active() || dspReferenceUpdateInProgress);
        reference.setOnAction(event -> setDspReferenceMode(reference.isSelected()));
        double matchBoost;
        try { matchBoost = routeManager.maximumEqBoostDb(config, snapshot, selectedSink); }
        catch (Exception ignored) { matchBoost = 0; }
        comparison.getChildren().addAll(reference,
                label(t("A — обработанный EQ; B — без EQ. В режиме B шины ослабляются примерно на ", "A — processed EQ; B — EQ bypass. In mode B, buses are attenuated by about ")
                        + String.format(Locale.ROOT, "%.1f dB", matchBoost)
                        + t(" по консервативной сумме положительных EQ gains. Limiter и полярность маршрута остаются включены. Переключение временное и не меняет JSON.",
                                " from a conservative sum of positive EQ gains. The limiter and route polarity stay enabled. This toggle is temporary and does not edit JSON."), "muted"));
        page.getChildren().add(comparison);
        Map<String, Object> limiter = map(dsp.get("limiter"));
        Map<String, Object> mixer = map(config.get("mixer"));
        VBox effectsMasterCard = card(t("Общая громкость Audio Matrix", "Audio Matrix master volume"));
        Slider masterVolume = new Slider(0, 1, number(mixer.get("global_volume"), 0.8));
        masterVolume.setShowTickMarks(true); masterVolume.setMajorTickUnit(0.25); masterVolume.setBlockIncrement(0.01);
        masterVolume.setAccessibleText(t("Общая громкость графа Audio Matrix", "Audio Matrix graph master volume"));
        Label masterValue = label(String.format(Locale.ROOT, "%.0f%%", masterVolume.getValue() * 100), "stat-value");
        masterVolume.valueProperty().addListener((observable, oldValue, value) -> masterValue.setText(String.format(Locale.ROOT, "%.0f%%", value.doubleValue() * 100)));
        Runnable saveMaster = () -> setGlobalMasterVolume(Math.round(masterVolume.getValue() * 100.0) / 100.0);
        masterVolume.setOnMouseReleased(event -> saveMaster.run()); masterVolume.setOnKeyReleased(event -> saveMaster.run());
        CheckBox masterMute = new CheckBox(t("Заглушить весь граф", "Mute the whole graph"));
        masterMute.setSelected(Boolean.TRUE.equals(mixer.get("global_mute")));
        masterMute.setOnAction(event -> setGlobalMasterMute(masterMute.isSelected()));
        effectsMasterCard.getChildren().addAll(new HBox(10, label(t("Уровень", "Level"), "muted"), masterVolume, masterValue), masterMute,
                label(t("При использовании виртуального входа системный ползунок GNOME синхронизируется с этим уровнем.",
                        "When the virtual system input is active, GNOME's master slider stays synchronized with this level."), "muted"));
        HBox.setHgrow(masterVolume, Priority.ALWAYS);
        page.getChildren().add(effectsMasterCard);
        VBox limiterCard = card(t("Цифровой пиковый limiter", "Digital peak limiter"));
        CheckBox limiterToggle = new CheckBox(t("Включён на физических выходах", "Enabled on physical outputs"));
        limiterToggle.setSelected(Boolean.TRUE.equals(limiter.get("enabled")));
        limiterToggle.setOnAction(event -> updateLimiter("enabled", limiterToggle.isSelected()));
        limiterCard.getChildren().addAll(limiterToggle,
                limiterControl(t("Потолок", "Ceiling"), "ceiling_db", -12, 0, number(limiter.get("ceiling_db"), -1), "dBFS"),
                limiterControl(t("Упреждение", "Lookahead"), "lookahead_ms", 1, 10, number(limiter.get("lookahead_ms"), 5), "ms"),
                limiterControl(t("Восстановление", "Release"), "release_ms", 5, 500, number(limiter.get("release_ms"), 50), "ms"),
                label(t("Обработка применяется один раз после сведения ветвей в каждый физический выход. Это цифровой предел сигнала; допустимую мощность усилителя и вибродинамика нужно настраивать отдельно.", "Processing runs once after route branches are mixed into each physical output. This is a digital signal ceiling; configure amplifier and transducer power limits separately."), "muted"));
        page.getChildren().add(limiterCard);
        Map<String, Object> eq = map(dsp.getOrDefault("global_eq", DefaultConfigMap.map("enabled", false, "filters", List.of())));
        VBox filters = card(t("Общий эквалайзер · конфигурация", "Global EQ · stored configuration"));
        List<Map<String, Object>> filterList = maps(eq.get("filters"));
        if (filterList.isEmpty()) filters.getChildren().add(label(t("Фильтров пока нет.", "No filters yet."), "muted"));
        for (int i = 0; i < filterList.size(); i++) {
            Map<String, Object> filter = filterList.get(i); int index = i;
            HBox row = new HBox(10); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("node-card");
            String info = string(filter.get("type"), "filter") + " · " + number(filter.get("frequency_hz"), 0) + " Hz · " + number(filter.get("gain_db"), 0) + " dB · Q " + number(filter.get("q"), 0.707);
            Label label = new Label(info); HBox.setHgrow(label, Priority.ALWAYS);
            Button toggle = actionButton(Boolean.TRUE.equals(filter.get("enabled")) ? t("Выключить", "Disable") : t("Включить", "Enable"), () -> updateFilter(index, "enabled", !Boolean.TRUE.equals(filter.get("enabled"))));
            Button delete = actionButton(t("Удалить", "Delete"), () -> removeFilter(index));
            row.getChildren().addAll(label, toggle, delete); filters.getChildren().add(row);
        }
        Button add = actionButton(t("Добавить notch", "Add notch"), () -> addDefaultFilter());
        Button master = actionButton(Boolean.TRUE.equals(eq.get("enabled")) ? t("EQ включён в конфигурации", "EQ enabled in config") : t("Включить EQ в конфигурации", "Enable EQ in config"), () -> setEqEnabled(!Boolean.TRUE.equals(eq.get("enabled"))));
        filters.getChildren().add(new HBox(10, add, master));
        page.getChildren().add(filters);
        VBox chains = card(t("EQ-цепочки приложений, шин и маршрутов", "EQ chains for apps, buses, and routes"));
        Map<String, Object> routeChains = map(dsp.get("route_chains"));
        if (routeChains.isEmpty()) chains.getChildren().add(label(t("Цепочек пока нет.", "No route chains yet."), "muted"));
        for (Map.Entry<String, Object> entry : routeChains.entrySet()) {
            String chainId = entry.getKey(); Map<String, Object> chain = map(entry.getValue());
            List<Map<String, Object>> chainFilters = maps(chain.get("filters"));
            VBox chainCard = new VBox(8); chainCard.getStyleClass().add("node-card");
            HBox chainHeader = new HBox(10); chainHeader.setAlignment(Pos.CENTER_LEFT);
            CheckBox chainEnabled = new CheckBox(chainId); chainEnabled.setSelected(Boolean.TRUE.equals(chain.get("enabled")));
            chainEnabled.setOnAction(event -> updateRouteChain(chainId, candidate -> candidate.put("enabled", chainEnabled.isSelected())));
            Region chainSpacer = new Region(); HBox.setHgrow(chainSpacer, Priority.ALWAYS);
            chainHeader.getChildren().addAll(chainEnabled, chainSpacer,
                    actionButton(t("Добавить фильтр", "Add filter"), () -> editRouteFilter(chainId, -1, null)),
                    actionButton(t("Удалить цепочку", "Delete chain"), () -> deleteRouteChain(chainId)));
            chainCard.getChildren().add(chainHeader);
            if (chainFilters.isEmpty()) chainCard.getChildren().add(label(t("Фильтров пока нет.", "No filters yet."), "muted"));
            for (int i = 0; i < chainFilters.size(); i++) {
                Map<String, Object> filter = chainFilters.get(i); int filterIndex = i;
                String filterType = string(filter.get("type"), "filter");
                String detail = filterType + " · " + number(filter.get("frequency_hz"), 0) + " Hz";
                if (isPassFilter(filterType)) detail += " · " + (int) number(filter.get("slope_db_oct"), 12) + " dB/oct";
                else detail += " · " + number(filter.get("gain_db"), 0) + " dB · Q " + number(filter.get("q"), 1);
                HBox filterRow = new HBox(8); filterRow.setAlignment(Pos.CENTER_LEFT);
                Label filterLabel = label(detail, "muted"); HBox.setHgrow(filterLabel, Priority.ALWAYS);
                filterRow.getChildren().addAll(filterLabel,
                        actionButton(Boolean.FALSE.equals(filter.get("enabled")) ? t("Включить", "Enable") : t("Выключить", "Disable"),
                                () -> updateRouteChain(chainId, candidate -> maps(candidate.get("filters")).get(filterIndex).put("enabled", Boolean.FALSE.equals(filter.get("enabled"))))),
                        actionButton(t("Изменить", "Edit"), () -> editRouteFilter(chainId, filterIndex, filter)),
                        actionButton(t("Удалить", "Delete"), () -> updateRouteChain(chainId, candidate -> maps(candidate.get("filters")).remove(filterIndex))));
                chainCard.getChildren().add(filterRow);
            }
            chains.getChildren().add(chainCard);
        }
        chains.getChildren().addAll(label(Boolean.TRUE.equals(limiter.get("enabled"))
                        ? t("Положительный EQ gain разрешён при включённом цифровом limiter. High/low-pass поддерживают уклоны 6/12/18/24/36/48 dB/oct.", "Positive EQ gain is allowed while the digital limiter is enabled. High/low-pass slopes support 6/12/18/24/36/48 dB/oct.")
                        : t("Положительный EQ gain требует включённого цифрового limiter. High/low-pass поддерживают уклоны 6/12/18/24/36/48 dB/oct.", "Positive EQ gain requires the digital limiter. High/low-pass slopes support 6/12/18/24/36/48 dB/oct."), "muted"),
                actionButton(t("Создать route chain", "Create route chain"), this::createRouteChain));
        page.getChildren().add(chains);
        VBox future = card(t("Физический выход и каналы", "Physical output and channels"));
        future.getChildren().addAll(label(t("Профиль может хранить независимый EQ и отдельные notch дребезга. Кривые строятся только по импортированной или записанной WAV-измерительной записи; субъективные заметки остаются непроверенными.", "A profile can store an independent EQ and separate rattle notches. Curves are derived only from imported or recorded WAV measurements; subjective notes remain unverified."), ""), label(t("A/B временно переключает активный граф PipeWire: A — сохранённый EQ, B — bypass EQ с консервативным выравниванием gain. Limiter и полярность остаются включены; JSON не меняется. Сначала примените граф маршрутов.", "A/B temporarily switches the active PipeWire graph: A uses the saved EQ, while B bypasses EQ with conservative gain matching. The limiter and route polarity stay enabled; JSON is unchanged. Apply the route graph first."), "muted"));
        page.getChildren().add(future);
        return page;
    }
}
