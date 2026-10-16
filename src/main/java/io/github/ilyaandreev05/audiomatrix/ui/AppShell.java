package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.ApplicationRuleMatcher;
import io.github.ilyaandreev05.audiomatrix.audio.ApplicationStreamRuleApplier;
import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.CalibrationFilterAdvisor;
import io.github.ilyaandreev05.audiomatrix.audio.CalibrationImpulseAnalyzer;
import io.github.ilyaandreev05.audiomatrix.audio.CalibrationMeasurementAnalyzer;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceSelectorMatcher;
import io.github.ilyaandreev05.audiomatrix.audio.DspComparisonConfig;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteManager;
import io.github.ilyaandreev05.audiomatrix.audio.ProfileRoutePolicy;
import io.github.ilyaandreev05.audiomatrix.config.ConfigArchive;
import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.ConfigValidator;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeControlBridge;
import javafx.animation.PauseTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.collections.FXCollections;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.stage.FileChooser;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;
import javafx.scene.shape.Circle;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class AppShell extends BorderPane {
    private enum Page { HOME, APPLICATIONS, DEVICES, ROUTES, DSP, CALIBRATION, PROFILES, SETTINGS }
    private record Endpoint(String id, String label, boolean bus, AudioNode node) {}
    private record MeasurementPreview(Path source, AudioNode output, String method, String sensorSource,
                                     String sensorSourceDescription, int channel, double startHz, double endHz,
                                     int steps, int dwellMs, int offsetMs, String sensorMount,
                                     CalibrationMeasurementAnalyzer.Result result, String measurementFile,
                                     String responseCurveFile, String calibrationId, Path temporaryDirectory) {}
    private record ImpulseDecayPreview(Path source, AudioNode output, String method, int channel, String sensorMount,
                                       int impulseOffsetMs, int responseWindowMs, CalibrationImpulseAnalyzer.Result result,
                                       String measurementFile, String decayCurveFile, String calibrationId) {}
    private record CalibrationComparisonPreview(String beforeId, String afterId, String beforeLabel, String afterLabel,
                                                CalibrationMeasurementAnalyzer.Comparison comparison,
                                                boolean clipped, boolean verified) {}
    private record SavedCalibrationFiles(Path recording, Path curve, CalibrationMeasurementAnalyzer.Result result) {}
    private record CalibrationFilterRollback(String deviceId, String calibrationId, String previousRef,
                                             Map<String, Object> previousChain, String appliedRef,
                                             Map<String, Object> appliedChain, String outputDescription) {}
    private record RouteHistoryEntry(String description, Map<String, Object> previousSnapshot) {}
    private final ConfigStore store;
    private final PipeWireBackend backend;
    private final PipeWireRouteManager routeManager = new PipeWireRouteManager();
    private final ArrayDeque<RouteHistoryEntry> routeHistory = new ArrayDeque<>();
    private final ThemeManager themes;
    private final GnomeControlBridge gnomeBridge;
    private final ExecutorService archiveIo = Executors.newSingleThreadExecutor(r -> { Thread thread = new Thread(r, "audio-matrix-config-archive"); thread.setDaemon(true); return thread; });
    private final ExecutorService calibrationIo = Executors.newVirtualThreadPerTaskExecutor();
    private final VBox sidebar = new VBox(8);
    private final BorderPane body = new BorderPane();
    private final Label title = new Label();
    private final Label connection = new Label();
    private final Label notice = new Label();
    private Label outputMeterValue;
    private Label inputMeterValue;
    private AudioNode selectedInputSource;
    private boolean inputMeterEnabled;
    private final Timeline meterRefresh = new Timeline(new KeyFrame(Duration.millis(250), event -> refreshMeterLabel()));
    private final TextField search = new TextField();
    private final Map<Page, ToggleButton> navButtons = new LinkedHashMap<>();
    private final ApplicationStreamRuleApplier streamRuleApplier;
    private final ConcurrentMap<Integer, String> appliedDeviceRules = new ConcurrentHashMap<>();
    private volatile PipeWireBackend.Snapshot snapshot = PipeWireBackend.Snapshot.offline("Waiting for PipeWire");
    private Page currentPage = Page.HOME;
    private AudioNode selectedSink;
    private MeasurementPreview calibrationPreview;
    private boolean calibrationPreviewSaved;
    private ImpulseDecayPreview calibrationImpulsePreview;
    private boolean calibrationImpulsePreviewSaved;
    private Path calibrationRecordingPath;
    private CalibrationComparisonPreview calibrationComparisonPreview;
    private List<CalibrationFilterAdvisor.Suggestion> calibrationFilterSuggestions = List.of();
    private boolean calibrationFilterPreviewShown;
    private CalibrationFilterRollback calibrationFilterRollback;
    private volatile CompletableFuture<MeasurementPreview> calibrationCaptureTask;
    private CompletableFuture<Void> closeFuture;
    private volatile boolean closing;
    private boolean suppressConfigListener;
    private boolean routesRequested;
    private long routeHistoryRevision = -1;
    private boolean routeUndoInProgress;
    private boolean dspReferenceMode;
    private boolean dspReferenceUpdateInProgress;
    private CheckBox dspReferenceToggle;
    private boolean autoApplySuppressedUntilRestart;
    private String lastRouteInputSignature = "";
    private long lastAppliedConnectionGeneration;

    public AppShell(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        this.store = store; this.backend = backend; this.themes = themes;
        this.streamRuleApplier = new ApplicationStreamRuleApplier(backend);
        this.gnomeBridge = new GnomeControlBridge(backend, this::handleGnomeCommand);
        getStyleClass().add("app-shell");
        buildChrome();
        showPage(Page.HOME);
        search.textProperty().addListener((obs, old, value) -> showPage(currentPage));
        store.addListener(event -> {
            if (suppressConfigListener) return;
            Platform.runLater(() -> {
                if (event.message().startsWith("Configuration rejected:")) setNotice(event.message(), true);
                else {
                    setNotice(event.message(), false);
                    applyConfiguredDeviceRules(snapshot, event.config());
                    applyConfiguredStreamRules(snapshot, event.config());
                    maybeApplyConfiguredRoutes(event.config(), snapshot);
                    showPage(currentPage);
                }
                publishGnomeState();
            });
        });
    }

    public void start() {
        meterRefresh.setCycleCount(Timeline.INDEFINITE);
        meterRefresh.play();
        gnomeBridge.start();
        publishGnomeState();
        backend.start(next -> Platform.runLater(() -> {
            snapshot = next;
            applyConfiguredDeviceRules(next,store.snapshot());
            applyConfiguredStreamRules(next, store.snapshot());
            maybeApplyConfiguredRoutes(store.snapshot(), next);
            if (selectedSink != null) selectedSink = next.sinks().stream().filter(n -> n.name().equals(selectedSink.name())).findFirst().orElse(null);
            if (selectedSink == null) selectedSink = configuredSink();
            if (selectedSink == null) selectedSink = next.sinks().stream().filter(n -> n.id() == next.defaultSinkId()).findFirst().orElse(null);
            updateMeterTarget();
            connection.setText(next.connected() ? t("PipeWire подключён", "PipeWire connected") : t("PipeWire недоступен", "PipeWire unavailable"));
            connection.getStyleClass().removeAll("accent", "danger", "warning");
            connection.getStyleClass().add(next.connected() ? "accent" : "danger");
            showPage(currentPage);
            publishGnomeState();
        }));
    }

    private void buildChrome() {
        sidebar.getStyleClass().add("sidebar");
        HBox brand = new HBox(9, new Label("AM"), new Label("Audio Matrix"));
        brand.getChildren().getFirst().getStyleClass().add("brand-mark");
        brand.getChildren().getLast().getStyleClass().add("brand");
        brand.setAlignment(Pos.CENTER_LEFT);
        sidebar.getChildren().add(brand);
        Separator separator = new Separator(); sidebar.getChildren().add(separator);
        ToggleGroup group = new ToggleGroup();
        for (Page page : Page.values()) {
            ToggleButton button = new ToggleButton(pageLabel(page));
            button.getStyleClass().add("nav-button");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setAlignment(Pos.CENTER_LEFT);
            button.setAccessibleText(pageLabel(page));
            button.setToggleGroup(group);
            button.setOnAction(event -> showPage(page));
            navButtons.put(page, button);
            sidebar.getChildren().add(button);
        }
        Region spacer = new Region(); VBox.setVgrow(spacer, Priority.ALWAYS); sidebar.getChildren().add(spacer);
        connection.getStyleClass().add("status-pill");
        sidebar.getChildren().addAll(new Separator(), connection, label(t("Настройки сохраняются в YAML", "Settings are stored in YAML"), "muted"));
        sidebar.setPrefWidth(228);

        HBox topbar = new HBox(14);
        title.getStyleClass().add("page-title");
        search.setPromptText(t("Поиск в разделе…", "Search this section…"));
        search.setAccessibleText(t("Поиск в текущем разделе", "Search the current section"));
        search.setPrefWidth(250);
        Region push = new Region(); HBox.setHgrow(push, Priority.ALWAYS);
        notice.setVisible(false); notice.setManaged(false);
        topbar.getChildren().addAll(title, push, search);
        topbar.getStyleClass().add("topbar");
        body.setTop(topbar);
        setLeft(sidebar); setCenter(body);
        setPadding(Insets.EMPTY);
    }

    private void showPage(Page page) {
        currentPage = page;
        if (page == Page.DSP) backend.monitorOutput(selectedSink);
        else {
            backend.monitorOutput(null);
            if (inputMeterEnabled) inputMeterEnabled = false;
            backend.monitorInput(null);
        }
        ToggleButton button = navButtons.get(page);
        if (button != null) button.setSelected(true);
        title.setText(pageLabel(page));
        Node content = switch (page) {
            case HOME -> homePage();
            case APPLICATIONS -> applicationsPage();
            case DEVICES -> devicesPage();
            case ROUTES -> routesPage();
            case DSP -> dspPage();
            case CALIBRATION -> calibrationPage();
            case PROFILES -> profilesPage();
            case SETTINGS -> settingsPage();
        };
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("page-scroll");
        body.setCenter(scroll);
        if (notice.isVisible()) body.setBottom(notice);
    }

    private VBox homePage() {
        VBox page = page(t("Обзор текущего звукового графа и доступных выходов.", "Current audio graph and available outputs."));
        HBox cards = new HBox(14);
        cards.getChildren().addAll(metricCard(t("Выходы", "Outputs"), Integer.toString(snapshot.sinks().size()), t("реальные PipeWire sinks", "real PipeWire sinks")),
                metricCard(t("Источники", "Sources"), Integer.toString(snapshot.sources().size()), t("входы, доступные системе", "inputs visible to the system")),
                metricCard(t("Потоки", "Streams"), Integer.toString(snapshot.streams().size()), t("сейчас воспроизводят звук", "currently playing")));
        cards.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));
        page.getChildren().add(cards);

        VBox outputCard = card(t("Управление физическим выходом", "Physical output control"));
        outputCard.getChildren().add(label(t("Этот ползунок управляет уровнем выбранного PipeWire sink. Он не измеряет акустические децибелы.", "This slider controls the selected PipeWire sink. It does not measure acoustic decibels."), "muted"));
        if (!snapshot.connected()) {
            outputCard.getChildren().add(label(snapshot.error().isBlank() ? t("PipeWire ещё не подключён.", "PipeWire has not connected yet.") : snapshot.error(), "danger"));
        } else if (snapshot.sinks().isEmpty()) {
            outputCard.getChildren().add(label(t("Система не сообщает ни одного звукового выхода.", "The system reports no audio outputs."), "warning"));
        } else {
            ComboBox<AudioNode> outputs = new ComboBox<>(); outputs.getItems().setAll(snapshot.sinks());
            outputs.setMaxWidth(Double.MAX_VALUE); outputs.setAccessibleText(t("Выходное устройство", "Output device"));
            outputs.setCellFactory(list -> nodeCell()); outputs.setButtonCell(nodeCell());
            AudioNode configured = configuredSink();
            AudioNode actual = snapshot.sinks().stream().filter(n -> n.id() == snapshot.defaultSinkId()).findFirst().orElse(null);
            selectedSink = configured != null ? configured : actual;
            if (selectedSink != null) outputs.getSelectionModel().select(selectedSink);
            outputs.setOnAction(event -> {
                AudioNode selected = outputs.getValue(); if (selected == null) return;
                selectedSink = selected;
                backend.setDefaultOutput(selected.id()).whenComplete((ignored, error) -> Platform.runLater(() -> {
                    if (error != null) setNotice(t("Не удалось выбрать выход: ", "Could not select output: ") + rootMessage(error), true);
                    else { saveDefaultOutput(selected); setNotice(t("Выбран выход: ", "Selected output: ") + selected.description(), false); refreshBackend(); }
                }));
            });
            Label outputLabel = label(t("Выходное устройство", "Output device"), ""); outputLabel.setLabelFor(outputs);
            Slider level = new Slider(0, 1, selectedSink != null && Double.isFinite(selectedSink.volume()) ? Math.min(1, selectedSink.volume()) : 0);
            level.setAccessibleText(t("Громкость выходного устройства", "Output device volume")); level.setShowTickMarks(false);
            Label percent = label(volumeLabel(selectedSink == null ? Double.NaN : selectedSink.volume()), "stat-value");
            PauseTransition debounce = new PauseTransition(Duration.millis(160));
            level.valueProperty().addListener((obs, old, value) -> {
                percent.setText(volumeLabel(value.doubleValue()));
                if (selectedSink == null) return;
                AudioNode target = selectedSink;
                debounce.setOnFinished(e -> backend.setVolume(target.id(), level.getValue()).whenComplete((v, error) -> {
                    if (error != null) Platform.runLater(() -> setNotice(t("Не удалось изменить уровень: ", "Could not change the level: ") + rootMessage(error), true));
                }));
                debounce.playFromStart();
            });
            level.setOnMouseReleased(event -> { if (selectedSink != null) saveDeviceVolume(selectedSink, level.getValue()); });
            level.setOnKeyReleased(event -> { if (selectedSink != null) saveDeviceVolume(selectedSink, level.getValue()); });
            Button mute = actionButton(selectedSink != null && selectedSink.muted() ? t("Включить звук", "Unmute") : t("Выключить звук", "Mute"), () -> {
                AudioNode target = selectedSink; if (target == null) return;
                backend.setMute(target.id(), !target.muted()).whenComplete((v, error) -> Platform.runLater(() -> {
                    if (error != null) setNotice(rootMessage(error), true); else { saveDeviceMute(target, !target.muted()); refreshBackend(); }
                }));
            });
            Button refresh = actionButton(t("Обновить состояние", "Refresh state"), this::refreshBackend);
            HBox levelRow = new HBox(12, percent, level); levelRow.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(level, Priority.ALWAYS);
            outputCard.getChildren().addAll(outputLabel, outputs, levelRow, new HBox(10, mute, refresh));
        }
        page.getChildren().add(outputCard);

        VBox state = card(t("Что применяется сейчас", "What is applied now"));
        boolean processingActive = routeManager.active() && routeManager.status().contains("PipeWire filters");
        state.getChildren().addAll(
                label(t("Громкость sink, mute и выбор default выходят через wpctl и проверяются по PipeWire.", "Sink volume, mute, and default output are changed through wpctl and read back from PipeWire."), ""),
                label(processingActive
                        ? t("PipeWire-маршруты и фильтрующие цепочки активны; limiter стоит после сведения выходных ветвей.", "PipeWire routes and processing chains are active; the limiter follows the output mix.")
                        : routeManager.active()
                        ? t("PipeWire-маршруты активны; фильтры выхода выключены.", "PipeWire routes are active; output processing is disabled.")
                        : t("Шины и маршруты применяются на экране маршрутов; выходная filter-chain создаётся при включённом лимитере или коррекции.", "Apply buses and routes on the Routes page; output filter-chains are created when limiting or correction is enabled."),
                        routeManager.active() ? "accent" : "warning"),
                label(t("Фактические аудиометры требуют meter-node; в этой сборке входной и выходной уровень не показывается как будто он измерен.", "Actual audio meters need a meter node; this build does not present input/output levels as measured."), "muted"));
        page.getChildren().add(state);
        return page;
    }

    private VBox applicationsPage() {
        VBox page = page(t("Активные потоки отдельно от недавно завершившихся.", "Active streams are shown separately from recently ended streams."));
        if (!snapshot.connected()) page.getChildren().add(errorCard(t("PipeWire недоступен", "PipeWire unavailable"), snapshot.error()));
        List<AudioNode> active = filtered(snapshot.streams(), search.getText());
        VBox activeCard = card(t("Активные потоки", "Active streams"));
        if (active.isEmpty()) activeCard.getChildren().add(label(t("Активных аудиопотоков сейчас нет.", "There are no active audio streams right now."), "muted"));
        for (AudioNode stream : active) activeCard.getChildren().add(streamRow(stream, false));
        page.getChildren().add(activeCard);
        VBox endedCard = card(t("Недавно завершённые", "Recently ended"));
        List<AudioNode> ended = filtered(snapshot.recentlyEnded(), search.getText());
        if (ended.isEmpty()) endedCard.getChildren().add(label(t("За последние 30 секунд завершившихся потоков нет.", "No streams ended in the last 30 seconds."), "muted"));
        for (AudioNode stream : ended) endedCard.getChildren().add(streamRow(stream, true));
        page.getChildren().add(endedCard);
        return page;
    }

    private Node streamRow(AudioNode stream, boolean ended) {
        VBox card = new VBox(9); card.getStyleClass().add("node-card");
        HBox heading = new HBox(10); heading.setAlignment(Pos.CENTER_LEFT);
        VBox names = new VBox(3, label(stream.applicationName().isBlank() ? stream.description() : stream.applicationName(), "card-title"),
                label(stream.processName().isBlank() ? stream.name() : stream.processName() + " · " + stream.name(), "muted"));
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        heading.getChildren().addAll(names, spacer, label(ended ? t("Завершён", "Ended") : stream.state(), ended ? "muted" : "status-pill"));
        card.getChildren().add(heading);
        if (!ended) {
            HBox controls = new HBox(12); controls.setAlignment(Pos.CENTER_LEFT);
            Label current = label(volumeLabel(stream.volume()), "");
            Slider slider = new Slider(0, 1, Double.isFinite(stream.volume()) ? Math.min(stream.volume(), 1) : 0);
            slider.setAccessibleText(t("Громкость приложения", "Application volume"));
            slider.setDisable(!Double.isFinite(stream.volume()));
            if (!Double.isFinite(stream.volume())) slider.setTooltip(new javafx.scene.control.Tooltip(t("PipeWire не сообщил уровень этого потока.", "PipeWire did not report this stream's volume.")));
            HBox.setHgrow(slider, Priority.ALWAYS);
            PauseTransition debounce = new PauseTransition(Duration.millis(180));
            slider.valueProperty().addListener((obs, old, value) -> {
                current.setText(volumeLabel(value.doubleValue()));
                debounce.setOnFinished(e -> backend.setVolume(stream.id(), value.doubleValue()).whenComplete((v, err) -> {
                    if (err != null) Platform.runLater(() -> setNotice(rootMessage(err), true));
                })); debounce.playFromStart();
            });
            slider.setOnMouseReleased(e -> saveAppVolume(stream, slider.getValue()));
            slider.setOnKeyReleased(e -> saveAppVolume(stream, slider.getValue()));
            Button mute = actionButton(stream.muted() ? t("Включить звук", "Unmute") : t("Выключить звук", "Mute"), () -> backend.setMute(stream.id(), !stream.muted()).whenComplete((v, err) -> Platform.runLater(() -> {
                if (err != null) setNotice(rootMessage(err), true); else { saveAppMute(stream, !stream.muted()); refreshBackend(); }
            })));
            ComboBox<AudioNode> destination = new ComboBox<>(); destination.getItems().setAll(snapshot.sinks());
            destination.setCellFactory(list -> nodeCell()); destination.setButtonCell(nodeCell()); destination.setPromptText(t("Перенос…", "Move to…")); destination.setPrefWidth(190); destination.setAccessibleText(t("Выход для переноса потока", "Destination for stream transfer"));
            Button move = actionButton(t("Перенести", "Move"), () -> {
                AudioNode sink = destination.getValue(); if (sink == null) return;
                backend.moveStream(stream, sink).whenComplete((v, err) -> Platform.runLater(() -> {
                    if (err != null) setNotice(t("Перенос не выполнен: ", "Stream transfer failed: ") + rootMessage(err), true);
                    else refreshBackend();
                }));
            });
            move.setDisable(!backend.canMoveStreams() || routeManager.active());
            move.setTooltip(new javafx.scene.control.Tooltip(routeManager.active()
                    ? t("Сначала очистите применённые маршруты.", "Clear the applied route graph first.")
                    : backend.canMoveStreams()
                    ? t("Переносит поток на выбранный выход через pactl или прямые PipeWire-связи.", "Moves the stream using pactl or direct PipeWire links.")
                    : t("Нужны pactl или pw-link; ни одна команда не найдена.", "Either pactl or pw-link is required; neither command was found.")));
            controls.getChildren().addAll(label(t("Громкость приложения", "Application volume"), "muted"), slider, current, mute, destination, move);
            card.getChildren().add(controls);

            Map<String, Object> appConfig = store.snapshot();
            Map<String, Object> appRule = ApplicationRuleMatcher.find(maps(appConfig.get("applications")), stream);
            ComboBox<String> eqChain = new ComboBox<>();
            eqChain.getItems().add("none");
            map(map(appConfig.get("dsp")).get("route_chains")).keySet().stream().sorted()
                    .map(id -> "dsp.route_chains." + id).forEach(eqChain.getItems()::add);
            String savedChain = string(appRule == null ? null : appRule.get("filters_ref"), "none");
            if (!eqChain.getItems().contains(savedChain)) eqChain.getItems().add(savedChain);
            eqChain.setValue(savedChain);
            eqChain.setAccessibleText(t("EQ-цепочка приложения", "Application EQ chain"));
            eqChain.setPrefWidth(250);
            eqChain.setOnAction(event -> {
                String selected = eqChain.getValue();
                if (selected == null) return;
                updateConfig(config -> {
                    Map<String, Object> rule = ensureApplication(config, stream);
                    if ("none".equals(selected)) rule.remove("filters_ref");
                    else rule.put("filters_ref", selected);
                });
                setNotice(t("EQ-цепочка сохранена для правила приложения.", "Application EQ chain saved to this app rule."), false);
            });
            card.getChildren().add(new HBox(10, label(t("EQ приложения", "Application EQ"), "muted"), eqChain,
                    label(t("Цепочки создаются на экране DSP.", "Create chains on the DSP page."), "muted")));
        }
        return card;
    }

    private VBox devicesPage() {
        VBox page = page(t("Sink, source и их свойства сообщаются PipeWire.", "Sinks, sources, and properties reported by PipeWire."));
        Map<String,Object> config=store.snapshot();
        VBox outputs = card(t("Выходы · sinks", "Outputs · sinks"));
        if (snapshot.sinks().isEmpty()) outputs.getChildren().add(label(t("Система не сообщила выходы.", "No outputs were reported by the system."), "muted"));
        for (AudioNode node : filtered(snapshot.sinks(), search.getText())) {
            String status = node.id() == snapshot.defaultSinkId() ? t("Системный выход по умолчанию", "System default output") : node.state();
            outputs.getChildren().add(deviceRow(node, status, true));
        }
        VBox inputs = card(t("Входы · sources", "Inputs · sources"));
        if(snapshot.sources().isEmpty())inputs.getChildren().add(label(t("Система не сообщила входы.","No inputs were reported by the system."),"muted"));
        for (AudioNode node : filtered(snapshot.sources(), search.getText())) inputs.getChildren().add(deviceRow(node, node.state(), false));
        VBox identities=card(t("Сохранённые selectors", "Saved device selectors"));
        List<Map<String,Object>> savedDevices=maps(config.get("devices")).stream().filter(device->matchesSearch(String.valueOf(device.getOrDefault("name",device.get("id")))+" "+device.get("id")+" "+map(device.get("selector")),search.getText())).toList();
        if(savedDevices.isEmpty())identities.getChildren().add(label(t("Сохранённых устройств пока нет. Выберите или настройте выход, чтобы создать selector.","No saved devices yet. Select or configure an output to create a selector."),"muted"));
        for (Map<String, Object> device : savedDevices) {
            String id = string(device.get("id"), "");
            Map<String, Object> selector = map(device.get("selector"));
            AudioNode online = resolveDevice(config, device);
            int matches = matchingNodes(device).size();
            String state = matches > 1 ? t("Неоднозначный selector", "Ambiguous selector")
                    : online == null ? t("Не подключён", "Offline") : t("Подключён", "Online");
            String outputEq = string(device.get("filters_ref"), "");
            String detail = id + " · " + selector.getOrDefault("kind", "") + " = " + selector.getOrDefault("value", "") + " · " + state;
            if (!outputEq.isBlank()) detail += " · " + t("EQ: ", "EQ: ") + outputEq.replace("dsp.route_chains.", "");
            HBox row = new HBox(12); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("node-card");
            VBox details = new VBox(4, label(string(device.getOrDefault("name", id), id), "card-title"), label(detail, online == null || matches > 1 ? "warning" : "muted"));
            Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
            Button edit = actionButton(t("Изменить selector", "Edit selector"), () -> editDeviceSelector(device, online));
            row.getChildren().addAll(details, spacer);
            if (Set.of("sink", "output").contains(string(device.get("role"), "")))
                row.getChildren().add(actionButton(t("EQ выхода", "Output EQ"), () -> editOutputEq(device, online)));
            row.getChildren().add(edit);
            identities.getChildren().add(row);
        }
        page.getChildren().addAll(outputs,inputs,identities);
        return page;
    }

    private Node deviceRow(AudioNode node, String status, boolean output) {
        HBox row = new HBox(12); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("node-card");
        Map<String,Object> config=store.snapshot();Map<String,Object> device=matchingDevice(config,node);String alias=device==null?"":string(device.get("name"),"");
        String heading=alias.isBlank()?node.description():alias;String detail=alias.isBlank()?node.name():node.description()+" · "+node.name();
        String outputEq = output && device != null ? string(device.get("filters_ref"), "") : "";
        if (!outputEq.isBlank()) detail += " · " + t("EQ: ", "EQ: ") + outputEq.replace("dsp.route_chains.", "");
        VBox details = new VBox(4, label(heading, "card-title"), label(detail, "muted"));
        String channels = node.channels() + " ch";
        String rates = node.sampleRates().isEmpty() ? t("частота неизвестна", "sample rate unavailable") : node.sampleRates().stream().map(String::valueOf).distinct().reduce((a,b) -> a + " / " + b).orElse("") + " Hz";
        VBox facts = new VBox(4, label(status, "status-pill"), label(channels + " · " + rates, "muted"));
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().addAll(details, spacer, facts);
        if (output) {
            Button choose = actionButton(t("Сделать по умолчанию", "Set default"), () -> backend.setDefaultOutput(node.id()).whenComplete((v, err) -> Platform.runLater(() -> {
                if (err != null) setNotice(rootMessage(err), true); else { saveDefaultOutput(node); refreshBackend(); }
            })));
            row.getChildren().add(choose);
            row.getChildren().add(actionButton(t("EQ выхода", "Output EQ"), () -> editOutputEq(device, node)));
        } else row.getChildren().add(label(t("Уровень входа не измеряется", "Input level not measured"), "muted"));
        row.getChildren().add(actionButton(t("Имя и selector","Name and selector"),()->editDeviceSelector(device,node)));
        return row;
    }


    private VBox routesPage() {
        Map<String, Object> config = store.snapshot();
        Map<String, Object> profile = activeProfile(config);
        List<Map<String, Object>> buses = maps(config.get("buses"));
        List<Map<String, Object>> routes = maps(config.get("routes"));
        List<Map<String, Object>> applications = maps(config.get("applications"));
        Set<String> activeBusIds = new java.util.HashSet<>(stringList(profile.get("active_buses")));
        Set<String> activeRouteIds = new java.util.HashSet<>(stringList(profile.get("active_routes")));
        VBox page = page(t("Примените сохранённую схему, чтобы направить потоки через шины и маршруты PipeWire.", "Apply the saved graph to route streams through PipeWire buses and routes."));
        String routeStatus = routeManager.status();
        String routeStatusStyle = routeStatus.startsWith("Route apply failed") ? "danger" : routeManager.active() ? "accent" : "warning";
        Label banner = label(routeStatus, routeStatusStyle);
        VBox card = card(t("Приложения → шины → выходы", "Applications → buses → outputs"));
        List<AudioNode> streams = filtered(snapshot.streams(), search.getText());
        List<AudioNode> sinks = filtered(snapshot.sinks(), search.getText());
        int sinkCount = sinks.size() + (routes.stream().anyMatch(route -> "system_default".equals(route.get("to"))) ? 1 : 0);
        double canvasHeight = Math.max(430, 100 + Math.max(buses.size(), Math.max(streams.size(), sinkCount)) * 104);
        Pane canvas = new Pane(); canvas.setMinSize(900, canvasHeight); canvas.setPrefSize(1050, canvasHeight); canvas.getStyleClass().add("canvas");
        List<Line> wires = new ArrayList<>();
        List<Node> appCards = new ArrayList<>();
        List<String> appBusIds = new ArrayList<>();
        Map<String, Node> busCards = new LinkedHashMap<>();
        Map<String, Node> sinkCards = new LinkedHashMap<>();
        double leftY = 28;
        if (streams.isEmpty()) {
            Label app = label(t("Нет активных потоков", "No active streams"), "muted"); app.setLayoutX(24); app.setLayoutY(leftY); canvas.getChildren().add(app);
        } else for (AudioNode stream : streams) {
            Map<String, Object> rule = ApplicationRuleMatcher.find(applications, stream);
            String busId = rule == null ? "" : string(rule.get("bus"), "");
            Map<String, Object> bus = buses.stream().filter(candidate -> busId.equals(candidate.get("id"))).findFirst().orElse(null);
            String appName = stream.applicationName().isBlank() ? stream.description() : stream.applicationName();
            String detail = rule == null ? t("нет сохранённого правила", "no saved rule")
                    : t("шина: ", "bus: ") + (bus == null ? busId : string(bus.get("name"), busId))
                    + (rule.get("filters_ref") == null ? "" : " · " + t("EQ приложения: ", "App EQ: ")
                    + string(rule.get("filters_ref"), "").replace("dsp.route_chains.", ""));
            VBox node = routeNode(appName, detail);
            node.setLayoutX(26); node.setLayoutY(leftY); leftY += 104; canvas.getChildren().add(node);
            appCards.add(node); appBusIds.add(bus == null ? "" : busId);
        }
        double busY = 28;
        if (buses.isEmpty()) {
            Label noBuses = label(t("Шин пока нет — добавьте шину ниже.", "No buses yet — add one below."), "muted");
            noBuses.setLayoutX(350); noBuses.setLayoutY(28); canvas.getChildren().add(noBuses);
        }
        for (Map<String, Object> bus : buses) {
            String id = string(bus.get("id"), "");
            String detail = string(bus.get("channels"), "stereo") + " · " + volumeLabel(number(bus.get("volume"), 1))
                    + (Boolean.TRUE.equals(bus.get("mute")) ? " · Mute" : "")
                    + (activeBusIds.contains(id) ? " · " + t("активна", "active") : " · " + t("выключена", "off"));
            VBox node = routeNode(string(bus.get("name"), id), detail);
            node.setLayoutX(350); node.setLayoutY(busY); busY += 104; canvas.getChildren().add(node); busCards.put(id, node);
        }
        for (int i = 0; i < appCards.size(); i++) {
            Node bus = busCards.get(appBusIds.get(i));
            if (bus != null) addWire(canvas, wires, appCards.get(i), bus);
        }
        double sinkY = 28;
        boolean needsSystemDefault = routes.stream().anyMatch(route -> "system_default".equals(route.get("to")) && !Boolean.FALSE.equals(route.get("enabled")));
        if (needsSystemDefault) {
            VBox node = routeNode(t("Системный выход", "System default"), t("текущий выход PipeWire", "current PipeWire default"));
            node.setLayoutX(680); node.setLayoutY(sinkY); sinkY += 104; canvas.getChildren().add(node); sinkCards.put("system_default", node);
        }
        for (AudioNode sink : sinks) {
            VBox node = routeNode(nodeDisplayName(sink), sink.state()); node.setLayoutX(680); node.setLayoutY(sinkY); sinkY += 104; canvas.getChildren().add(node);
            Map<String, Object> savedDevice = matchingDevice(config, sink);
            sinkCards.put(savedDevice == null ? "" : string(savedDevice.get("id"), ""), node);
        }
        for (Map<String, Object> route : routes) {
            if (Boolean.FALSE.equals(route.get("enabled")) || !activeRouteIds.contains(string(route.get("id"), ""))) continue;
            Node from = busCards.get(string(route.get("from"), ""));
            String toId = string(route.get("to"), "");
            Node to = busCards.get(toId);
            if (to == null) to = sinkCards.get(toId);
            if (to == null) {
                AudioNode target = DeviceSelectorMatcher.resolve(sinks, toId, maps(config.get("devices")));
                if (target != null) {
                    Map<String, Object> targetDevice = matchingDevice(config, target);
                    to = sinkCards.get(targetDevice == null ? "" : string(targetDevice.get("id"), ""));
                }
            }
            if (from != null && to != null) addWire(canvas, wires, from, to);
        }
        appCards.forEach(node -> makeDraggable(node, canvas, wires));
        busCards.values().forEach(node -> makeDraggable(node, canvas, wires));
        sinkCards.values().forEach(node -> makeDraggable(node, canvas, wires));
        card.getChildren().addAll(banner, canvas);
        HBox actions = new HBox(10);
        Button apply = actionButton(t("Применить к PipeWire", "Apply to PipeWire"), this::applyRoutes);
        Button clear = actionButton(t("Сбросить маршруты", "Clear routes"), this::clearRoutes);
        clear.setDisable(!routeManager.active());
        actions.getChildren().addAll(apply, clear);
        card.getChildren().add(actions);
        page.getChildren().add(card);
        VBox busesCard = card(t("Шины", "Buses"));
        for (Map<String, Object> bus : buses) busesCard.getChildren().add(busRow(bus, activeBusIds.contains(string(bus.get("id"), ""))));
        busesCard.getChildren().add(new HBox(10, actionButton(t("Добавить шину", "Add bus"), () -> editBus(null))));
        page.getChildren().add(busesCard);
        VBox routesCard = card(t("Маршруты", "Routes"));
        if (routes.isEmpty()) routesCard.getChildren().add(label(t("Маршрутов пока нет.", "No routes yet."), "muted"));
        for (Map<String, Object> route : routes) routesCard.getChildren().add(routeRow(route, activeRouteIds.contains(string(route.get("id"), ""))));
        routesCard.getChildren().add(new HBox(10, actionButton(t("Добавить маршрут", "Add route"), () -> editRoute(null))));
        page.getChildren().add(routesCard);
        page.getChildren().add(routeHistoryCard());
        return page;
    }

    private VBox routeHistoryCard() {
        VBox history = card(t("История маршрутов", "Route history"));
        history.getChildren().add(label(t("История хранится в текущем сеансе. Отмена сохраняет прежний YAML; если граф уже применён, Audio Matrix обновит его согласно восстановленной конфигурации.",
                "History is kept for this session. Undo saves the previous YAML; if a route graph is active, Audio Matrix updates it to match the restored configuration."), "muted"));
        if (routeHistory.isEmpty()) {
            history.getChildren().add(label(t("Изменений маршрутов в этом сеансе пока нет.", "No route changes in this session yet."), "muted"));
            return history;
        }
        int shown = 0;
        for (RouteHistoryEntry entry : routeHistory) {
            if (shown++ == 10) break;
            history.getChildren().add(label("• " + entry.description(), "muted"));
        }
        Button undo = actionButton(t("Отменить последнее изменение", "Undo latest change"), this::undoLastRouteChange);
        undo.setDisable(routeUndoInProgress);
        history.getChildren().add(undo);
        return history;
    }

    private void undoLastRouteChange() {
        if (routeUndoInProgress || routeHistory.isEmpty()) return;
        if (store.revision() != routeHistoryRevision) {
            routeHistory.clear();
            routeHistoryRevision = -1;
            setNotice(t("История устарела после другого изменения конфигурации; откат не выполнен.",
                    "Route history is stale after another configuration change; undo was not applied."), true);
            showPage(Page.ROUTES);
            return;
        }
        RouteHistoryEntry entry = routeHistory.peekFirst();
        routeUndoInProgress = true;
        showPage(Page.ROUTES);
        store.restoreIfRevisionAsync(entry.previousSnapshot(), routeHistoryRevision).whenComplete((revision, error) -> Platform.runLater(() -> {
            routeUndoInProgress = false;
            if (error != null) {
                routeHistory.clear();
                routeHistoryRevision = -1;
                setNotice(t("Откат маршрута не сохранён: ", "Route undo was not saved: ") + rootMessage(error), true);
            } else {
                routeHistory.removeFirst();
                routeHistoryRevision = revision;
                setNotice(t("Отменено: ", "Undid: ") + entry.description(), false);
            }
            showPage(Page.ROUTES);
        }));
    }

    private void updateRouteConfig(String description, Consumer<Map<String, Object>> change) {
        store.updateWithHistoryAsync(change).whenComplete((saved, error) -> Platform.runLater(() -> finishRouteHistory(description, saved, error)));
    }

    private void saveRouteConfig(String description, Map<String, Object> next) {
        store.saveWithHistoryAsync(next).whenComplete((saved, error) -> Platform.runLater(() -> finishRouteHistory(description, saved, error)));
    }

    private void finishRouteHistory(String description, ConfigStore.ConfigChange saved, Throwable error) {
        if (error != null) {
            setNotice(t("Конфигурация маршрута не сохранена: ", "Route configuration was not saved: ") + rootMessage(error), true);
            showPage(currentPage);
            return;
        }
        if (!routeHistory.isEmpty() && routeHistoryRevision != saved.beforeRevision()) routeHistory.clear();
        routeHistory.addFirst(new RouteHistoryEntry(description, saved.previousSnapshot()));
        while (routeHistory.size() > 20) routeHistory.removeLast();
        routeHistoryRevision = saved.afterRevision();
        setNotice(t("Изменение маршрута сохранено.", "Route change saved."), false);
        showPage(currentPage);
    }

    private VBox dspPage() {
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
                        + t(" по консервативной сумме положительных EQ gains. Limiter и полярность маршрута остаются включены. Переключение временное и не меняет YAML.",
                                " from a conservative sum of positive EQ gains. The limiter and route polarity stay enabled. This toggle is temporary and does not edit YAML."), "muted"));
        page.getChildren().add(comparison);
        Map<String, Object> limiter = map(dsp.get("limiter"));
        Map<String, Object> mixer = map(config.get("mixer"));
        VBox effectsMasterCard = card(t("Общая громкость эффектов", "Effects master volume"));
        Slider masterVolume = new Slider(0, 1, number(mixer.get("global_volume"), 0.8));
        masterVolume.setShowTickMarks(true); masterVolume.setMajorTickUnit(0.25); masterVolume.setBlockIncrement(0.01);
        masterVolume.setAccessibleText(t("Общая громкость только маршрутов Audio Matrix", "Effects master volume for Audio Matrix routes only"));
        Label masterValue = label(String.format(Locale.ROOT, "%.0f%%", masterVolume.getValue() * 100), "stat-value");
        masterVolume.valueProperty().addListener((observable, oldValue, value) -> masterValue.setText(String.format(Locale.ROOT, "%.0f%%", value.doubleValue() * 100)));
        Runnable saveMaster = () -> updateConfig(candidate -> map(candidate.get("mixer")).put("global_volume", Math.round(masterVolume.getValue() * 100.0) / 100.0));
        masterVolume.setOnMouseReleased(event -> saveMaster.run()); masterVolume.setOnKeyReleased(event -> saveMaster.run());
        CheckBox masterMute = new CheckBox(t("Заглушить маршруты Audio Matrix", "Mute Audio Matrix routes"));
        masterMute.setSelected(Boolean.TRUE.equals(mixer.get("global_mute")));
        masterMute.setOnAction(event -> updateConfig(candidate -> map(candidate.get("mixer")).put("global_mute", masterMute.isSelected())));
        effectsMasterCard.getChildren().addAll(new HBox(10, label(t("Уровень", "Level"), "muted"), masterVolume, masterValue), masterMute,
                label(t("Управляет только шинами и маршрутами Audio Matrix; громкость других приложений не меняется.",
                        "Controls only Audio Matrix buses and routes; other apps are unaffected."), "muted"));
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
        future.getChildren().addAll(label(t("Профиль может хранить независимый EQ и отдельные notch дребезга. Кривые строятся только по импортированной или записанной WAV-измерительной записи; субъективные заметки остаются непроверенными.", "A profile can store an independent EQ and separate rattle notches. Curves are derived only from imported or recorded WAV measurements; subjective notes remain unverified."), ""), label(t("A/B временно переключает активный граф PipeWire: A — сохранённый EQ, B — bypass EQ с консервативным выравниванием gain. Limiter и полярность остаются включены; YAML не меняется. Сначала примените граф маршрутов.", "A/B temporarily switches the active PipeWire graph: A uses the saved EQ, while B bypasses EQ with conservative gain matching. The limiter and route polarity stay enabled; YAML is unchanged. Apply the route graph first."), "muted"));
        page.getChildren().add(future);
        return page;
    }

    private Node limiterControl(String title, String key, double minimum, double maximum, double initial, String unit) {
        Slider slider = new Slider(minimum, maximum, Math.max(minimum, Math.min(maximum, initial))); slider.setAccessibleText(title);
        slider.setShowTickMarks(true);
        slider.setMajorTickUnit("release_ms".equals(key) ? 50 : ("lookahead_ms".equals(key) ? 1 : 3));
        slider.setBlockIncrement("release_ms".equals(key) ? 5 : 0.1);
        Label value = label("", "muted"); value.setMinWidth(64);
        Runnable refreshValue = () -> value.setText(("release_ms".equals(key)
                ? Long.toString(Math.round(slider.getValue())) : String.format(Locale.ROOT, "%.1f", slider.getValue())) + " " + unit);
        Runnable save = () -> updateLimiter(key, "release_ms".equals(key)
                ? (double) Math.round(slider.getValue()) : Math.round(slider.getValue() * 10.0) / 10.0);
        slider.valueProperty().addListener((observable, oldValue, newValue) -> refreshValue.run());
        slider.setOnMouseReleased(event -> save.run());
        slider.setOnKeyReleased(event -> save.run());
        refreshValue.run();
        HBox row = new HBox(10, label(title, "muted"), slider, value);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, Priority.ALWAYS);
        return row;
    }

    private VBox calibrationPage() {
        VBox page = page(t("Ручная проверка тоном, коротким импульсом и sweep. Измерительная калибровка требует реального датчика.", "Manual tone, short impulse, and sweep checks. Measurement calibration requires a real sensor."));
        VBox test = card(t("Ручная калибровка", "Manual calibration"));
        test.getChildren().add(label(t("Сигналы идут только на выбранный sink и требуют проверки аппаратной цепи и отдельного подтверждения. Импульс ограничен 10 ms, 100 Hz, peak 0.005 PCM и уровнем pw-play 0.03; усилитель может добавить неизвестное усиление.", "Signals go only to the selected sink and require a hardware-chain check plus a separate confirmation. The impulse is capped at 10 ms, 100 Hz, 0.005 PCM peak, and pw-play level 0.03; the amplifier may add unknown gain."), "warning"));
        CheckBox hardwareChecked = new CheckBox(t("Я проверил выбранный выход, усилитель, питание и нагрузку.",
                "I checked the selected output, amplifier, power supply, and load."));
        hardwareChecked.setAccessibleText(t("Подтверждение проверки аппаратной цепи", "Hardware path check confirmation"));
        ComboBox<AudioNode> outputs = new ComboBox<>(); outputs.getItems().setAll(snapshot.sinks()); outputs.setCellFactory(list -> nodeCell()); outputs.setButtonCell(nodeCell()); outputs.setPromptText(t("Выбрать выход", "Select output")); outputs.setAccessibleText(t("Выход для проверки", "Output under test"));
        if (selectedSink != null) outputs.getSelectionModel().select(selectedSink);
        outputs.setOnAction(e -> { selectedSink = outputs.getValue(); hardwareChecked.setSelected(false); });
        TextField frequency = field("80", t("Частота (Hz)", "Frequency (Hz)"));
        TextField duration = field("500", t("Длительность (ms)", "Duration (ms)"));
        TextField sweepStart = field("30", t("Начало sweep (Hz)", "Sweep start (Hz)"));
        TextField sweepEnd = field("300", t("Конец sweep (Hz)", "Sweep end (Hz)"));
        TextField sweepSteps = field("8", t("Шаги (2–16)", "Steps (2–16)"));
        TextField observationFrequency = field("80", t("Проблемная частота (Hz)", "Problem frequency (Hz)"));
        TextField observationNote = field("", t("Что вы слышите или чувствуете?", "What do you hear or feel?"));
        VBox sweepStartField = new VBox(4, label(t("Начало (Hz)", "Start (Hz)"), "muted"), sweepStart);
        VBox sweepEndField = new VBox(4, label(t("Конец (Hz)", "End (Hz)"), "muted"), sweepEnd);
        VBox sweepStepsField = new VBox(4, label(t("Шаги (2–16)", "Steps (2–16)"), "muted"), sweepSteps);
        VBox observationFrequencyField = new VBox(4, label(t("Частота (Hz)", "Frequency (Hz)"), "muted"), observationFrequency);
        VBox observationNoteField = new VBox(4, label(t("Субъективное наблюдение", "Listening observation"), "muted"), observationNote);
        ComboBox<String> phase = new ComboBox<>(); phase.getItems().setAll("before", "after"); phase.setValue("before");
        phase.setAccessibleText(t("Этап сравнения", "Comparison stage"));
        Label frequencyLabel = label(t("Частота, Гц", "Frequency, Hz"), ""); frequencyLabel.setLabelFor(frequency);
        Label durationLabel = label(t("Длительность, мс", "Duration, ms"), ""); durationLabel.setLabelFor(duration);
        Button[] manualImpulseRef = new Button[1];
        Button manualImpulse = actionButton(t("Короткий импульс · 10 ms", "Short impulse · 10 ms"), () -> {
            try {
                if (selectedSink == null) throw new IllegalStateException(t("Сначала выберите выход.", "Choose an output first."));
                if (!hardwareChecked.isSelected()) throw new IllegalStateException(t("Подтвердите проверку выхода, усилителя и нагрузки.",
                        "Confirm that the output, amplifier, and load have been checked."));
                String signal = t("Импульс 100 Hz, 10 ms · PCM peak ≤ 0.005 · pw-play 0.03",
                        "Impulse 100 Hz, 10 ms · PCM peak ≤ 0.005 · pw-play 0.03");
                if (!confirmCalibrationOutput(selectedSink, signal)) return;
                AudioNode output = selectedSink;
                manualImpulseRef[0].setDisable(true);
                backend.playManualImpulse(output).whenComplete((v, error) -> Platform.runLater(() -> {
                    manualImpulseRef[0].setDisable(!hardwareChecked.isSelected());
                    setNotice(error == null ? t("Импульс завершён. Это слуховая проверка, не измерение.", "Impulse finished. This is a listening check, not a measurement.")
                            : t("Импульс не воспроизведён: ", "Impulse was not played: ") + rootMessage(error), error != null);
                }));
            } catch (Exception e) { setNotice(e.getMessage(), true); }
        });
        manualImpulseRef[0] = manualImpulse;
        manualImpulse.setAccessibleText(t("Воспроизвести короткий ограниченный импульс на выбранном выходе", "Play a short, bounded impulse on the selected output"));
        Button play = actionButton(t("Воспроизвести тестовый тон", "Play test tone"), () -> {
            try {
                if (selectedSink == null) throw new IllegalStateException(t("Сначала выберите выход.", "Choose an output first."));
                if (!hardwareChecked.isSelected()) throw new IllegalStateException(t("Подтвердите проверку выхода, усилителя и нагрузки.",
                        "Confirm that the output, amplifier, and load have been checked."));
                double hz = Double.parseDouble(frequency.getText()); int ms = Integer.parseInt(duration.getText());
                if (!confirmCalibrationOutput(selectedSink, t("Тон %.0f Hz, %d ms", "Tone %.0f Hz, %d ms").formatted(hz, ms))) return;
                backend.playTestTone(selectedSink, hz, ms).whenComplete((v, error) -> Platform.runLater(() -> setNotice(error == null ? t("Тестовый тон завершён. Это не измерение.", "Test tone finished. This is not a measurement.") : t("Тон не воспроизведён: ", "Tone was not played: ") + rootMessage(error), error != null)));
            } catch (Exception e) { setNotice(e.getMessage(), true); }
        });
        Button sweep = actionButton(t("Ступенчатый sweep · до 3.2 s", "Stepped sweep · up to 3.2 s"), () -> {
            try {
                if (selectedSink == null) throw new IllegalStateException(t("Сначала выберите выход.", "Choose an output first."));
                if (!hardwareChecked.isSelected()) throw new IllegalStateException(t("Подтвердите проверку выхода, усилителя и нагрузки.",
                        "Confirm that the output, amplifier, and load have been checked."));
                double startHz = Double.parseDouble(sweepStart.getText().trim());
                double endHz = Double.parseDouble(sweepEnd.getText().trim());
                int steps = Integer.parseInt(sweepSteps.getText().trim());
                if (!confirmCalibrationOutput(selectedSink, t("Sweep %.0f–%.0f Hz, %d шагов", "Sweep %.0f–%.0f Hz, %d steps").formatted(startHz, endHz, steps))) return;
                backend.playSteppedSweep(selectedSink, startHz, endHz, steps).whenComplete((v, error) -> Platform.runLater(() ->
                        setNotice(error == null ? t("Sweep завершён. Это слуховая проверка, не измерение.", "Sweep finished. This is a listening check, not a measurement.")
                                : t("Sweep не воспроизведён: ", "Sweep was not played: ") + rootMessage(error), error != null)));
            } catch (Exception e) { setNotice(e.getMessage(), true); }
        });
        Runnable updateManualTestAvailability = () -> {
            manualImpulse.setDisable(!hardwareChecked.isSelected());
            play.setDisable(!hardwareChecked.isSelected());
            sweep.setDisable(!hardwareChecked.isSelected());
        };
        hardwareChecked.selectedProperty().addListener((observable, oldValue, newValue) -> updateManualTestAvailability.run());
        updateManualTestAvailability.run();
        test.getChildren().addAll(outputs, hardwareChecked, manualImpulse,
                label(t("Максимальные параметры импульса: 10 ms · 100 Hz · 0.005 PCM · pw-play 0.03", "Impulse caps: 10 ms · 100 Hz · 0.005 PCM · pw-play 0.03"), "muted"),
                new HBox(12, frequencyLabel, frequency, durationLabel, duration), play,
                label(t("Ограниченный ступенчатый sweep · 20–1000 Hz, максимум 16 × 200 ms", "Bounded stepped sweep · 20–1000 Hz, maximum 16 × 200 ms"), "muted"),
                new HBox(12, sweepStartField, sweepEndField, sweepStepsField), sweep);
        VBox observations = card(t("Ручные заметки · сравнение до/после", "Manual notes · before/after comparison"));
        observations.getChildren().add(label(t("Это субъективные заметки, не показания датчика и не измеренная АЧХ.", "These are subjective notes, not sensor readings or a measured frequency response."), "muted"));
        Button saveObservation = actionButton(t("Сохранить наблюдение", "Save observation"), () -> {
            try {
                if (selectedSink == null) throw new IllegalStateException(t("Сначала выберите выход.", "Choose an output first."));
                double hz = Double.parseDouble(observationFrequency.getText().trim());
                String note = observationNote.getText().trim();
                if (!Double.isFinite(hz) || hz < 20 || hz > 1000) throw new IllegalArgumentException(t("Частота должна быть от 20 до 1000 Hz.", "Frequency must be between 20 and 1000 Hz."));
                if (note.isBlank()) throw new IllegalArgumentException(t("Добавьте короткое субъективное наблюдение.", "Enter a short listening observation."));
                updateConfig(config -> {
                    Map<String, Object> device = ensureDevice(config, selectedSink);
                    maps(config.get("calibrations")).add(DefaultConfigMap.map("id", "manual-" + UUID.randomUUID().toString().substring(0, 8),
                            "device", device.get("id"), "channel", null, "method", "manual", "measurement_file", null,
                            "filters_ref", null, "verified", false, "frequency_hz", hz, "phase", phase.getValue(),
                            "observed_at", Instant.now().toString(), "notes", note));
                });
                observationNote.clear();
            } catch (Exception invalid) { setNotice(invalid.getMessage(), true); }
        });
        observations.getChildren().add(new HBox(10, observationFrequencyField, observationNoteField, phase, saveObservation));
        List<Map<String, Object>> savedObservations = maps(store.snapshot().get("calibrations")).stream()
                .filter(item -> "manual".equals(item.get("method")) && item.get("frequency_hz") instanceof Number).toList();
        if (savedObservations.isEmpty()) observations.getChildren().add(label(t("Пока нет сохранённых заметок.", "No saved notes yet."), "muted"));
        for (Map<String, Object> item : savedObservations) {
            String id = string(item.get("id"), "");
            String detail = string(item.get("phase"), "before") + " · " + number(item.get("frequency_hz"), 0) + " Hz · " + string(item.get("notes"), "");
            HBox noteRow = new HBox(8); noteRow.setAlignment(Pos.CENTER_LEFT);
            Label noteLabel = label(detail, "muted"); HBox.setHgrow(noteLabel, Priority.ALWAYS);
            noteRow.getChildren().addAll(noteLabel, actionButton(t("Удалить", "Delete"), () -> deleteCalibrationNote(id)));
            observations.getChildren().add(noteRow);
        }
        VBox measurement = card(t("Анализ записи датчика", "Analyze a sensor recording"));
        measurement.getChildren().add(label(t("Импортированный WAV анализируется офлайн. Синхронная запись ниже открывает только выбранный источник и воспроизводит короткий sweep на выбранном выходе после отдельного подтверждения. Результат показывает относительный уровень, не SPL и не ускорение в физических единицах.",
                "Imported WAV files are analyzed offline. Synchronized capture below opens only the selected input and plays a short sweep to the selected output after a separate confirmation. Results show relative level, not SPL or acceleration in physical units."), "warning"));
        ComboBox<String> sensorMethod = new ComboBox<>(); sensorMethod.getItems().setAll("microphone", "accelerometer"); sensorMethod.setValue("accelerometer");
        sensorMethod.setAccessibleText(t("Тип датчика", "Sensor type"));
        ComboBox<Integer> sensorChannel = new ComboBox<>(); sensorChannel.getItems().setAll(0, 1); sensorChannel.setValue(0);
        sensorChannel.setAccessibleText(t("Канал WAV, начиная с 0", "WAV channel, zero-based"));
        ComboBox<AudioNode> sensorSource = new ComboBox<>();
        sensorSource.getItems().setAll(snapshot.sources().stream().filter(node -> node.kind().equals("input")
                && node.channels() >= 1 && node.channels() <= 2).toList());
        sensorSource.setCellFactory(list -> nodeCell()); sensorSource.setButtonCell(nodeCell());
        sensorSource.setPromptText(t("Выбрать вход датчика", "Select sensor input"));
        sensorSource.setAccessibleText(t("Вход PipeWire для синхронной записи", "PipeWire input for synchronized capture"));
        CheckBox sensorChecked = new CheckBox(t("Я проверил подключение и закрепление датчика.",
                "I checked the sensor connection and mounting."));
        sensorChecked.setAccessibleText(t("Подтверждение подключения датчика", "Sensor connection confirmation"));
        sensorSource.setOnAction(event -> sensorChecked.setSelected(false));
        TextField captureOffset = field("0", t("Начало sweep в записи, ms", "Sweep start in recording, ms"));
        TextField sensorMount = field("", t("Положение датчика", "Sensor position"));
        Label recordingPath = label(calibrationRecordingPath == null ? t("WAV не выбран.", "No WAV selected.")
                : calibrationRecordingPath.getFileName().toString(), "muted");
        Path[] recording = {calibrationRecordingPath};
        Button[] estimateOffsetRef = new Button[1];
        Button[] chooseRecordingRef = new Button[1];
        Button[] analyzeButtonRef = new Button[1];
        Button estimateOffset = actionButton(t("Найти начало sweep", "Estimate sweep start"), () -> {
            try {
                if (recording[0] == null) throw new IllegalArgumentException(t("Сначала выберите WAV.", "Choose a WAV file first."));
                int channel = sensorChannel.getValue() == null ? 0 : sensorChannel.getValue();
                double startHz = Double.parseDouble(sweepStart.getText().trim());
                double endHz = Double.parseDouble(sweepEnd.getText().trim());
                int steps = Integer.parseInt(sweepSteps.getText().trim());
                Path source = recording[0];
                estimateOffsetRef[0].setDisable(true); chooseRecordingRef[0].setDisable(true); analyzeButtonRef[0].setDisable(true);
                sensorChannel.setDisable(true); sweepStart.setDisable(true); sweepEnd.setDisable(true); sweepSteps.setDisable(true);
                setNotice(t("Ищу последовательность sweep в первых 10 секундах записи; звук не воспроизводится…",
                        "Searching for the stepped-sine sequence in the first 10 seconds; no audio is played…"), false);
                Thread.ofVirtual().name("audio-matrix-calibration-alignment").start(() -> {
                    try {
                        CalibrationMeasurementAnalyzer.Recording input = CalibrationMeasurementAnalyzer.readPcm16Wav(source, channel);
                        CalibrationMeasurementAnalyzer.OffsetEstimate estimate = CalibrationMeasurementAnalyzer.estimateSweepOffset(
                                input, startHz, endHz, steps, 200, 10_000);
                        Platform.runLater(() -> {
                            captureOffset.setText(Integer.toString(estimate.offsetMs()));
                            estimateOffsetRef[0].setDisable(false); chooseRecordingRef[0].setDisable(false); analyzeButtonRef[0].setDisable(false);
                            sensorChannel.setDisable(false); sweepStart.setDisable(false); sweepEnd.setDisable(false); sweepSteps.setDisable(false);
                            setNotice(t("Начало sweep примерно на %d ms (неопределённость ±%d ms, оценка %.0f%%). Проверьте/поправьте смещение и постройте кривую.",
                                            "Sweep start estimated at %d ms (uncertainty ±%d ms, fit %.0f%%). Review or adjust the offset, then plot the curve.")
                                    .formatted(estimate.offsetMs(), estimate.uncertaintyMs(), estimate.sequenceScore() * 100), false);
                        });
                    } catch (Exception failure) {
                        Platform.runLater(() -> {
                            estimateOffsetRef[0].setDisable(false); chooseRecordingRef[0].setDisable(false); analyzeButtonRef[0].setDisable(false);
                            sensorChannel.setDisable(false); sweepStart.setDisable(false); sweepEnd.setDisable(false); sweepSteps.setDisable(false);
                            setNotice(t("Смещение не определено: ", "Could not estimate the offset: ") + rootMessage(failure), true);
                        });
                    }
                });
            } catch (Exception invalid) { setNotice(invalid.getMessage(), true); }
        });
        estimateOffsetRef[0] = estimateOffset;
        estimateOffset.setAccessibleText(t("Офлайн-поиск начала тестовой частотной последовательности", "Offline search for the start of the calibration frequency sequence"));
        estimateOffset.setDisable(recording[0] == null);
        Button chooseRecording = actionButton(t("Выбрать WAV", "Choose WAV"), () -> {
            FileChooser chooser = new FileChooser(); chooser.setTitle(t("Выберите запись датчика", "Choose sensor recording"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAV PCM", "*.wav", "*.WAV"));
            java.io.File selected = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
            if (selected != null) {
                discardTemporaryCalibrationPreview();
                recording[0] = selected.toPath(); calibrationRecordingPath = recording[0];
                calibrationPreview = null; calibrationPreviewSaved = false; calibrationComparisonPreview = null;
                calibrationFilterSuggestions = List.of(); calibrationFilterPreviewShown = false;
                calibrationImpulsePreview = null; calibrationImpulsePreviewSaved = false;
                recordingPath.setText(recording[0].getFileName().toString()); estimateOffset.setDisable(false); showPage(Page.CALIBRATION);
            }
        });
        chooseRecordingRef[0] = chooseRecording;
        Button analyzeRecording = actionButton(t("Построить относительный отклик", "Plot relative response"), () -> {
            try {
                if (recording[0] == null) throw new IllegalArgumentException(t("Сначала выберите WAV.", "Choose a WAV file first."));
                AudioNode output = selectedSink;
                if (output == null) throw new IllegalArgumentException(t("Сначала выберите выход в блоке ручной калибровки.", "Choose an output in the manual calibration section first."));
                int channel = sensorChannel.getValue() == null ? 0 : sensorChannel.getValue();
                int offset = Integer.parseInt(captureOffset.getText().trim());
                double startHz = Double.parseDouble(sweepStart.getText().trim());
                double endHz = Double.parseDouble(sweepEnd.getText().trim());
                int steps = Integer.parseInt(sweepSteps.getText().trim());
                String method = sensorMethod.getValue(); String mount = sensorMount.getText().trim();
                Path source = recording[0];
                chooseRecording.setDisable(true); analyzeButtonRef[0].setDisable(true);
                setNotice(t("Анализирую WAV без воспроизведения…", "Analyzing WAV without playback…"), false);
                Thread.ofVirtual().name("audio-matrix-calibration-analysis").start(() -> {
                    try {
                        CalibrationMeasurementAnalyzer.Recording input = CalibrationMeasurementAnalyzer.readPcm16Wav(source, channel);
                        CalibrationMeasurementAnalyzer.Result result = CalibrationMeasurementAnalyzer.analyze(input,
                                startHz, endHz, steps, 200, offset);
                        Path temporaryDirectory = calibrationPreview != null && source.equals(calibrationPreview.source())
                                ? calibrationPreview.temporaryDirectory() : null;
                        MeasurementPreview preview = new MeasurementPreview(source, output, method, null, null, channel, startHz, endHz,
                                steps, 200, offset, mount, result, null, null, null, temporaryDirectory);
                        Platform.runLater(() -> {
                            calibrationPreview = preview; calibrationPreviewSaved = false; calibrationComparisonPreview = null;
                            calibrationFilterSuggestions = List.of(); calibrationFilterPreviewShown = false;
                            chooseRecording.setDisable(false); analyzeButtonRef[0].setDisable(false);
                            showPage(Page.CALIBRATION);
                            setNotice(result.clipped() ? t("Кривая рассчитана, но в WAV есть цифрово обрезанные отсчёты.", "Curve calculated, but the WAV contains digitally clipped samples.")
                                    : t("Относительная кривая рассчитана. Единицы датчика неизвестны.", "Relative curve calculated. Sensor units are unknown."), result.clipped());
                        });
                    } catch (Exception failure) {
                        Platform.runLater(() -> { chooseRecording.setDisable(false); analyzeButtonRef[0].setDisable(false); setNotice(rootMessage(failure), true); });
                    }
                });
            } catch (Exception invalid) { setNotice(invalid.getMessage(), true); }
        });
        analyzeButtonRef[0] = analyzeRecording;
        TextField impulseOffset = field("500", t("Импульс от начала WAV, ms", "Impulse offset from WAV start, ms"));
        TextField impulseWindow = field("2000", t("Окно отклика, ms", "Response window, ms"));
        Button[] analyzeImpulseRef = new Button[1];
        Button analyzeImpulse = actionButton(t("Анализировать затухание", "Analyze impulse decay"), () -> {
            try {
                if (recording[0] == null) throw new IllegalArgumentException(t("Сначала выберите WAV.", "Choose a WAV file first."));
                int channel = sensorChannel.getValue() == null ? 0 : sensorChannel.getValue();
                int offsetMs = Integer.parseInt(impulseOffset.getText().trim());
                int windowMs = Integer.parseInt(impulseWindow.getText().trim());
                String method = sensorMethod.getValue();
                String mount = sensorMount.getText().trim();
                if (mount.isBlank()) throw new IllegalArgumentException(t("Укажите положение датчика.", "Enter the sensor position."));
                Path source = recording[0];
                AudioNode output = selectedSink;
                chooseRecording.setDisable(true); analyzeRecording.setDisable(true); estimateOffset.setDisable(true);
                analyzeImpulseRef[0].setDisable(true); sensorChannel.setDisable(true); sensorMethod.setDisable(true); sensorMount.setDisable(true);
                setNotice(t("Анализирую импульс и затухание офлайн; звук не воспроизводится…",
                        "Analyzing the impulse and decay offline; no audio is played…"), false);
                Thread.ofVirtual().name("audio-matrix-impulse-analysis").start(() -> {
                    try {
                        CalibrationMeasurementAnalyzer.Recording input = CalibrationMeasurementAnalyzer.readPcm16Wav(source, channel);
                        CalibrationImpulseAnalyzer.Result result = CalibrationImpulseAnalyzer.analyze(input, offsetMs, windowMs);
                        ImpulseDecayPreview preview = new ImpulseDecayPreview(source, output, method, channel, mount,
                                offsetMs, windowMs, result, null, null, null);
                        Platform.runLater(() -> {
                            calibrationImpulsePreview = preview; calibrationImpulsePreviewSaved = false;
                            chooseRecording.setDisable(false); analyzeRecording.setDisable(false); estimateOffset.setDisable(false);
                            analyzeImpulseRef[0].setDisable(false); sensorChannel.setDisable(false); sensorMethod.setDisable(false); sensorMount.setDisable(false);
                            showPage(Page.CALIBRATION);
                            setNotice(result.clipped() ? t("Анализ затухания рассчитан, но в выбранном окне есть клиппинг.",
                                            "Decay analysis completed, but the selected window contains clipping.")
                                    : t("Затухание рассчитано из WAV. T60 и Q — оценки, не физическая калибровка.",
                                            "Decay was analyzed from the WAV. T60 and Q are estimates, not physical calibration."), result.clipped());
                        });
                    } catch (Exception failure) {
                        Platform.runLater(() -> {
                            chooseRecording.setDisable(false); analyzeRecording.setDisable(false); estimateOffset.setDisable(false);
                            analyzeImpulseRef[0].setDisable(false); sensorChannel.setDisable(false); sensorMethod.setDisable(false); sensorMount.setDisable(false);
                            setNotice(t("Затухание не рассчитано: ", "Could not analyze impulse decay: ") + rootMessage(failure), true);
                        });
                    }
                });
            } catch (Exception invalid) { setNotice(invalid.getMessage(), true); }
        });
        analyzeImpulseRef[0] = analyzeImpulse;
        Button[] recordSweepRef = new Button[1];
        Button[] cancelCaptureRef = new Button[1];
        Button recordSweep = actionButton(t("Записать sweep с датчиком", "Record a synchronized sensor sweep"),
                () -> beginCalibrationCapture(sensorSource.getValue(), sensorMethod.getValue(), sensorChannel.getValue(),
                        sensorMount.getText(), sweepStart.getText(), sweepEnd.getText(), sweepSteps.getText(), captureOffset,
                        hardwareChecked, sensorChecked, chooseRecording, analyzeRecording, recordingPath, recordSweepRef[0], cancelCaptureRef[0], test));
        Button cancelCapture = actionButton(t("Отменить запись", "Cancel capture"), () -> {
            backend.stopCalibrationCapture();
            setNotice(t("Останавливаю запись и sweep…", "Stopping sensor capture and sweep…"), false);
        });
        recordSweepRef[0] = recordSweep;
        cancelCaptureRef[0] = cancelCapture;
        boolean captureActive = calibrationCaptureTask != null && !calibrationCaptureTask.isDone();
        Runnable updateCaptureAvailability = () -> recordSweep.setDisable(captureActive || selectedSink == null
                || sensorSource.getValue() == null || !hardwareChecked.isSelected() || !sensorChecked.isSelected());
        hardwareChecked.selectedProperty().addListener((observable, oldValue, newValue) -> updateCaptureAvailability.run());
        sensorSource.valueProperty().addListener((observable, oldValue, newValue) -> updateCaptureAvailability.run());
        sensorChecked.selectedProperty().addListener((observable, oldValue, newValue) -> updateCaptureAvailability.run());
        updateCaptureAvailability.run();
        cancelCapture.setDisable(!captureActive);
        test.setDisable(captureActive);
        chooseRecording.setDisable(captureActive);
        analyzeRecording.setDisable(captureActive);
        estimateOffset.setDisable(captureActive || recording[0] == null);
        analyzeImpulse.setDisable(captureActive || recording[0] == null);
        hardwareChecked.setDisable(captureActive);
        measurement.getChildren().addAll(new HBox(10, sensorMethod, sensorChannel, captureOffset),
                new HBox(10, sensorSource, sensorMount), new HBox(10, chooseRecording, recordingPath, estimateOffset, analyzeRecording),
                sensorChecked, new FlowPane(10, 8, recordSweep, cancelCapture),
                new HBox(10, impulseOffset, impulseWindow, analyzeImpulse));
        measurement.getChildren().add(label(t("Для анализа импульса оставьте не менее 20 ms предзаписи; смещение и окно указываются относительно начала WAV. Кривая затухания и T20 строятся по энергии сигнала, T60 экстраполируется, Q выводится из T60 и доминирующей частоты. Все результаты остаются непроверенными.",
                        "Keep at least 20 ms of pre-roll for impulse analysis; offset and window are relative to the WAV start. The decay curve and T20 use signal energy, T60 is extrapolated, and Q is derived from T60 and the dominant frequency. Results remain unverified."), "muted"));
        if (calibrationImpulsePreview != null) {
            ImpulseDecayPreview preview = calibrationImpulsePreview;
            measurement.getChildren().addAll(label(impulseSummary(preview.result()), preview.result().clipped() ? "warning" : "muted"),
                    calibrationImpulseChart(preview.result()));
            if (calibrationImpulsePreviewSaved) {
                measurement.getChildren().add(label(t("WAV и CSV затухания сохранены в YAML-профиле.", "WAV and decay CSV are saved in the YAML profile."), "muted"));
            } else {
                Button saveImpulse = actionButton(t("Сохранить WAV и затухание в YAML-профиль", "Save WAV and decay to the YAML profile"),
                        () -> saveImpulseCalibration(preview));
                saveImpulse.setDisable(preview.output() == null);
                Button discardImpulse = actionButton(t("Отбросить анализ затухания", "Discard decay analysis"), () -> {
                    calibrationImpulsePreview = null; calibrationImpulsePreviewSaved = false; showPage(Page.CALIBRATION);
                });
                measurement.getChildren().add(new FlowPane(10, 8, saveImpulse, discardImpulse));
                if (preview.output() == null)
                    measurement.getChildren().add(label(t("Выберите выход выше, чтобы привязать измерение к профилю.",
                            "Select an output above to associate this measurement with a profile."), "muted"));
            }
        }
        if (calibrationPreview != null) {
            String quality = calibrationPreview.result().clipped()
                    ? t("Есть клиппинг входной записи; пики кривой могут быть занижены.", "The input recording is clipped; response peaks may be understated.")
                    : t("Относительный уровень нормирован к медиане точек. Глубокие провалы не преобразуются в усиление.", "Relative level is normalized to the median point. Deep nulls are not turned into boost.");
            measurement.getChildren().addAll(label(quality, calibrationPreview.result().clipped() ? "warning" : "muted"),
                    calibrationFilterPreviewShown && !calibrationFilterSuggestions.isEmpty()
                            ? calibrationFilterChart(calibrationPreview.result(), calibrationPreview.method(), calibrationFilterSuggestions)
                            : calibrationChart(calibrationPreview.result(), calibrationPreview.method()),
                    calibrationPointsTable(calibrationPreview.result()));
            VBox filterProposal = new VBox(8);
            filterProposal.getStyleClass().add("node-card");
            filterProposal.getChildren().addAll(label(t("Предпросмотр коррекции", "Filter correction preview"), "card-title"),
                    label(t("Только выраженные пики: максимум 3 отрицательных PEQ-среза до −3 dB. Прогноз рассчитан по относительной кривой и не заменяет повторное измерение; провалы не усиливаются.",
                                    "Only clear peaks: at most 3 subtractive PEQ cuts up to −3 dB. This prediction uses the relative curve and does not replace a repeat measurement; nulls are never boosted."), "muted"));
            if (!calibrationFilterPreviewShown) {
                Button suggest = actionButton(t("Предложить срезы и показать прогноз", "Suggest cuts and show prediction"), () -> {
                    calibrationFilterSuggestions = CalibrationFilterAdvisor.suggest(calibrationPreview.result());
                    calibrationFilterPreviewShown = true;
                    showPage(Page.CALIBRATION);
                    setNotice(calibrationFilterSuggestions.isEmpty()
                            ? t("Надёжных пиков для умеренного среза не найдено; фильтры не изменены.", "No clear peaks qualify for a moderate cut; filters were not changed.")
                            : t("Показан offline-прогноз. Фильтры ещё не сохранены и не применены.", "An offline prediction is shown. Filters have not been saved or applied."), false);
                });
                suggest.setDisable(calibrationPreview.result().clipped());
                filterProposal.getChildren().add(suggest);
                if (calibrationPreview.result().clipped())
                    filterProposal.getChildren().add(label(t("Из-за клиппинга предложения недоступны.", "Suggestions are unavailable because the recording is clipped."), "warning"));
            } else if (calibrationFilterSuggestions.isEmpty()) {
                filterProposal.getChildren().add(label(t("Выраженных локальных пиков не найдено. Автоподъём или срез не предлагается.",
                        "No clear local peaks were found. No automatic boost or cut is suggested."), "muted"));
                filterProposal.getChildren().add(actionButton(t("Скрыть прогноз", "Hide prediction"), () -> {
                    calibrationFilterPreviewShown = false; showPage(Page.CALIBRATION);
                }));
            } else {
                for (CalibrationFilterAdvisor.Suggestion suggestion : calibrationFilterSuggestions) {
                    filterProposal.getChildren().add(label(String.format(Locale.ROOT, "%.1f Hz · %+.1f dB · Q %.1f · ",
                                    suggestion.frequencyHz(), suggestion.gainDb(), suggestion.q())
                                    + t("выступание ", "peak prominence ") + String.format(Locale.ROOT, "%.1f dB", suggestion.prominenceDb()), "muted"));
                }
                Button applyCuts = actionButton(t("Сохранить и применить срезы", "Save and apply cuts"),
                        () -> applyCalibrationFilterProposal(calibrationPreview, hardwareChecked));
                applyCuts.setAccessibleText(t("Подтвердить и применить прогнозируемые фильтры к выбранному выходу", "Confirm and apply predicted filters to the selected output"));
                boolean canApply = calibrationPreviewSaved && calibrationPreview.calibrationId() != null
                        && calibrationPreview.output() != null && selectedSink != null
                        && calibrationPreview.output().id() == selectedSink.id() && snapshot.connected()
                        && !calibrationPreview.result().clipped() && hardwareChecked.isSelected();
                applyCuts.setDisable(!canApply);
                hardwareChecked.selectedProperty().addListener((observable, oldValue, newValue) ->
                        applyCuts.setDisable(!(calibrationPreviewSaved && calibrationPreview.calibrationId() != null
                                && calibrationPreview.output() != null && selectedSink != null
                                && calibrationPreview.output().id() == selectedSink.id() && snapshot.connected()
                                && !calibrationPreview.result().clipped() && newValue)));
                filterProposal.getChildren().addAll(applyCuts,
                        label(calibrationPreviewSaved
                                        ? t("Применение требует сохранённого WAV, совпадения с выбранным выходом и подтверждения аппаратной цепи.",
                                                "Applying requires a saved WAV, a match with the selected output, and hardware-chain acknowledgement.")
                                        : t("Сначала сохраните WAV и кривую, чтобы привязать фильтры к калибровочному профилю.",
                                                "Save the WAV and curve first so the filters can be linked to the calibration profile."), "muted"),
                        actionButton(t("Скрыть прогноз", "Hide prediction"), () -> {
                            calibrationFilterPreviewShown = false; showPage(Page.CALIBRATION);
                        }));
            }
            measurement.getChildren().add(filterProposal);
            if (calibrationPreviewSaved) {
                measurement.getChildren().add(label(t("Запись WAV и CSV кривой сохранены в папке measurements.", "WAV recording and curve CSV are saved under measurements."), "muted"));
            } else {
                Button saveMeasurement = actionButton(t("Сохранить запись и кривую в YAML-профиль", "Save recording and curve to the YAML profile"),
                        () -> saveCalibrationMeasurement(calibrationPreview));
                Button discardMeasurement = actionButton(t("Отбросить результат", "Discard result"), () -> {
                    discardTemporaryCalibrationPreview();
                    showPage(Page.CALIBRATION);
                    setNotice(t("Несохранённый результат отброшен.", "Unsaved result discarded."), false);
                });
                measurement.getChildren().add(new FlowPane(10, 8, saveMeasurement, discardMeasurement));
            }
        }
        if (calibrationFilterRollback != null) {
            measurement.getChildren().addAll(label(t("Сохранённая коррекция: ", "Saved correction: ")
                            + calibrationFilterRollback.outputDescription(), "muted"),
                    actionButton(t("Откатить последний фильтр калибровки", "Roll back the latest calibration filter"),
                            this::rollbackCalibrationFilterProposal));
        }
        List<Map<String, Object>> savedCalibrationRecords = maps(store.snapshot().get("calibrations")).stream()
                .filter(item -> item.get("measurement_file") instanceof String).toList();
        List<Map<String, Object>> savedMeasurements = savedCalibrationRecords.stream()
                .filter(item -> item.get("response_curve_file") instanceof String).toList();
        if (!savedCalibrationRecords.isEmpty()) {
            measurement.getChildren().add(label(t("Сохранённые измерения", "Saved measurements"), "muted"));
            for (Map<String, Object> item : savedCalibrationRecords) {
                boolean impulse = "impulse_decay".equals(item.get("measurement_kind"));
                measurement.getChildren().add(new HBox(8, label(calibrationMeasurementLabel(item), "muted"),
                        actionButton(impulse ? t("Показать затухание", "Show decay") : t("Показать кривую", "Show curve"),
                                () -> loadCalibrationMeasurement(item))));
            }
        }
        if (savedMeasurements.size() >= 2) {
            VBox comparison = card(t("Сравнение до/после", "Before/after comparison"));
            comparison.getChildren().add(label(t("Сравнивайте один выход, источник, метод, канал и положение датчика. Каждая кривая нормирована отдельно к своей медиане: график показывает форму, а не абсолютную вибрацию или изменение общей громкости.",
                    "Compare the same output, input source, method, channel, and sensor position. Each curve is normalized to its own median: this shows response shape, not absolute vibration or overall loudness."), "warning"));
            ComboBox<Map<String, Object>> before = calibrationMeasurementSelector(savedMeasurements); before.setAccessibleText(t("Измерение до", "Before measurement"));
            ComboBox<Map<String, Object>> after = calibrationMeasurementSelector(savedMeasurements); after.setAccessibleText(t("Измерение после", "After measurement"));
            if (calibrationComparisonPreview != null) {
                selectCalibrationMeasurement(before, calibrationComparisonPreview.beforeId(), 0);
                selectCalibrationMeasurement(after, calibrationComparisonPreview.afterId(), 1);
            } else {
                before.getSelectionModel().select(0);
                after.getSelectionModel().select(1);
            }
            Button compare = actionButton(t("Сравнить кривые", "Compare curves"), () -> compareCalibrationMeasurements(before.getValue(), after.getValue()));
            FlowPane selectors = new FlowPane(10, 8,
                    new VBox(4, label(t("До", "Before"), "muted"), before),
                    new VBox(4, label(t("После", "After"), "muted"), after), compare);
            comparison.getChildren().add(selectors);
            boolean previewAvailable = calibrationComparisonPreview != null
                    && savedMeasurements.stream().anyMatch(item -> calibrationComparisonPreview.beforeId().equals(item.get("id")))
                    && savedMeasurements.stream().anyMatch(item -> calibrationComparisonPreview.afterId().equals(item.get("id")));
            if (previewAvailable) {
                CalibrationComparisonPreview preview = calibrationComparisonPreview;
                comparison.getChildren().add(label(preview.clipped()
                                ? t("В одной из записей есть клиппинг; сравнение может быть занижено.", "One recording is clipped; the comparison may understate peaks.")
                                : preview.verified()
                                ? t("Сравнение сохранённых кривых относительного отклика.", "Comparison of saved relative response curves.")
                                : t("Кривые загружены, но измерения не подтверждены.", "Curves loaded, but measurements are unverified."),
                        preview.clipped() ? "warning" : "muted"));
                comparison.getChildren().addAll(calibrationComparisonChart(preview.comparison(), preview.beforeLabel(), preview.afterLabel()),
                        calibrationComparisonTable(preview.comparison()));
            } else if (calibrationComparisonPreview != null) {
                calibrationComparisonPreview = null;
            }
            measurement.getChildren().add(comparison);
        }
        page.getChildren().addAll(test, observations, measurement);
        return page;
    }

    private void beginCalibrationCapture(AudioNode source, String method, Integer channelValue, String mountText,
                                        String startText, String endText, String stepsText, TextField captureOffset,
                                        CheckBox hardwareChecked, CheckBox sensorChecked, Button chooseRecording, Button analyzeRecording,
                                        Label recordingPath, Button recordButton, Button cancelButton, VBox testCard) {
        try {
            if (!hardwareChecked.isSelected()) throw new IllegalStateException(t("Сначала подтвердите проверку аппаратной цепи.",
                    "Confirm the hardware-path check first."));
            if (!sensorChecked.isSelected()) throw new IllegalStateException(t("Подтвердите подключение и закрепление датчика.",
                    "Confirm the sensor connection and mounting first."));
            if (source == null) throw new IllegalStateException(t("Выберите вход микрофона или акселерометра.",
                    "Choose a microphone or accelerometer input."));
            if (selectedSink == null) throw new IllegalStateException(t("Сначала выберите выход в блоке ручной калибровки.",
                    "Choose an output in the manual calibration section first."));
            if (string(mountText, "").isBlank()) throw new IllegalArgumentException(t("Укажите положение датчика.", "Enter the sensor position."));
            if (!snapshot.sources().stream().anyMatch(item -> item.id() == source.id())
                    || !snapshot.sinks().stream().anyMatch(item -> item.id() == selectedSink.id()))
                throw new IllegalStateException(t("Источник или выход уже исчез. Обновите состояние и выберите устройства снова.",
                        "The input or output is no longer available. Refresh and select the devices again."));
            int channel = channelValue == null ? 0 : channelValue;
            if (channel < 0 || channel >= source.channels()) throw new IllegalArgumentException(t("Выбранного канала нет во входном устройстве.",
                    "The selected channel is not available on this input."));
            double startHz = Double.parseDouble(startText.trim());
            double endHz = Double.parseDouble(endText.trim());
            int steps = Integer.parseInt(stepsText.trim());
            if (!Double.isFinite(startHz) || !Double.isFinite(endHz) || startHz < 20 || endHz > 1000 || endHz <= startHz
                    || steps < 2 || steps > 16)
                throw new IllegalArgumentException(t("Sweep: диапазон 20–1000 Hz, шагов 2–16.", "Sweep must be 20–1000 Hz with 2–16 steps."));
            if (calibrationCaptureTask != null && !calibrationCaptureTask.isDone())
                throw new IllegalStateException(t("Запись уже выполняется.", "A capture is already running."));

            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.setTitle(t("Подтвердите запись sweep", "Confirm sensor sweep"));
            confirmation.setHeaderText(t("Будет воспроизведён короткий тестовый sweep", "A short calibration sweep will play"));
            confirmation.setContentText(t("Вход: %s\nВыход: %s\nСигнал: PCM не выше 0.025, уровень pw-play 0.10; громкость устройства не меняется. Усилитель может дополнительно усиливать сигнал. Убедитесь, что схема и нагрузка проверены и физическая громкость минимальна. Исходный WAV останется временным, пока вы отдельно не сохраните измерение.",
                            "Input: %s\nOutput: %s\nSignal: PCM amplitude at most 0.025, pw-play level 0.10; device volume is not changed. The amplifier may add gain. Verify the wiring and load, and keep physical output at its minimum. The source WAV stays temporary until you separately save the measurement.")
                    .formatted(source.description(), selectedSink.description()));
            if (getScene() != null && getScene().getWindow() != null) confirmation.initOwner(getScene().getWindow());
            confirmation.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            if (confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return;

            AudioNode output = selectedSink;
            String sensorMount = mountText.trim();
            recordButton.setDisable(true); cancelButton.setDisable(false); testCard.setDisable(true);
            chooseRecording.setDisable(true); analyzeRecording.setDisable(true);
            hardwareChecked.setDisable(true);
            setNotice(t("Записываю датчик, затем воспроизведу sweep на выбранном выходе…",
                    "Recording the sensor, then playing the sweep to the selected output…"), false);
            CompletableFuture<MeasurementPreview> task = backend.captureSteppedSweep(source, output, startHz, endHz, steps)
                    .thenApplyAsync(capture -> {
                        try {
                            CalibrationMeasurementAnalyzer.Recording recording = CalibrationMeasurementAnalyzer.readPcm16Wav(capture.recording(), channel);
                            CalibrationMeasurementAnalyzer.Result result = CalibrationMeasurementAnalyzer.analyze(recording,
                                    startHz, endHz, steps, 200, capture.sweepOffsetMs());
                            return new MeasurementPreview(capture.recording(), output, method, source.name(), source.description(),
                                    channel, startHz, endHz, steps, 200,
                                    capture.sweepOffsetMs(), sensorMount, result, null, null, null, capture.temporaryDirectory());
                        } catch (Exception failure) {
                            deleteTemporaryCalibrationDirectory(capture.temporaryDirectory());
                            throw new java.util.concurrent.CompletionException(failure);
                        }
                    }, calibrationIo);
            calibrationCaptureTask = task;
            task.whenComplete((preview, error) -> Platform.runLater(() -> {
                if (calibrationCaptureTask == task) calibrationCaptureTask = null;
                if (closing) return;
                recordButton.setDisable(false); cancelButton.setDisable(true); testCard.setDisable(false);
                chooseRecording.setDisable(false); analyzeRecording.setDisable(false); hardwareChecked.setDisable(false);
                if (error != null) {
                    setNotice(t("Запись не завершена: ", "Capture did not complete: ") + rootMessage(error), true);
                    return;
                }
                calibrationPreview = preview; calibrationPreviewSaved = false; calibrationComparisonPreview = null;
                calibrationFilterSuggestions = List.of(); calibrationFilterPreviewShown = false;
                calibrationRecordingPath = preview.source();
                captureOffset.setText(Integer.toString(preview.offsetMs()));
                recordingPath.setText(preview.source().getFileName().toString());
                if (currentPage == Page.CALIBRATION) showPage(Page.CALIBRATION);
                setNotice(preview.result().clipped()
                                ? t("Кривая рассчитана, но в записи есть клиппинг.", "Curve calculated, but the recording is clipped.")
                                : t("Запись проанализирована; кривая не подтверждена, фильтры не применялись.",
                                        "Recording analyzed; the curve is unverified and no filters were applied."),
                        preview.result().clipped());
            }));
        } catch (Exception invalid) {
            setNotice(rootMessage(invalid), true);
        }
    }

    private boolean confirmCalibrationOutput(AudioNode sink, String signalDescription) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle(t("Подтвердите тестовый сигнал", "Confirm test signal"));
        confirmation.setHeaderText(t("На выбранный выход будет подан тестовый сигнал", "A test signal will be sent to the selected output"));
        confirmation.setContentText(t("Выход: %s\n%s\nГромкость устройства не меняется. Усилитель может дополнительно усиливать сигнал. Убедитесь, что нагрузка и питание проверены, а физический уровень минимален.",
                        "Output: %s\n%s\nDevice volume is not changed. The amplifier may add gain. Verify the load and power supply, and keep physical output at its minimum.")
                .formatted(sink.description(), signalDescription));
        if (getScene() != null && getScene().getWindow() != null) confirmation.initOwner(getScene().getWindow());
        confirmation.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        return confirmation.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private void applyCalibrationFilterProposal(MeasurementPreview preview, CheckBox hardwareChecked) {
        try {
            if (preview == null || !calibrationPreviewSaved || preview.calibrationId() == null || preview.output() == null)
                throw new IllegalStateException(t("Сначала сохраните WAV и кривую калибровки.", "Save the calibration WAV and curve first."));
            if (!hardwareChecked.isSelected())
                throw new IllegalStateException(t("Сначала подтвердите проверку аппаратной цепи.", "Confirm the hardware-path check first."));
            if (selectedSink == null || selectedSink.id() != preview.output().id()
                    || snapshot.sinks().stream().noneMatch(node -> node.id() == preview.output().id()))
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
            setNotice(t("Сохраняю срезы в YAML-профиль…", "Saving cuts to the YAML profile…"), false);
            store.updateAsync(config -> {
                Map<String, Object> calibration = maps(config.get("calibrations")).stream()
                        .filter(item -> preview.calibrationId().equals(item.get("id"))).findFirst()
                        .orElseThrow(() -> new IllegalStateException("The saved calibration record no longer exists"));
                String deviceId = string(calibration.get("device"), "");
                Map<String, Object> device = maps(config.get("devices")).stream()
                        .filter(item -> deviceId.equals(item.get("id"))).findFirst()
                        .orElseThrow(() -> new IllegalStateException("The calibration output is not mapped to a saved device"));
                AudioNode resolved = DeviceSelectorMatcher.resolve(snapshot.sinks(), deviceId, maps(config.get("devices")));
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
                setNotice(t("Срезы сохранены в YAML. Активный граф обновится по настройке применения маршрутов; тестовый сигнал не воспроизводился.",
                        "Cuts were saved in YAML. An active graph will refresh according to route-application settings; no test signal played."), false);
                showPage(Page.CALIBRATION);
            }));
        } catch (Exception invalid) { setNotice(rootMessage(invalid), true); }
    }

    private void rollbackCalibrationFilterProposal() {
        CalibrationFilterRollback rollback = calibrationFilterRollback;
        if (rollback == null) return;
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle(t("Подтвердите откат фильтров", "Confirm filter rollback"));
        confirmation.setHeaderText(t("Вернуть прежнюю цепочку выхода?", "Restore the previous output filter chain?"));
        confirmation.setContentText(t("Выход: %s\nБудет восстановлена прежняя ссылка на фильтры. Если граф маршрутов активен, Audio Matrix обновит его после записи YAML.",
                        "Output: %s\nThe previous filter reference will be restored. If the route graph is active, Audio Matrix will refresh it after saving YAML.")
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
            setNotice(t("Прежняя цепочка восстановлена в YAML. Активный граф обновится согласно настройкам маршрутов.",
                    "The previous filter chain was restored in YAML. An active graph will refresh according to route settings."), false);
            showPage(Page.CALIBRATION);
        }));
    }

    private static boolean calibrationFilterRefUsedElsewhere(Map<String, Object> config, String ref,
                                                               String ignoredDeviceId, String ignoredCalibrationId) {
        for (String collection : List.of("devices", "applications", "buses", "routes", "calibrations")) {
            for (Map<String, Object> item : maps(config.get(collection))) {
                String id = string(item.get("id"), "");
                if ("devices".equals(collection) && ignoredDeviceId.equals(id)) continue;
                if ("calibrations".equals(collection) && ignoredCalibrationId.equals(id)) continue;
                if (ref.equals(item.get("filters_ref"))) return true;
            }
        }
        return false;
    }

    private static Map<String, Object> copyCalibrationChain(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        List<Map<String, Object>> filters = new ArrayList<>();
        for (Map<String, Object> filter : maps(source.get("filters"))) filters.add(new LinkedHashMap<>(filter));
        copy.put("filters", filters);
        return copy;
    }

    private void restoreCalibrationRollbackState(Map<String, Object> calibration) {
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

    private void discardTemporaryCalibrationPreview() {
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

    private static void deleteTemporaryCalibrationDirectory(Path directory) {
        if (directory == null) return;
        Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path normalized = directory.toAbsolutePath().normalize();
        if (!temporaryRoot.equals(normalized.getParent()) || !normalized.getFileName().toString().startsWith("audio-matrix-calibration-")) return;
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }

    private void deleteCalibrationNote(String id) {
        updateConfig(config -> maps(config.get("calibrations")).removeIf(item -> id.equals(item.get("id"))));
    }

    private ComboBox<Map<String, Object>> calibrationMeasurementSelector(List<Map<String, Object>> measurements) {
        ComboBox<Map<String, Object>> selector = new ComboBox<>(FXCollections.observableArrayList(measurements));
        selector.setConverter(new StringConverter<>() {
            @Override public String toString(Map<String, Object> item) { return item == null ? "" : calibrationMeasurementLabel(item); }
            @Override public Map<String, Object> fromString(String value) { return null; }
        });
        selector.setAccessibleText(t("Сохранённая кривая", "Saved response curve"));
        selector.setPrefWidth(360);
        return selector;
    }

    private void selectCalibrationMeasurement(ComboBox<Map<String, Object>> selector, String id, int fallbackIndex) {
        for (Map<String, Object> item : selector.getItems()) if (id.equals(item.get("id"))) {
            selector.getSelectionModel().select(item);
            return;
        }
        if (fallbackIndex >= 0 && fallbackIndex < selector.getItems().size()) selector.getSelectionModel().select(fallbackIndex);
    }

    private String calibrationMeasurementLabel(Map<String, Object> item) {
        String method = switch (string(item.get("method"), "")) {
            case "microphone" -> t("Микрофон", "Microphone");
            case "accelerometer" -> t("Акселерометр", "Accelerometer");
            default -> string(item.get("method"), t("Датчик", "Sensor"));
        };
        String sensorSource = string(item.get("sensor_source_description"), "");
        String mount = string(item.get("sensor_mount"), t("положение не указано", "position unspecified"));
        String date = string(item.get("analyzed_at"), "");
        if (date.length() > 16) date = date.substring(0, 16).replace('T', ' ');
        String kind = "impulse_decay".equals(item.get("measurement_kind")) ? t(" · импульс / затухание", " · impulse / decay") : "";
        return method + kind + (sensorSource.isBlank() ? "" : " · " + sensorSource) + " · " + mount + (date.isBlank() ? "" : " · " + date);
    }

    private void compareCalibrationMeasurements(Map<String, Object> before, Map<String, Object> after) {
        try {
            if (before == null || after == null) throw new IllegalArgumentException(t("Выберите обе кривые.", "Choose both response curves."));
            if (Objects.equals(before.get("id"), after.get("id"))) throw new IllegalArgumentException(t("Выберите два разных измерения.", "Choose two different measurements."));
            for (String field : List.of("device", "method", "sensor_source", "sensor_channel_index", "sensor_mount")) {
                if (!Objects.equals(before.get(field), after.get(field)))
                    throw new IllegalArgumentException(t("Выбранные кривые должны совпадать по выходу, методу, каналу и положению датчика.",
                            "The curves must use the same output, sensor source, method, channel, and sensor position."));
            }
            SavedCalibrationFiles beforeFiles = readSavedCalibrationFiles(before);
            SavedCalibrationFiles afterFiles = readSavedCalibrationFiles(after);
            CalibrationMeasurementAnalyzer.Comparison curves = CalibrationMeasurementAnalyzer.compare(beforeFiles.result(), afterFiles.result());
            calibrationComparisonPreview = new CalibrationComparisonPreview(string(before.get("id"), ""), string(after.get("id"), ""),
                    t("До", "Before") + " · " + calibrationMeasurementLabel(before),
                    t("После", "After") + " · " + calibrationMeasurementLabel(after), curves,
                    beforeFiles.result().clipped() || afterFiles.result().clipped(),
                    Boolean.TRUE.equals(before.get("verified")) && Boolean.TRUE.equals(after.get("verified")));
            calibrationPreview = null;
            calibrationPreviewSaved = false;
            calibrationFilterSuggestions = List.of(); calibrationFilterPreviewShown = false;
            showPage(Page.CALIBRATION);
        } catch (Exception failure) {
            setNotice(t("Кривые не сравнены: ", "Could not compare curves: ") + rootMessage(failure), true);
        }
    }

    private LineChart<Number, Number> calibrationComparisonChart(CalibrationMeasurementAnalyzer.Comparison comparison,
                                                                  String beforeName, String afterName) {
        double lower = Math.log10(comparison.points().getFirst().frequencyHz());
        double upper = Math.log10(comparison.points().getLast().frequencyHz());
        NumberAxis frequency = new NumberAxis(lower, upper, Math.max(0.05, (upper - lower) / 4));
        frequency.setLabel(t("Частота, Hz · логарифмическая шкала", "Frequency, Hz · logarithmic scale"));
        frequency.setTickLabelFormatter(new StringConverter<>() {
            @Override public String toString(Number value) { return String.format(Locale.ROOT, "%.0f", Math.pow(10, value.doubleValue())); }
            @Override public Number fromString(String value) { return Math.log10(Double.parseDouble(value)); }
        });
        NumberAxis level = new NumberAxis();
        level.setLabel(t("Относительный уровень, dB", "Relative level, dB"));
        LineChart<Number, Number> chart = new LineChart<>(frequency, level);
        chart.setCreateSymbols(true); chart.setAnimated(false); chart.setLegendVisible(true); chart.setPrefHeight(280);
        chart.setAccessibleText(t("Наложение кривых до и после. Таблица точных значений и разницы находится ниже.",
                "Before and after response overlay. A table with exact values and delta follows."));
        XYChart.Series<Number, Number> before = new XYChart.Series<>(); before.setName(beforeName);
        XYChart.Series<Number, Number> after = new XYChart.Series<>(); after.setName(afterName);
        for (CalibrationMeasurementAnalyzer.ComparisonPoint point : comparison.points()) {
            double x = Math.log10(point.frequencyHz());
            XYChart.Data<Number, Number> beforePoint = new XYChart.Data<>(x, point.beforeRelativeDb());
            beforePoint.setNode(new Circle(4));
            Tooltip.install(beforePoint.getNode(), new Tooltip(String.format(Locale.ROOT, "%.1f Hz · %.2f dB", point.frequencyHz(), point.beforeRelativeDb())));
            before.getData().add(beforePoint);
            XYChart.Data<Number, Number> afterPoint = new XYChart.Data<>(x, point.afterRelativeDb());
            afterPoint.setNode(new Circle(4));
            Tooltip.install(afterPoint.getNode(), new Tooltip(String.format(Locale.ROOT, "%.1f Hz · %.2f dB", point.frequencyHz(), point.afterRelativeDb())));
            after.getData().add(afterPoint);
        }
        chart.getData().setAll(before, after);
        return chart;
    }

    private TableView<CalibrationMeasurementAnalyzer.ComparisonPoint> calibrationComparisonTable(
            CalibrationMeasurementAnalyzer.Comparison comparison) {
        TableView<CalibrationMeasurementAnalyzer.ComparisonPoint> table = new TableView<>(FXCollections.observableArrayList(comparison.points()));
        TableColumn<CalibrationMeasurementAnalyzer.ComparisonPoint, String> frequency = new TableColumn<>(t("Частота, Hz", "Frequency, Hz"));
        frequency.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(String.format(Locale.ROOT, "%.1f", cell.getValue().frequencyHz())));
        TableColumn<CalibrationMeasurementAnalyzer.ComparisonPoint, String> before = new TableColumn<>(t("До, dB", "Before, dB"));
        before.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(String.format(Locale.ROOT, "%.2f", cell.getValue().beforeRelativeDb())));
        TableColumn<CalibrationMeasurementAnalyzer.ComparisonPoint, String> after = new TableColumn<>(t("После, dB", "After, dB"));
        after.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(String.format(Locale.ROOT, "%.2f", cell.getValue().afterRelativeDb())));
        TableColumn<CalibrationMeasurementAnalyzer.ComparisonPoint, String> delta = new TableColumn<>(t("Разница, dB", "Delta, dB"));
        delta.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(String.format(Locale.ROOT, "%+.2f", cell.getValue().deltaDb())));
        table.getColumns().setAll(frequency, before, after, delta);
        table.setAccessibleText(t("Таблица сравнения относительных кривых", "Relative response comparison table"));
        table.setPrefHeight(Math.min(260, 34 + comparison.points().size() * 28));
        return table;
    }

    private LineChart<Number, Number> calibrationChart(CalibrationMeasurementAnalyzer.Result result, String method) {
        double lower = Math.log10(result.points().getFirst().frequencyHz());
        double upper = Math.log10(result.points().getLast().frequencyHz());
        NumberAxis frequency = new NumberAxis(lower, upper, Math.max(0.05, (upper - lower) / 4));
        frequency.setLabel(t("Частота, Hz · логарифмическая шкала", "Frequency, Hz · logarithmic scale"));
        frequency.setTickLabelFormatter(new javafx.util.StringConverter<>() {
            @Override public String toString(Number value) { return String.format(Locale.ROOT, "%.0f", Math.pow(10, value.doubleValue())); }
            @Override public Number fromString(String value) { return Math.log10(Double.parseDouble(value)); }
        });
        NumberAxis level = new NumberAxis();
        level.setLabel(t("Относительный уровень, dB", "Relative level, dB"));
        LineChart<Number, Number> chart = new LineChart<>(frequency, level);
        chart.setCreateSymbols(true); chart.setAnimated(false); chart.setLegendVisible(true); chart.setPrefHeight(280);
        chart.setAccessibleText(t("График относительного отклика. Таблица точных значений находится ниже.",
                "Relative response chart. A table with exact values follows."));
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName("microphone".equals(method) ? t("Микрофон", "Microphone") : t("Акселерометр", "Accelerometer"));
        for (CalibrationMeasurementAnalyzer.Point point : result.points()) {
            XYChart.Data<Number, Number> data = new XYChart.Data<>(Math.log10(point.frequencyHz()), point.relativeDb());
            data.setNode(new Circle(4));
            String clipping = point.clipped() ? t(" · клиппинг", " · clipped") : "";
            Tooltip.install(data.getNode(), new Tooltip(String.format(Locale.ROOT, "%.1f Hz · %.2f dB", point.frequencyHz(), point.relativeDb()) + clipping));
            series.getData().add(data);
        }
        chart.getData().add(series);
        return chart;
    }

    private LineChart<Number, Number> calibrationFilterChart(CalibrationMeasurementAnalyzer.Result result, String method,
                                                               List<CalibrationFilterAdvisor.Suggestion> suggestions) {
        LineChart<Number, Number> chart = calibrationChart(result, method);
        chart.setAccessibleText(t("Исходная относительная кривая и offline-прогноз после PEQ-срезов; прогноз не является измерением.",
                "Original relative response and offline prediction after PEQ cuts; prediction is not a measurement."));
        XYChart.Series<Number, Number> predicted = new XYChart.Series<>();
        predicted.setName(t("Прогноз после срезов", "Predicted after cuts"));
        for (CalibrationFilterAdvisor.PreviewPoint point : CalibrationFilterAdvisor.preview(result, suggestions)) {
            XYChart.Data<Number, Number> data = new XYChart.Data<>(Math.log10(point.frequencyHz()), point.afterRelativeDb());
            data.setNode(new Circle(4));
            Tooltip.install(data.getNode(), new Tooltip(String.format(Locale.ROOT, "%.1f Hz · %.2f dB", point.frequencyHz(), point.afterRelativeDb())));
            predicted.getData().add(data);
        }
        chart.getData().add(predicted);
        return chart;
    }

    private TableView<CalibrationMeasurementAnalyzer.Point> calibrationPointsTable(CalibrationMeasurementAnalyzer.Result result) {
        TableView<CalibrationMeasurementAnalyzer.Point> table = new TableView<>(FXCollections.observableArrayList(result.points()));
        TableColumn<CalibrationMeasurementAnalyzer.Point, Number> frequency = new TableColumn<>(t("Частота, Hz", "Frequency, Hz"));
        frequency.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().frequencyHz()));
        TableColumn<CalibrationMeasurementAnalyzer.Point, Number> amplitude = new TableColumn<>(t("Входной PCM", "Input PCM"));
        amplitude.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().amplitudePcm()));
        TableColumn<CalibrationMeasurementAnalyzer.Point, Number> level = new TableColumn<>(t("Относительно, dB", "Relative, dB"));
        level.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().relativeDb()));
        TableColumn<CalibrationMeasurementAnalyzer.Point, String> clipped = new TableColumn<>(t("Клиппинг", "Clipped"));
        clipped.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().clipped() ? t("Да", "Yes") : t("Нет", "No")));
        table.getColumns().setAll(frequency, amplitude, level, clipped);
        table.setAccessibleText(t("Таблица значений относительного отклика", "Relative response values table"));
        table.setPrefHeight(Math.min(260, 34 + result.points().size() * 28));
        table.setPlaceholder(label(t("Нет точек для отображения.", "No response points to show."), "muted"));
        return table;
    }

    private String impulseSummary(CalibrationImpulseAnalyzer.Result result) {
        String frequency = result.dominantFrequencyHz() == null ? t("нет надёжного пика", "no reliable peak")
                : String.format(Locale.ROOT, "%.1f Hz", result.dominantFrequencyHz());
        String q = result.qualityFactor() == null ? t("н/д", "n/a") : String.format(Locale.ROOT, "%.1f", result.qualityFactor());
        String t20 = result.decayT20Ms() == null ? t("не измерено", "not measured")
                : String.format(Locale.ROOT, "%.0f ms", result.decayT20Ms());
        String t60 = result.projectedT60Ms() == null ? t("не доступно", "unavailable")
                : String.format(Locale.ROOT, "%.0f ms", result.projectedT60Ms());
        return t("Пик на %d ms · доминирующая частота %s · Q по затуханию %s · T20 %s · T60 экстраполировано %s · шум до импульса %.6f PCM%s",
                        "Peak at %d ms · dominant frequency %s · decay-derived Q %s · T20 %s · extrapolated T60 %s · pre-impulse noise %.6f PCM%s")
                .formatted(result.detectedPeakOffsetMs(), frequency, q, t20, t60, result.noiseRmsPcm(),
                        result.clipped() ? t(" · КЛИППИНГ", " · CLIPPED") : "");
    }

    private LineChart<Number, Number> calibrationImpulseChart(CalibrationImpulseAnalyzer.Result result) {
        NumberAxis time = new NumberAxis(); time.setLabel(t("Время после пика, ms", "Time after response peak, ms"));
        NumberAxis level = new NumberAxis(-120, 0, 20); level.setLabel(t("Относительный уровень, dB", "Relative level, dB"));
        LineChart<Number, Number> chart = new LineChart<>(time, level);
        chart.setCreateSymbols(false); chart.setAnimated(false); chart.setLegendVisible(true); chart.setPrefHeight(260);
        chart.setAccessibleText(t("Огибающая и интегрированная кривая затухания импульсной записи.",
                "Envelope and integrated decay curve for the impulse recording."));
        XYChart.Series<Number, Number> envelope = new XYChart.Series<>();
        envelope.setName(t("Огибающая 10 ms", "10 ms envelope"));
        XYChart.Series<Number, Number> integrated = new XYChart.Series<>();
        integrated.setName(t("Интегрированное затухание · шумовой пол", "Integrated decay · noise floor"));
        for (CalibrationImpulseAnalyzer.DecayPoint point : result.points()) {
            envelope.getData().add(new XYChart.Data<>(point.timeMs(), point.envelopeRelativeDb()));
            integrated.getData().add(new XYChart.Data<>(point.timeMs(), point.integratedDecayDb()));
        }
        chart.getData().setAll(envelope, integrated);
        return chart;
    }

    private void saveImpulseCalibration(ImpulseDecayPreview preview) {
        if (preview == null || preview.source() == null || preview.output() == null || calibrationImpulsePreviewSaved) return;
        String id = "impulse-" + UUID.randomUUID().toString().replace("-", "");
        Path root = store.path().getParent().toAbsolutePath().normalize();
        Path recordingRelative = Path.of("measurements", "calibration", id + ".wav");
        Path decayRelative = Path.of("measurements", "calibration", id + "-decay.csv");
        Path recordingTarget = root.resolve(recordingRelative).normalize();
        Path decayTarget = root.resolve(decayRelative).normalize();
        ImpulseDecayPreview saved = new ImpulseDecayPreview(preview.source(), preview.output(), preview.method(), preview.channel(),
                preview.sensorMount(), preview.impulseOffsetMs(), preview.responseWindowMs(), preview.result(),
                recordingRelative.toString().replace('\\', '/'), decayRelative.toString().replace('\\', '/'), id);
        calibrationImpulsePreview = saved; calibrationImpulsePreviewSaved = true;
        setNotice(t("Сохраняю исходный WAV и CSV затухания…", "Saving the source WAV and decay CSV…"), false);
        Thread.ofVirtual().name("audio-matrix-impulse-save").start(() -> {
            try {
                atomicCopyMeasurement(root, preview.source(), recordingTarget);
                atomicWriteMeasurement(root, decayTarget, CalibrationImpulseAnalyzer.toCsv(preview.result()));
                store.updateAsync(config -> {
                    Map<String, Object> output = ensureDevice(config, preview.output());
                    CalibrationImpulseAnalyzer.Result result = preview.result();
                    maps(config.get("calibrations")).add(DefaultConfigMap.map(
                            "id", id, "device", output.get("id"), "channel", null, "method", preview.method(),
                            "measurement_kind", "impulse_decay", "sensor_source", null, "sensor_source_description", null,
                            "sensor_mount", preview.sensorMount(), "measurement_file", saved.measurementFile(),
                            "response_curve_file", null, "decay_curve_file", saved.decayCurveFile(),
                            "filters_ref", null, "verified", false, "sample_rate_hz", result.sampleRate(),
                            "sensor_channel_index", preview.channel(), "impulse_offset_ms", preview.impulseOffsetMs(),
                            "response_window_ms", preview.responseWindowMs(), "impulse_peak_offset_ms", result.detectedPeakOffsetMs(),
                            "peak_pcm", result.peakPcm(), "noise_rms_pcm", result.noiseRmsPcm(), "clipped", result.clipped(),
                            "dominant_frequency_hz", result.dominantFrequencyHz(), "quality_factor", result.qualityFactor(),
                            "decay_t20_ms", result.decayT20Ms(), "decay_t60_projected_ms", result.projectedT60Ms(),
                            "analyzed_at", Instant.now().toString(),
                            "notes", t("Импульсный отклик; относительная оценка затухания, физические единицы датчика не заданы.",
                                    "Impulse response; relative decay estimate, sensor physical units unspecified.")));
                }).join();
                Platform.runLater(() -> {
                    setNotice(t("WAV и кривая затухания сохранены; фильтры не применялись.",
                            "WAV and decay curve saved; no filters were applied."), false);
                    showPage(Page.CALIBRATION);
                });
            } catch (Exception failure) {
                try { Files.deleteIfExists(recordingTarget); Files.deleteIfExists(decayTarget); } catch (IOException ignored) {}
                Platform.runLater(() -> {
                    calibrationImpulsePreview = preview; calibrationImpulsePreviewSaved = false;
                    showPage(Page.CALIBRATION);
                    setNotice(t("Импульсное измерение не сохранено: ", "Impulse measurement was not saved: ") + rootMessage(failure), true);
                });
            }
        });
    }

    private void saveCalibrationMeasurement(MeasurementPreview preview) {
        if (preview == null || preview.source() == null || preview.output() == null || calibrationPreviewSaved) return;
        String id = "measurement-" + UUID.randomUUID().toString().replace("-", "");
        Path root = store.path().getParent().toAbsolutePath().normalize();
        Path recordingRelative = Path.of("measurements", "calibration", id + ".wav");
        Path curveRelative = Path.of("measurements", "calibration", id + "-response.csv");
        Path recordingTarget = root.resolve(recordingRelative).normalize();
        Path curveTarget = root.resolve(curveRelative).normalize();
        MeasurementPreview saved = new MeasurementPreview(preview.source(), preview.output(), preview.method(),
                preview.sensorSource(), preview.sensorSourceDescription(), preview.channel(),
                preview.startHz(), preview.endHz(), preview.steps(), preview.dwellMs(), preview.offsetMs(), preview.sensorMount(),
                preview.result(), recordingRelative.toString().replace('\\', '/'), curveRelative.toString().replace('\\', '/'), id,
                preview.temporaryDirectory());
        calibrationPreview = saved; calibrationPreviewSaved = true;
        setNotice(t("Сохраняю исходный WAV и относительную кривую…", "Saving the source WAV and relative curve…"), false);
        Thread.ofVirtual().name("audio-matrix-calibration-save").start(() -> {
            try {
                atomicCopyMeasurement(root, preview.source(), recordingTarget);
                atomicWriteMeasurement(root, curveTarget, CalibrationMeasurementAnalyzer.toCsv(preview.result()));
                store.updateAsync(config -> {
                    Map<String, Object> output = ensureDevice(config, preview.output());
                    maps(config.get("calibrations")).add(DefaultConfigMap.map("id", id, "device", output.get("id"),
                            "channel", null, "method", preview.method(), "sensor_source", preview.sensorSource(),
                            "sensor_source_description", preview.sensorSourceDescription(), "sensor_mount", preview.sensorMount(),
                            "measurement_file", saved.measurementFile(), "response_curve_file", saved.responseCurveFile(),
                            "filters_ref", null, "verified", false, "sample_rate_hz", preview.result().sampleRate(),
                            "sensor_channel_index", preview.channel(), "sweep_start_hz", preview.startHz(),
                            "sweep_end_hz", preview.endHz(), "sweep_steps", preview.steps(), "sweep_dwell_ms", preview.dwellMs(),
                            "recording_offset_ms", preview.offsetMs(), "peak_pcm", preview.result().peakPcm(),
                            "clipped", preview.result().clipped(), "analyzed_at", Instant.now().toString(),
                            "notes", t("Импортированный WAV; относительный отклик, физические единицы датчика не заданы.",
                                    "Imported WAV; relative response only, sensor physical units unspecified.")));
                }).join();
                deleteTemporaryCalibrationDirectory(preview.temporaryDirectory());
                Platform.runLater(() -> {
                    if (preview.temporaryDirectory() != null && preview.source().equals(calibrationRecordingPath)) calibrationRecordingPath = null;
                    setNotice(t("Запись и кривая сохранены; фильтры не применялись.", "Recording and curve saved; no filters were applied."), false);
                    showPage(Page.CALIBRATION);
                });
            } catch (Exception failure) {
                try { Files.deleteIfExists(recordingTarget); Files.deleteIfExists(curveTarget); } catch (IOException ignored) {}
                Platform.runLater(() -> {
                    calibrationPreview = preview; calibrationPreviewSaved = false;
                    showPage(Page.CALIBRATION);
                    setNotice(t("Измерение не сохранено: ", "Measurement was not saved: ") + rootMessage(failure), true);
                });
            }
        });
    }

    private void loadCalibrationMeasurement(Map<String, Object> calibration) {
        try {
            if ("impulse_decay".equals(calibration.get("measurement_kind"))) {
                Path root = store.path().getParent().toAbsolutePath().normalize();
                Path recording = savedCalibrationAsset(root, string(calibration.get("measurement_file"), ""));
                Path decayCsv = savedCalibrationAsset(root, string(calibration.get("decay_curve_file"), ""));
                if (Files.size(decayCsv) > 128 * 1024 || !Files.readAllLines(decayCsv).getFirst()
                        .equals("time_ms,envelope_relative_db,integrated_decay_db"))
                    throw new IOException("Saved impulse decay curve has an unsupported format");
                int channel = (int) number(calibration.get("sensor_channel_index"), 0);
                int offsetMs = (int) number(calibration.get("impulse_offset_ms"), 500);
                int windowMs = (int) number(calibration.get("response_window_ms"), 2_000);
                CalibrationMeasurementAnalyzer.Recording input = CalibrationMeasurementAnalyzer.readPcm16Wav(recording, channel);
                CalibrationImpulseAnalyzer.Result result = CalibrationImpulseAnalyzer.analyze(input, offsetMs, windowMs);
                calibrationImpulsePreview = new ImpulseDecayPreview(recording, null,
                        string(calibration.get("method"), "accelerometer"), channel,
                        string(calibration.get("sensor_mount"), ""), offsetMs, windowMs, result,
                        string(calibration.get("measurement_file"), ""), string(calibration.get("decay_curve_file"), ""),
                        string(calibration.get("id"), ""));
                calibrationImpulsePreviewSaved = true;
                calibrationPreview = null; calibrationPreviewSaved = false; calibrationComparisonPreview = null;
                calibrationFilterSuggestions = List.of(); calibrationFilterPreviewShown = false;
                calibrationRecordingPath = recording;
                showPage(Page.CALIBRATION);
                setNotice(result.clipped() ? t("Затухание загружено; в исходной записи есть клиппинг.",
                                "Decay analysis loaded; the source recording contains clipping.")
                        : t("Затухание пересчитано из сохранённого WAV.", "Decay analysis recalculated from the saved WAV."), result.clipped());
                return;
            }
            SavedCalibrationFiles saved = readSavedCalibrationFiles(calibration);
            CalibrationMeasurementAnalyzer.Result result = saved.result();
            Map<String, Object> config = store.snapshot();
            String savedDeviceId = string(calibration.get("device"), "");
            AudioNode savedOutput = maps(config.get("devices")).stream()
                    .filter(device -> savedDeviceId.equals(device.get("id"))).findFirst()
                    .map(device -> DeviceSelectorMatcher.resolve(snapshot.sinks(), savedDeviceId, maps(config.get("devices"))))
                    .orElse(null);
            calibrationPreview = new MeasurementPreview(saved.recording(), savedOutput, string(calibration.get("method"), "accelerometer"),
                    string(calibration.get("sensor_source"), null), string(calibration.get("sensor_source_description"), null),
                    (int) number(calibration.get("sensor_channel_index"), 0),
                    number(calibration.get("sweep_start_hz"), result.points().getFirst().frequencyHz()),
                    number(calibration.get("sweep_end_hz"), result.points().getLast().frequencyHz()),
                    (int) number(calibration.get("sweep_steps"), result.points().size()),
                    (int) number(calibration.get("sweep_dwell_ms"), 200),
                    (int) number(calibration.get("recording_offset_ms"), 0), string(calibration.get("sensor_mount"), ""),
                    result, string(calibration.get("measurement_file"), ""), string(calibration.get("response_curve_file"), ""),
                    string(calibration.get("id"), ""), null);
            calibrationPreviewSaved = true;
            calibrationImpulsePreview = null; calibrationImpulsePreviewSaved = false;
            calibrationComparisonPreview = null;
            calibrationFilterSuggestions = List.of(); calibrationFilterPreviewShown = false;
            restoreCalibrationRollbackState(calibration);
            showPage(Page.CALIBRATION);
        } catch (Exception failure) { setNotice(t("Измерение не загружено: ", "Could not load calibration measurement: ") + rootMessage(failure), true); }
    }

    private static Path savedCalibrationAsset(Path root, String raw) throws IOException {
        Path relative;
        try { relative = Path.of(raw).normalize(); }
        catch (RuntimeException invalid) { throw new IOException("Saved calibration file path is invalid", invalid); }
        if (raw.isBlank() || relative.isAbsolute() || relative.startsWith("..") || !relative.toString().equals(raw))
            throw new IOException("Calibration files must stay inside the Audio Matrix config directory");
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw new IOException("Saved calibration file is outside the config directory");
        checkMeasurementParents(root, target);
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target))
            throw new IOException("Saved calibration file is missing or is a symbolic link");
        return target;
    }

    private SavedCalibrationFiles readSavedCalibrationFiles(Map<String, Object> calibration) throws IOException {
        Path root = store.path().getParent().toAbsolutePath().normalize();
        String rawCurve = string(calibration.get("response_curve_file"), "");
        String rawRecording = string(calibration.get("measurement_file"), "");
        Path relativeCurve = Path.of(rawCurve).normalize(); Path relativeRecording = Path.of(rawRecording).normalize();
        if (rawCurve.isBlank() || rawRecording.isBlank() || relativeCurve.isAbsolute() || relativeCurve.startsWith("..")
                || relativeRecording.isAbsolute() || relativeRecording.startsWith(".."))
            throw new IOException("Calibration files must be stored inside the Audio Matrix config directory");
        Path curve = root.resolve(relativeCurve).normalize(); Path recording = root.resolve(relativeRecording).normalize();
        if (!curve.startsWith(root) || !recording.startsWith(root))
            throw new IOException("Saved recording or response curve is outside the config directory");
        checkMeasurementParents(root, curve); checkMeasurementParents(root, recording);
        if (Files.isSymbolicLink(curve) || Files.isSymbolicLink(recording)
                || !Files.isRegularFile(curve) || !Files.isRegularFile(recording))
            throw new IOException("Saved recording or response curve is missing");
        return new SavedCalibrationFiles(recording, curve, readCalibrationCurve(curve, calibration));
    }

    private CalibrationMeasurementAnalyzer.Result readCalibrationCurve(Path path, Map<String, Object> calibration) throws IOException {
        if (Files.size(path) > 64 * 1024 || Files.isSymbolicLink(path)) throw new IOException("Response curve file exceeds the size limit or is a symbolic link");
        List<String> rows = Files.readAllLines(path);
        if (rows.size() < 3 || rows.size() > 17 || !rows.getFirst().equals("frequency_hz,amplitude_pcm,relative_db,clipped"))
            throw new IOException("Response curve CSV has an unsupported format");
        List<CalibrationMeasurementAnalyzer.Point> points = new ArrayList<>();
        try {
            for (String row : rows.subList(1, rows.size())) {
                String[] columns = row.split(",", -1);
                if (columns.length != 4) throw new IllegalArgumentException("invalid row");
                double frequency = Double.parseDouble(columns[0]); double amplitude = Double.parseDouble(columns[1]);
                double relativeDb = Double.parseDouble(columns[2]);
                if (!Double.isFinite(frequency) || frequency <= 0 || !Double.isFinite(amplitude) || amplitude < 0 || !Double.isFinite(relativeDb))
                    throw new IllegalArgumentException("invalid numeric value");
                points.add(new CalibrationMeasurementAnalyzer.Point(frequency, amplitude, relativeDb, Boolean.parseBoolean(columns[3])));
            }
            for (int i = 1; i < points.size(); i++) if (points.get(i).frequencyHz() <= points.get(i - 1).frequencyHz())
                throw new IllegalArgumentException("frequencies are not increasing");
        } catch (RuntimeException invalid) { throw new IOException("Response curve CSV contains invalid values", invalid); }
        return new CalibrationMeasurementAnalyzer.Result((int) number(calibration.get("sample_rate_hz"), 48_000),
                (int) number(calibration.get("sensor_channel_index"), 0), number(calibration.get("peak_pcm"), 0),
                Boolean.TRUE.equals(calibration.get("clipped")), 0, points);
    }

    private static void atomicCopyMeasurement(Path root, Path source, Path target) throws IOException {
        Path parent = target.getParent(); createMeasurementParents(root, target);
        Path temporary = Files.createTempFile(parent, ".measurement-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Measurement directory does not support atomic file replacement", unsupported);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void atomicWriteMeasurement(Path root, Path target, String content) throws IOException {
        Path parent = target.getParent(); createMeasurementParents(root, target);
        Path temporary = Files.createTempFile(parent, ".measurement-", ".tmp");
        try {
            Files.writeString(temporary, content);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Measurement directory does not support atomic file replacement", unsupported);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void createMeasurementParents(Path root, Path target) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!target.toAbsolutePath().normalize().startsWith(normalizedRoot)) throw new IOException("Measurement path escapes the config directory");
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(target.getParent().toAbsolutePath().normalize())) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Measurement path traverses a symbolic link");
            if (!Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current);
            else if (!Files.isDirectory(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("Measurement parent is not a directory");
        }
    }

    private static void checkMeasurementParents(Path root, Path target) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!target.toAbsolutePath().normalize().startsWith(normalizedRoot)) throw new IOException("Measurement path escapes the config directory");
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(target.getParent().toAbsolutePath().normalize())) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Measurement path traverses a symbolic link");
            if (!Files.isDirectory(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("Measurement parent directory is missing");
        }
    }

    private String meterText() {
        return meterText(backend.outputLevel());
    }

    private String inputMeterText() {
        return inputMeterEnabled ? meterText(backend.inputLevel()) : t("Входной meter выключен", "Input meter is off");
    }

    private String meterText(io.github.ilyaandreev05.audiomatrix.audio.PipeWireLevelMonitor.Reading reading) {
        if (!reading.available()) return reading.message();
        String value = t("Пик ", "Peak ") + String.format(Locale.ROOT, "%.1f dBFS", reading.peakDbfs())
                + " · RMS " + String.format(Locale.ROOT, "%.1f dBFS", reading.rmsDbfs());
        return reading.clipped() ? value + " · " + t("цифровой клиппинг", "digital clipping") : value;
    }

    private void refreshMeterLabel() {
        if (outputMeterValue != null) outputMeterValue.setText(meterText());
        if (inputMeterValue != null) inputMeterValue.setText(inputMeterText());
    }

    private VBox profilesPage() {
        VBox page = page(t("Профиль сохраняет режим, выход, активные шины и маршруты.", "A profile stores mode, output, active buses, and routes."));
        List<Map<String, Object>> profiles = maps(store.snapshot().get("profiles"));
        VBox list = card(t("Профили", "Profiles"));
        String active = string(store.snapshot().get("active_profile"), "desk");
        List<Map<String, Object>> visibleProfiles = profiles.stream().filter(profile -> matchesSearch(string(profile.get("name"), "") + " " + string(profile.get("id"), "") + " " + string(profile.get("mode"), "") + " " + outputReferenceName(store.snapshot(),string(profile.get("default_output"),"system_default")), search.getText())).toList();
        if (visibleProfiles.isEmpty()) list.getChildren().add(label(t("Профили не найдены.", "No profiles found."), "muted"));
        for (Map<String, Object> profile : visibleProfiles) {
            String id = string(profile.get("id"), "");
            HBox row = new HBox(12); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("node-card");
            VBox info = new VBox(4, label(string(profile.get("name"), id), "card-title"), label(id + " · " + string(profile.get("mode"), "hybrid") + " · " + outputReferenceName(store.snapshot(),string(profile.get("default_output"),"system_default")), "muted"));
            Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
            row.getChildren().addAll(info, spacer);
            if (id.equals(active)) row.getChildren().add(label(t("Активен", "Active"), "accent"));
            else row.getChildren().add(actionButton(t("Активировать", "Activate"), () -> activateProfile(id)));
            row.getChildren().add(actionButton(t("Переименовать", "Rename"), () -> renameProfile(id)));
            if (profiles.size() > 1) row.getChildren().add(actionButton(t("Удалить", "Delete"), () -> deleteProfile(id)));
            list.getChildren().add(row);
        }
        list.getChildren().add(actionButton(t("Создать копию активного профиля", "Copy active profile"), this::copyProfile));
        page.getChildren().add(list);
        VBox presets = card(t("Режимы выхода", "Output modes"));
        presets.getChildren().addAll(label(t("Haptic · Subwoofer · Speaker · Hybrid · Multi-output · Off", "Haptic · Subwoofer · Speaker · Hybrid · Multi-output · Off"), "card-title"),
                label(t("Haptic пропускает шины с ролью effects на выходы haptic_output; Subwoofer — шину subwoofer на выход subwoofer; Speaker — маршруты на speaker. Назначьте роли в редакторах шин и устройств. Без роли специальный маршрут будет пропущен.",
                        "Haptic routes effects buses to haptic_output devices; Subwoofer routes the subwoofer bus to a subwoofer output; Speaker keeps routes to speaker outputs. Set roles in bus and device editors. Specialized routes without matching roles are skipped."), "muted"));
        ComboBox<String> mode = new ComboBox<>(); mode.getItems().addAll("haptic", "subwoofer", "speaker", "hybrid", "multi-output", "off");
        mode.setValue(activeProfile().getOrDefault("mode", "hybrid").toString()); mode.setAccessibleText(t("Режим профиля", "Profile mode"));
        mode.setOnAction(e -> updateRouteConfig(t("Изменён режим выхода профиля", "Profile output mode changed"),
                config -> activeProfile(config).put("mode", mode.getValue()))); presets.getChildren().add(mode);
        page.getChildren().add(presets);
        VBox importExport = card(t("Резервная копия и перенос", "Backup and transfer"));
        importExport.getChildren().addAll(label(t("ZIP включает YAML-профили, связи, маршруты и все указанные файлы измерений; перед импортом проверяются manifest и контрольные суммы.", "ZIP includes YAML profiles, links, routes, and referenced measurement files; the manifest and checksums are validated before import."), "muted"),
                actionButton(t("Экспортировать ZIP…", "Export ZIP…"), this::exportArchive),
                actionButton(t("Проверить и импортировать ZIP…", "Preview and import ZIP…"), this::importArchive),
                label(t("После подтверждения импорт заменяет YAML. Совпавшие правила могут сразу изменить громкость/mute устройств и потоков. Активный граф маршрутов обновится; если импорт включает apply_on_start, маршруты могут примениться сразу. Пропавшие или неоднозначные selectors можно сопоставить с текущим устройством либо оставить offline.", "After confirmation, import replaces YAML. Matching rules may immediately change device/stream volume or mute. An active route graph will refresh; imported apply_on_start may apply routes immediately. Missing or ambiguous selectors can be mapped to a current device or kept offline."), "warning"));
        page.getChildren().add(importExport);
        return page;
    }

    private VBox settingsPage() {
        VBox page = page(t("Язык, тема, проверка YAML и состояние источников.", "Language, theme, YAML validation, and source status."));
        VBox preferences = card(t("Внешний вид", "Appearance"));
        ComboBox<String> language = new ComboBox<>(); language.getItems().addAll("ru", "en"); language.setValue(setting(store.snapshot(), "ui", "language", "ru")); language.setAccessibleText(t("Язык интерфейса", "Interface language"));
        ComboBox<String> theme = new ComboBox<>(); theme.getItems().addAll("system", "light", "dark"); theme.setValue(setting(store.snapshot(), "ui", "theme", "system")); theme.setAccessibleText(t("Тема интерфейса", "Interface theme"));
        language.setOnAction(e -> updateUiSetting("language", language.getValue()));
        theme.setOnAction(e -> { updateUiSetting("theme", theme.getValue()); themes.apply(getScene(), theme.getValue()); });
        preferences.getChildren().addAll(label(t("Язык", "Language"), ""), language, label(t("Тема: системная, светлая или тёмная", "Theme: system, light, or dark"), ""), theme);
        page.getChildren().add(preferences);
        VBox pipewire = card(t("Диагностика PipeWire", "PipeWire diagnostics"));
        pipewire.getChildren().addAll(label(backend.backendMessage(), snapshot.connected() ? "accent" : "danger"),
                label("wpctl: " + (executable("wpctl") ? t("доступен", "available") : t("не найден", "not found")), ""),
                label("pw-dump: " + (executable("pw-dump") ? t("доступен", "available") : t("не найден", "not found")), ""),
                label("pw-link: " + (executable("pw-link") ? t("доступен", "available") : t("не найден", "not found")), ""),
                label("pactl: " + (executable("pactl") ? t("доступен", "available") : t("не найден; перенос потоков отключён", "not found; stream transfer disabled")), ""),
                label(t("В системе обнаружено ", "System discovery found ") + snapshot.sinks().size() + t(" выходов, ", " outputs, ") + snapshot.sources().size() + t(" входов и ", " inputs and ") + snapshot.streams().size() + t(" активных потоков.", " active streams."), ""));
        Button refresh = actionButton(t("Проверить заново", "Check again"), this::refreshBackend); pipewire.getChildren().add(refresh);
        page.getChildren().add(pipewire);
        VBox routeLifecycle = card(t("Маршруты при запуске", "Route startup"));
        CheckBox applyOnStart = new CheckBox(t("Применять сохранённые маршруты при запуске Audio Matrix", "Apply saved routes when Audio Matrix starts"));
        applyOnStart.setSelected(Boolean.TRUE.equals(map(store.snapshot().get("engine")).get("apply_on_start")));
        applyOnStart.setOnAction(event -> updateConfig(config -> map(config.get("engine")).put("apply_on_start", applyOnStart.isSelected())));
        routeLifecycle.getChildren().addAll(applyOnStart,
                label(t("Когда выключено, запуск приложения оставляет PipeWire-граф без изменений. Уже применённый граф повторно собирается после переподключения PipeWire, пока Audio Matrix работает.", "When disabled, launching the app leaves the PipeWire graph unchanged. An applied graph is rebuilt after PipeWire reconnects while Audio Matrix is running."), "muted"));
        page.getChildren().add(routeLifecycle);
        VBox gnome = card(t("GNOME Quick Settings", "GNOME Quick Settings"));
        gnome.getChildren().addAll(label(t("Установите скрипт scripts/install-gnome-quick-settings.sh, перезапустите сеанс GNOME и включите audio-matrix-quick-settings@ilyaandreev05 в GNOME Extensions.", "Run scripts/install-gnome-quick-settings.sh, sign out and back in, then enable audio-matrix-quick-settings@ilyaandreev05 in GNOME Extensions."), ""),
                label(t("Мост использует D-Bus сеанса GNOME; команды выполняет Audio Matrix. Выбор выхода и mute меняют PipeWire, режим профиля и карта каналов маршрута сохраняются в YAML, а уже применённый граф обновляется.", "The GNOME session D-Bus bridge sends commands to Audio Matrix. Output selection and mute change PipeWire; profile mode and route channel maps are saved to YAML, and an applied graph refreshes."), "muted")); page.getChildren().add(gnome);
        return page;
    }

    private void handleGnomeCommand(GnomeControlBridge.Command command) {
        switch (command.name()) {
            case "open" -> Platform.runLater(() -> {
                if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage stage) {
                    stage.setIconified(false); stage.toFront(); stage.requestFocus();
                }
            });
            case "select_output" -> snapshot.sinks().stream().filter(node -> node.name().equals(command.value())).findFirst().ifPresent(node ->
                    backend.setDefaultOutput(node.id()).whenComplete((ignored, error) -> {
                        if (error == null) { saveDefaultOutput(node); refreshBackend(); }
                        else Platform.runLater(() -> setNotice(t("Не удалось выбрать выход: ", "Could not select output: ") + rootMessage(error), true));
                    }));
            case "toggle_mute" -> {
                AudioNode node = activeOutputNode();
                if (node != null) backend.setMute(node.id(), !node.muted()).whenComplete((ignored, error) -> {
                    if (error == null) { saveDeviceMute(node, !node.muted()); refreshBackend(); }
                    else Platform.runLater(() -> setNotice(t("Не удалось изменить mute: ", "Could not change mute: ") + rootMessage(error), true));
                });
            }
            case "set_mode" -> {
                if (Set.of("haptic", "subwoofer", "speaker", "hybrid", "multi-output", "off").contains(command.value()))
                    updateRouteConfig(t("Изменён режим выхода из GNOME", "GNOME output mode changed"),
                            config -> activeProfile(config).put("mode", command.value()));
            }
            case "set_channel_map" -> setGnomeChannelMap(command.value());
            default -> { }
        }
    }

    private void setGnomeChannelMap(String value) {
        String[] fields = value.split("\\t", 2);
        if (fields.length != 2 || !Set.of("stereo", "mono", "mono_sum", "left", "right", "swap_lr",
                "center", "lfe", "rear_left", "rear_right", "rear_sum", "side_left", "side_right", "side_sum", "surround_sum", "all_sum").contains(fields[1])) return;
        String routeId = fields[0];
        Map<String, Object> current = store.snapshot();
        Map<String, Object> profile = activeProfile(current);
        if (!stringList(profile.get("active_routes")).contains(routeId)) return;
        Map<String, Object> route = maps(current.get("routes")).stream()
                .filter(candidate -> routeId.equals(candidate.get("id")) && !Boolean.FALSE.equals(candidate.get("enabled")))
                .findFirst().orElse(null);
        AudioNode selected = activeOutputNode();
        AudioNode target = route == null ? null : routeOutputNode(current, route);
        if (selected == null || target == null || !selected.name().equals(target.name())) return;
        updateRouteConfig(t("Изменена карта каналов маршрута ", "Changed channel map for route ") + routeId, config -> maps(config.get("routes")).stream()
                .filter(candidate -> routeId.equals(candidate.get("id")) && !Boolean.FALSE.equals(candidate.get("enabled")))
                .findFirst().ifPresent(candidate -> candidate.put("channel_map", fields[1])));
    }

    private AudioNode routeOutputNode(Map<String, Object> config, Map<String, Object> route) {
        String target = string(route.get("to"), "");
        if (maps(config.get("buses")).stream().anyMatch(bus -> target.equals(bus.get("id")))) return null;
        if ("system_default".equals(target))
            return snapshot.sinks().stream().filter(node -> node.id() == snapshot.defaultSinkId()).findFirst().orElse(null);
        return DeviceSelectorMatcher.resolve(snapshot.sinks(), target, maps(config.get("devices")));
    }

    private AudioNode activeOutputNode() {
        AudioNode configured = configuredSink();
        if (configured != null) return configured;
        return snapshot.sinks().stream().filter(node -> node.id() == snapshot.defaultSinkId()).findFirst().orElse(null);
    }

    private void updateMeterTarget() {
        String previousName = selectedSink == null ? "" : selectedSink.name();
        selectedSink = snapshot.sinks().stream().filter(node -> node.name().equals(previousName)).findFirst().orElse(null);
        if (selectedSink == null) selectedSink = configuredSink();
        if (selectedSink == null) selectedSink = snapshot.sinks().stream().filter(node -> node.id() == snapshot.defaultSinkId()).findFirst().orElse(null);
        backend.monitorOutput(currentPage == Page.DSP ? selectedSink : null);
        if (selectedInputSource != null) {
            String inputName = selectedInputSource.name();
            selectedInputSource = snapshot.sources().stream().filter(node -> node.name().equals(inputName)).findFirst().orElse(null);
            if (inputMeterEnabled && selectedInputSource == null) inputMeterEnabled = false;
            backend.monitorInput(currentPage == Page.DSP && inputMeterEnabled ? selectedInputSource : null);
        }
    }

    private void publishGnomeState() {
        gnomeBridge.publishState(snapshot, store.snapshot());
    }

    private void applyRoutes() {
        Map<String, Object> config = store.snapshot();
        autoApplySuppressedUntilRestart = false;
        setNotice(t("Обновляю состояние PipeWire и применяю граф…", "Refreshing PipeWire and applying the graph…"), false);
        backend.refresh().thenCompose(current -> {
            snapshot = current;
            updateMeterTarget();
            return routeManager.apply(runtimeDspConfig(config, current), current);
        }).whenComplete((report, error) -> Platform.runLater(() -> {
            if (error != null) {
                setNotice(t("Граф PipeWire не применён: ", "PipeWire graph was not applied: ") + rootMessage(error), true);
                showPage(currentPage);
                return;
            }
            routesRequested = routeManager.active();
            lastRouteInputSignature = routeInputSignature(config, snapshot);
            lastAppliedConnectionGeneration = backend.connectionGeneration();
            autoApplySuppressedUntilRestart = false;
            String summary = t("Создано шин: ", "Buses: ") + report.buses()
                    + t(", маршрутов: ", ", routes: ") + report.routes()
                    + t(", потоков: ", ", streams: ") + report.streams();
            if (!report.warnings().isEmpty()) summary += " · " + String.join("; ", report.warnings());
            setNotice(summary, false);
            showPage(currentPage);
        }));
    }

    private void clearRoutes() {
        routesRequested = false;
        dspReferenceMode = false;
        autoApplySuppressedUntilRestart = true;
        routeManager.clear().whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) setNotice(t("Не удалось сбросить граф: ", "Could not clear the graph: ") + rootMessage(error), true);
            else setNotice(t("Маршруты сброшены; исходные связи потоков восстановлены.", "Routes cleared; original stream links restored."), false);
            showPage(currentPage);
        }));
    }

    private void maybeApplyConfiguredRoutes(Map<String, Object> config, PipeWireBackend.Snapshot current) {
        boolean autoApply = Boolean.TRUE.equals(map(config.get("engine")).get("apply_on_start")) && !autoApplySuppressedUntilRestart;
        String signature = routeInputSignature(config, current);
        if (!current.connected()) {
            if (routeManager.active()) {
                lastRouteInputSignature = signature;
                routeManager.apply(runtimeDspConfig(config, current), current).whenComplete((ignored, error) -> Platform.runLater(() -> {
                    routesRequested = true;
                    showPage(currentPage);
                }));
            }
            return;
        }
        if (!routesRequested && !autoApply) return;
        long connectionGeneration = backend.connectionGeneration();
        boolean reconnected = connectionGeneration != lastAppliedConnectionGeneration;
        if (!reconnected && signature.equals(lastRouteInputSignature)) return;
        lastRouteInputSignature = signature;
        CompletableFuture<PipeWireRouteManager.ApplyReport> apply = reconnected
                ? routeManager.reapplyAfterReconnect(runtimeDspConfig(config, current), current)
                : routeManager.apply(runtimeDspConfig(config, current), current);
        apply.whenComplete((report, error) -> Platform.runLater(() -> {
            if (error != null) {
                lastRouteInputSignature = "";
                routesRequested = routeManager.active() || !current.connected();
                setNotice(t("Не удалось обновить применённый граф: ", "Could not update the applied graph: ") + rootMessage(error), true);
            } else {
                routesRequested = routeManager.active();
                lastAppliedConnectionGeneration = connectionGeneration;
                if (currentPage == Page.ROUTES) setNotice(routeManager.status(), false);
            }
            showPage(currentPage);
        }));
    }

    private void setDspReferenceMode(boolean referenceMode) {
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
        backend.refresh().thenCompose(current -> {
            snapshot = current;
            updateMeterTarget();
            return routeManager.apply(runtimeDspConfig(config, current), current);
        }).whenComplete((report, error) -> Platform.runLater(() -> {
            dspReferenceUpdateInProgress = false;
            if (error != null) {
                dspReferenceMode = previous;
                setNotice(t("A/B не применён: ", "A/B was not applied: ") + rootMessage(error), true);
            } else {
                routesRequested = routeManager.active();
                lastRouteInputSignature = routeInputSignature(config, snapshot);
                lastAppliedConnectionGeneration = backend.connectionGeneration();
                setNotice(referenceMode
                        ? t("B включён: EQ обойдён, уровень снижен по расчётной верхней оценке.", "B is on: EQ bypassed and level reduced by the conservative gain estimate.")
                        : t("A включён: обработанная EQ-цепочка восстановлена.", "A is on: the processed EQ chain is restored."), false);
            }
            showPage(currentPage);
        }));
    }

    private Map<String, Object> runtimeDspConfig(Map<String, Object> config, PipeWireBackend.Snapshot current) {
        if (!dspReferenceMode) return config;
        double matchDb;
        try { matchDb = Math.max(0, routeManager.maximumEqBoostDb(config, current, selectedSink)); }
        catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        return DspComparisonConfig.reference(config, matchDb);
    }

    private String routeInputSignature(Map<String, Object> config, PipeWireBackend.Snapshot current) {
        List<String> sinks = current.sinks().stream().map(node -> node.id() + ":" + node.name()).sorted().toList();
        List<String> streams = current.streams().stream().map(node -> node.id() + ":" + node.name() + ":" + node.applicationId() + ":" + node.processName()).sorted().toList();
        int hash = Objects.hash(config.get("active_profile"), config.get("profiles"), config.get("buses"),
                config.get("routes"), config.get("applications"), config.get("devices"), config.get("dsp"),
                config.get("mixer"), config.get("engine"), backend.connectionGeneration(), current.connected(),
                current.defaultSinkId(), sinks, streams);
        return Integer.toHexString(hash);
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closeFuture != null) return closeFuture;
        closing = true;
        meterRefresh.stop();
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

    private void exportArchive() {
        FileChooser chooser = new FileChooser(); chooser.setTitle(t("Экспортировать резервную копию", "Export backup"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Audio Matrix archive", "*.amx.zip")); chooser.setInitialFileName("audio-matrix.amx.zip");
        java.io.File selected = chooser.showSaveDialog(getScene().getWindow()); if (selected == null) return;
        Path destination = selected.toPath(); Map<String, Object> config = store.snapshot(); Path data = store.path().getParent();
        setNotice(t("Создаю ZIP-архив…", "Creating ZIP archive…"), false);
        archiveIo.execute(() -> { try { ConfigArchive.exportArchive(destination, config, data); Platform.runLater(() -> setNotice(t("Архив сохранён: ", "Archive saved: ") + destination, false)); }
            catch (Exception error) { Platform.runLater(() -> setNotice(t("Экспорт не выполнен: ", "Export failed: ") + rootMessage(error), true)); } });
    }

    private void importArchive() {
        FileChooser chooser = new FileChooser(); chooser.setTitle(t("Выбрать резервную копию", "Choose backup"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Audio Matrix archive", "*.amx.zip", "*.zip"));
        java.io.File selected = chooser.showOpenDialog(getScene().getWindow()); if (selected == null) return;
        long expectedRevision = store.revision();
        Path archive = selected.toPath(); setNotice(t("Проверяю архив и измерения…", "Checking archive and measurements…"), false);
        archiveIo.execute(() -> { try { ConfigArchive.PreparedImport prepared = ConfigArchive.prepare(archive); Platform.runLater(() -> confirmArchiveImport(prepared, expectedRevision)); }
            catch (Exception error) { Platform.runLater(() -> setNotice(t("Архив не прошёл проверку: ", "Archive validation failed: ") + rootMessage(error), true)); } });
    }

    private void confirmArchiveImport(ConfigArchive.PreparedImport prepared, long expectedRevision) {
        List<ConfigArchive.ImportedDevice> unresolved = unresolvedImportedDevices(prepared);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION); confirm.initOwner(getScene().getWindow());
        confirm.setTitle(t("Предпросмотр импорта", "Import preview")); confirm.setHeaderText(t("Архив проверен", "Archive validated"));
        String deviceNote = unresolved.isEmpty() ? "" : "\n\n" + t("Устройства, не найденные однозначно: ", "Devices not found uniquely: ")
                + unresolved.stream().map(device -> device.name() + " (" + device.id() + ")").reduce((a, b) -> a + ", " + b).orElse("")
                + ". " + t("После подтверждения можно сопоставить их с текущими устройствами или оставить offline.", "After confirming, you can map them to current devices or leave them offline.");
        confirm.setContentText(t("Профилей: ", "Profiles: ") + prepared.profileCount() + "\n" + t("Маршрутов: ", "Routes: ") + prepared.routeCount() + "\n" + t("Файлов измерений: ", "Measurement files: ") + prepared.measurementCount() + "\n\n" +
                t("Продолжить? YAML будет заменён после проверки конфликта записи. При совпадении правил могут измениться громкость/mute устройств и потоков; если граф маршрутов сейчас применён или в архиве включено применение при запуске, маршруты могут обновиться.",
                        "Continue? YAML will be replaced after checking for concurrent edits. Matching rules may change device/stream volume or mute; an already-applied route graph or an archive with apply-on-start may refresh routes.") + deviceNote);
        confirm.showAndWait().filter(button -> button == javafx.scene.control.ButtonType.OK).ifPresentOrElse(button -> {
            if (unresolved.isEmpty()) installPreparedImport(prepared, Map.of(), List.of(), expectedRevision);
            else reviewDeviceRemapping(prepared, unresolved, expectedRevision);
        }, prepared::close);
    }

    private List<ConfigArchive.ImportedDevice> unresolvedImportedDevices(ConfigArchive.PreparedImport prepared) {
        return prepared.deviceMappings().stream().filter(device -> matchingNodes(device).size() != 1).toList();
    }

    private List<AudioNode> matchingNodes(ConfigArchive.ImportedDevice device) {
        Map<String,Object> selector = Map.of("kind", device.selectorKind(), "value", device.selectorValue());
        String direction = deviceDirection(device.role());
        List<AudioNode> candidates = switch (direction) {
            case "output" -> snapshot.sinks();
            case "input" -> snapshot.sources();
            default -> { List<AudioNode> all = new ArrayList<>(snapshot.sinks()); all.addAll(snapshot.sources()); yield all; }
        };
        return DeviceSelectorMatcher.findAll(candidates, selector);
    }

    private List<AudioNode> matchingNodes(Map<String, Object> device) {
        Map<String, Object> selector = map(device.get("selector"));
        return matchingNodes(new ConfigArchive.ImportedDevice(String.valueOf(device.getOrDefault("id", "")),
                String.valueOf(device.getOrDefault("name", device.getOrDefault("id", "Device"))),
                string(device.get("role"), ""), string(selector.get("kind"), ""), string(selector.get("value"), "")));
    }

    private static String deviceDirection(String role) {
        if (Set.of("source", "input", "microphone", "accelerometer").contains(role)) return "input";
        if (Set.of("output", "speaker", "speakers", "subwoofer", "haptic_output").contains(role)) return "output";
        return "";
    }

    private List<ConfigArchive.DeviceTarget> availableDeviceTargets() {
        List<ConfigArchive.DeviceTarget> targets = new ArrayList<>();
        snapshot.sinks().forEach(node -> targets.add(new ConfigArchive.DeviceTarget("output", node.name())));
        snapshot.sources().forEach(node -> targets.add(new ConfigArchive.DeviceTarget("input", node.name())));
        return List.copyOf(targets);
    }

    private List<ConfigArchive.DeviceTarget> deviceTargets(String role, List<ConfigArchive.DeviceTarget> all) {
        String direction = deviceDirection(role);
        return direction.isBlank() ? all : all.stream().filter(target -> direction.equals(target.kind())).toList();
    }

    private void reviewDeviceRemapping(ConfigArchive.PreparedImport prepared, List<ConfigArchive.ImportedDevice> unresolved, long expectedRevision) {
        List<ConfigArchive.DeviceTarget> available = availableDeviceTargets();
        Dialog<Map<String, ConfigArchive.DeviceTarget>> dialog = new Dialog<>(); dialog.initOwner(getScene().getWindow());
        dialog.setTitle(t("Сопоставление устройств", "Device remapping"));
        dialog.setHeaderText(t("Некоторые устройства не найдены однозначно", "Some devices are missing or ambiguous"));
        ButtonType importButton = new ButtonType(t("Импортировать", "Import"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(importButton, javafx.scene.control.ButtonType.CANCEL);
        dialog.getDialogPane().setPrefWidth(720);
        VBox content = new VBox(10); content.setPadding(new Insets(8, 4, 4, 4));
        content.getChildren().add(label(t("Выберите текущий sink/source для каждого selector. Пустой выбор сохранит устройство offline. Сопоставление меняет только YAML; применение правил описано в предыдущем подтверждении.",
                "Choose a current sink/source for each selector. Leaving a row blank keeps it offline. Mapping changes YAML; possible live rule application was described in the previous confirmation."), "muted"));
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

    private javafx.scene.control.ListCell<ConfigArchive.DeviceTarget> deviceTargetCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(ConfigArchive.DeviceTarget item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); return; }
                List<AudioNode> nodes = "output".equals(item.kind()) ? snapshot.sinks() : snapshot.sources();
                AudioNode node = nodes.stream().filter(candidate -> candidate.name().equals(item.name())).findFirst().orElse(null);
                setText(node == null ? item.kind() + " · " + item.name() : node.description() + " · " + node.name());
            }
        };
    }

    private void installPreparedImport(ConfigArchive.PreparedImport prepared, Map<String, ConfigArchive.DeviceTarget> remapping,
                                       List<ConfigArchive.DeviceTarget> available, long expectedRevision) {
        setNotice(t("Восстанавливаю конфигурацию и файлы измерений…", "Restoring config and measurement files…"), false);
        archiveIo.execute(() -> { try {
            if (!remapping.isEmpty()) prepared.remapDeviceSelectors(remapping, available);
            ConfigArchive.install(prepared, store.path().getParent(), store, expectedRevision);
            Platform.runLater(() -> { setNotice(t("Импорт завершён.", "Import complete."), false); showPage(Page.PROFILES); });
        } catch (Exception error) { Platform.runLater(() -> setNotice(t("Импорт не выполнен: ", "Import failed: ") + rootMessage(error), true)); }
        finally { prepared.close(); } });
    }

    private void refreshBackend() { backend.refresh().thenAccept(next -> Platform.runLater(() -> { snapshot = next;updateMeterTarget();applyConfiguredDeviceRules(next,store.snapshot());applyConfiguredStreamRules(next,store.snapshot());maybeApplyConfiguredRoutes(store.snapshot(),next);showPage(currentPage); })); }
    private void saveDefaultOutput(AudioNode sink) {
        updateConfig(config -> {Map<String,Object> device=ensureDevice(config,sink);activeProfile(config).put("default_output",device.get("id"));});
    }
    private void saveDeviceVolume(AudioNode sink, double volume) {
        backend.setVolume(sink.id(), volume);
        updateConfig(config -> {
            Map<String, Object> device = ensureDevice(config, sink);
            device.put("volume", volume);
        });
    }
    private void saveDeviceMute(AudioNode sink, boolean muted) {
        updateConfig(config -> { Map<String, Object> device = ensureDevice(config, sink); device.put("mute", muted); });
    }
    private void saveAppVolume(AudioNode stream, double volume) {
        updateConfig(config -> { Map<String, Object> app = ensureApplication(config, stream); app.put("volume", volume); });
    }
    private void saveAppMute(AudioNode stream, boolean muted) {
        updateConfig(config -> { Map<String, Object> app = ensureApplication(config, stream); app.put("mute", muted); });
    }
    private void applyConfiguredDeviceRules(PipeWireBackend.Snapshot current,Map<String,Object> config){
        if(!current.connected())return;
        Set<Integer> liveIds=current.sinks().stream().map(AudioNode::id).collect(java.util.stream.Collectors.toSet());appliedDeviceRules.keySet().removeIf(id->!liveIds.contains(id));
        for(AudioNode sink:current.sinks()){
            Map<String,Object> device=matchingDevice(config,sink);if(device==null){appliedDeviceRules.remove(sink.id());continue;}
            double volume=Math.max(0,Math.min(1,number(device.get("volume"),1.0)));boolean muted=Boolean.TRUE.equals(device.get("mute"));
            String signature=String.valueOf(device.get("id"))+"|"+map(device.get("selector"))+"|"+volume+"|"+muted;if(signature.equals(appliedDeviceRules.get(sink.id())))continue;appliedDeviceRules.put(sink.id(),signature);
            List<CompletableFuture<Void>> changes=new ArrayList<>();if(!Double.isFinite(sink.volume())||Math.abs(sink.volume()-volume)>.01)changes.add(backend.setVolume(sink.id(),volume));if(sink.muted()!=muted)changes.add(backend.setMute(sink.id(),muted));
            CompletableFuture.allOf(changes.toArray(CompletableFuture<?>[]::new)).whenComplete((ignored,error)->{if(error!=null&&appliedDeviceRules.remove(sink.id(),signature))Platform.runLater(()->setNotice(t("Не удалось применить правило устройства ","Could not apply device rule for ")+nodeDisplayName(sink)+": "+rootMessage(error),true));});
        }
    }
    private void applyConfiguredStreamRules(PipeWireBackend.Snapshot current, Map<String, Object> config) {
        streamRuleApplier.apply(current, maps(config.get("applications")), (stream, error) ->
                Platform.runLater(() -> setNotice(t("Не удалось применить правило потока ", "Could not apply stream rule for ")
                        + stream.applicationName() + ": " + rootMessage(error), true)));
    }
    private Map<String, Object> ensureDevice(Map<String, Object> config, AudioNode node) {
        Map<String,Object> existing=matchingDevice(config,node);if(existing!=null)return existing;
        List<Map<String,Object>> devices=maps(config.get("devices"));String id="device-"+UUID.randomUUID().toString().substring(0,8);
        Map<String,Object> item=DefaultConfigMap.map("id",id,"selector",DefaultConfigMap.map("kind","name_equals","value",node.name()),"volume",finite(node.volume())?Math.max(0,Math.min(1,node.volume())):1.0,"mute",node.muted());devices.add(item);return item;
    }
    private Map<String,Object> matchingDevice(Map<String,Object> config,AudioNode node){
        List<Map<String,Object>> matches=maps(config.get("devices")).stream().filter(device->{List<AudioNode> resolved=matchingNodes(device);return resolved.size()==1&&resolved.getFirst().id()==node.id()&&resolved.getFirst().kind().equals(node.kind());}).toList();
        return matches.size()==1?matches.getFirst():null;
    }
    private AudioNode resolveDevice(Map<String,Object> config,Map<String,Object> device){List<AudioNode> resolved=matchingNodes(device);return resolved.size()==1?resolved.getFirst():null;}
    private String nodeDisplayName(AudioNode node){Map<String,Object> device=matchingDevice(store.snapshot(),node);String name=device==null?"":string(device.get("name"),"");return name.isBlank()?node.description():name;}
    private void editDeviceSelector(Map<String,Object> savedDevice,AudioNode node){
        Map<String,Object> config=store.snapshot();Map<String,Object> current=savedDevice==null?null:maps(config.get("devices")).stream().filter(device->String.valueOf(savedDevice.get("id")).equals(device.get("id"))).findFirst().orElse(null);
        String id=current==null?"":String.valueOf(current.get("id"));Map<String,Object> oldSelector=current==null?DefaultConfigMap.map("kind","name_equals","value",node==null?"":node.name()):map(current.get("selector"));
        Dialog<Map<String,Object>> dialog=new Dialog<>();if(getScene()!=null)dialog.initOwner(getScene().getWindow());dialog.setTitle(t("Имя и selector устройства","Device name and selector"));dialog.setHeaderText(current==null?node.description():string(current.get("name"),id));dialog.getDialogPane().setPrefWidth(600);styleDialog(dialog);
        TextField alias=field(current==null?"":string(current.get("name"),""),t("Понятное имя устройства","Device display name"));ComboBox<String> kind=new ComboBox<>();kind.getItems().setAll("name_equals","name_contains","description_contains");kind.setValue(string(oldSelector.get("kind"),"name_equals"));kind.setAccessibleText(t("Тип selector","Selector kind"));TextField value=field(string(oldSelector.get("value"),""),t("Значение selector","Selector value"));Label error=label("", "danger");
        ComboBox<String> role = new ComboBox<>();
        role.getItems().setAll("unassigned", "haptic_output", "subwoofer", "speaker", "speakers", "output", "microphone", "accelerometer", "input", "source");
        String selectedRole = string(current == null ? null : current.get("role"), "unassigned");
        if (!role.getItems().contains(selectedRole)) role.getItems().add(selectedRole);
        role.setValue(selectedRole);
        role.setAccessibleText(t("Роль устройства для режимов профиля", "Device role for profile modes"));
        role.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String value) {
                if (value == null) return "";
                return switch (value) {
                    case "unassigned" -> t("Не назначена", "Unassigned");
                    case "haptic_output" -> t("Вибровыход (haptic_output)", "Haptic output (haptic_output)");
                    case "subwoofer" -> t("Сабвуфер (subwoofer)", "Subwoofer (subwoofer)");
                    case "speaker", "speakers" -> t("Колонки (speaker)", "Speakers (speaker)");
                    case "output" -> t("Общий выход (output)", "Generic output (output)");
                    case "microphone" -> t("Микрофон (microphone)", "Microphone (microphone)");
                    case "accelerometer" -> t("Акселерометр (accelerometer)", "Accelerometer (accelerometer)");
                    case "input", "source" -> t("Общий вход (input)", "Generic input (input)");
                    default -> value;
                };
            }
            @Override public String fromString(String value) { return value; }
        });
        VBox content=new VBox(8,label(t("Понятное имя (необязательно)","Display name (optional)"),"muted"),alias,label(t("Стабильный selector","Stable selector"),"muted"),new HBox(8,kind,value),label(t("Роль выхода/входа. Новым устройствам роль не назначается автоматически.","Output/input role. New devices are not assigned a role automatically."),"muted"),role,error);HBox.setHgrow(value,Priority.ALWAYS);dialog.getDialogPane().setContent(content);dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,javafx.scene.control.ButtonType.CANCEL);Button save=(Button)dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK);
        Runnable validate=()->{Map<String,Object> selector=DefaultConfigMap.map("kind",kind.getValue(),"value",value.getText().trim());List<AudioNode> all=new ArrayList<>(snapshot.sinks());all.addAll(snapshot.sources());long count=all.stream().filter(candidate->DeviceSelectorMatcher.matches(candidate,selector)).count();boolean targetMatches=node==null||DeviceSelectorMatcher.matches(node,selector);String issue=kind.getValue()==null?t("Выберите тип selector.","Choose a selector kind."):value.getText().isBlank()?t("Введите значение selector.","Enter a selector value."):!targetMatches?t("Selector должен находить выбранное устройство.","The selector must match the selected device."):count>1?t("Selector неоднозначен: он подходит нескольким подключённым устройствам.","The selector is ambiguous and matches multiple connected devices."):"";error.setText(issue);error.setManaged(!issue.isEmpty());error.setVisible(!issue.isEmpty());save.setDisable(!issue.isEmpty());};
        kind.valueProperty().addListener((o,a,b)->validate.run());value.textProperty().addListener((o,a,b)->validate.run());validate.run();
        dialog.setResultConverter(button->button==javafx.scene.control.ButtonType.OK?DefaultConfigMap.map("name",alias.getText().trim(),"selector",DefaultConfigMap.map("kind",kind.getValue(),"value",value.getText().trim()),"role",role.getValue()):null);
        dialog.showAndWait().ifPresent(result->updateConfig(c->{Map<String,Object> device=current==null?ensureDevice(c,node):maps(c.get("devices")).stream().filter(item->id.equals(item.get("id"))).findFirst().orElse(null);if(device==null)return;device.put("selector",result.get("selector"));if(String.valueOf(result.get("name")).isBlank())device.remove("name");else device.put("name",result.get("name"));if("unassigned".equals(result.get("role")))device.remove("role");else device.put("role",result.get("role"));}));
    }

    private void editOutputEq(Map<String, Object> savedDevice, AudioNode node) {
        if (savedDevice == null && node == null) return;
        Map<String, Object> config = store.snapshot();
        String id = savedDevice == null ? "" : string(savedDevice.get("id"), "");
        Map<String, Object> current = savedDevice == null ? null : maps(config.get("devices")).stream()
                .filter(device -> id.equals(device.get("id"))).findFirst().orElse(null);
        ComboBox<String> filtersRef = new ComboBox<>();
        filtersRef.getItems().add("none");
        map(map(config.get("dsp")).get("route_chains")).keySet().stream().sorted()
                .map(chainId -> "dsp.route_chains." + chainId).forEach(filtersRef.getItems()::add);
        String selected = string(current == null ? null : current.get("filters_ref"), "none");
        if (!filtersRef.getItems().contains(selected)) filtersRef.getItems().add(selected);
        filtersRef.setValue(selected);
        filtersRef.setAccessibleText(t("EQ-цепочка физического выхода", "Physical output EQ chain"));
        Dialog<javafx.scene.control.ButtonType> dialog = new Dialog<>();
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle(t("Коррекция выхода", "Output correction"));
        dialog.setHeaderText(current == null ? nodeDisplayName(node) : string(current.get("name"), id));
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        dialog.getDialogPane().setContent(new VBox(8,
                label(t("Общая цепочка применяется после сведения всех маршрутов на этот выход.",
                        "This shared chain runs after all routes to this output have been mixed."), "muted"), filtersRef));
        styleDialog(dialog);
        dialog.showAndWait().filter(javafx.scene.control.ButtonType.OK::equals).ifPresent(button -> updateConfig(candidate -> {
            Map<String, Object> device = current == null ? ensureDevice(candidate, node)
                    : maps(candidate.get("devices")).stream().filter(item -> id.equals(item.get("id"))).findFirst().orElse(null);
            if (device == null) return;
            if ("none".equals(filtersRef.getValue())) device.remove("filters_ref");
            else device.put("filters_ref", filtersRef.getValue());
        }));
    }
    private Map<String, Object> ensureApplication(Map<String, Object> config, AudioNode stream) {
        List<Map<String, Object>> applications = maps(config.get("applications"));
        Map<String, Object> existing = ApplicationRuleMatcher.find(applications, stream);
        if (existing != null) return existing;
        String process = stream.processName(); String appId = stream.applicationId();
        Map<String, Object> match = new LinkedHashMap<>();
        if (!process.isBlank()) match.put("process", process);
        if (!appId.isBlank()) match.put("app_id", appId);
        if (match.isEmpty()) match.put("process", stream.name());
        String identity = !process.isBlank() ? process : !appId.isBlank() ? appId : stream.name();
        Map<String, Object> item = DefaultConfigMap.map("id", slug(identity), "match", match, "volume", 1.0, "mute", false, "bus", "main", "preferred_output", "system_default");
        applications.add(item); return item;
    }
    private HBox busRow(Map<String, Object> bus, boolean active) {
        String id = string(bus.get("id"), "");
        HBox row = new HBox(10); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("node-card");
        VBox details = new VBox(4, label(string(bus.get("name"), id), "card-title"),
                label(string(bus.get("channels"), "stereo") + " · " + volumeLabel(number(bus.get("volume"), 1))
                        + (Boolean.TRUE.equals(bus.get("mute")) ? " · Mute" : "")
                        + (bus.get("filters_ref") == null ? "" : " · EQ: " + string(bus.get("filters_ref"), "")), "muted"));
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().addAll(details, spacer,
                actionButton(active ? t("Отключить", "Deactivate") : t("Включить", "Activate"), () -> setProfileMembership("active_buses", id, !active)),
                actionButton(t("Изменить", "Edit"), () -> editBus(bus)),
                actionButton(t("Удалить", "Delete"), () -> deleteBus(id)));
        return row;
    }

    private HBox routeRow(Map<String, Object> route, boolean active) {
        Map<String, Object> config = store.snapshot();
        String id = string(route.get("id"), "");
        String polarity = string(route.get("polarity"), "normal");
        String routeChannels = route.containsKey("channel_matrix") ? t("произвольная матрица", "custom matrix")
                : string(route.get("channel_map"), "stereo");
        String summary = endpointLabel(config, string(route.get("from"), "")) + " → " + endpointLabel(config, string(route.get("to"), ""))
                + " · " + number(route.get("gain_db"), 0) + " dB · " + number(route.get("delay_ms"), 0) + " ms · " + routeChannels
                + " · p" + number(route.get("priority"), 0)
                + ("normal".equals(polarity) ? "" : " · " + t("инверсия фазы: ", "phase inverted: ") + polarityLabel(polarity))
                + (route.get("filters_ref") == null ? "" : " · EQ: " + string(route.get("filters_ref"), ""))
                + (route.get("fallback") == null ? "" : " · " + t("fallback: ", "fallback: ") + endpointLabel(config, string(route.get("fallback"), "")));
        HBox row = new HBox(10); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("node-card");
        VBox details = new VBox(4, label(string(route.get("name"), id), "card-title"), label(summary, "muted"));
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().addAll(details, spacer,
                actionButton(active ? t("Отключить", "Deactivate") : t("Включить", "Activate"), () -> setProfileMembership("active_routes", id, !active)),
                actionButton(t("Изменить", "Edit"), () -> editRoute(route)),
                actionButton(t("Удалить", "Delete"), () -> deleteRoute(id)));
        return row;
    }

    private String endpointLabel(Map<String, Object> config, String id) {
        Map<String, Object> bus = maps(config.get("buses")).stream().filter(item -> id.equals(item.get("id"))).findFirst().orElse(null);
        if (bus != null) return string(bus.get("name"), id);
        if ("system_default".equals(id)) return t("системный выход", "system default");
        return outputReferenceName(config, id);
    }

    private void setProfileMembership(String key, String id, boolean enabled) {
        String target = "active_buses".equals(key) ? t("шину ", "bus ") : t("маршрут ", "route ");
        String description = (enabled ? t("Включить ", "Activate ") : t("Отключить ", "Deactivate ")) + target + id;
        updateRouteConfig(description, config -> {
            Map<String, Object> profile = activeProfile(config);
            List<String> members = new ArrayList<>(stringList(profile.get(key)));
            if (enabled && !members.contains(id)) members.add(id);
            if (!enabled) members.removeIf(id::equals);
            profile.put(key, members);
        });
    }

    private void editBus(Map<String, Object> existing) {
        Map<String, Object> source = existing == null ? DefaultConfigMap.map("name", "", "role", "general", "channels", "stereo", "volume", 1.0, "mute", false) : existing;
        Dialog<javafx.scene.control.ButtonType> dialog = new Dialog<>();
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle(existing == null ? t("Добавить шину", "Add bus") : t("Изменить шину", "Edit bus"));
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        TextField name = field(string(source.get("name"), ""), t("Имя шины", "Bus name"));
        ComboBox<String> role = new ComboBox<>(); role.getItems().setAll("general", "music", "effects", "subwoofer");
        String busId = string(source.get("id"), "");
        role.setValue(string(source.get("role"), ProfileRoutePolicy.builtInBusRole(busId)));
        role.setAccessibleText(t("Назначение шины для режимов профиля", "Bus purpose for profile modes"));
        role.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String value) { return switch (value == null ? "" : value) {
                case "general" -> t("Общая", "General"); case "music" -> t("Музыка", "Music");
                case "effects" -> t("Эффекты", "Effects"); case "subwoofer" -> t("Сабвуферная ветка", "Subwoofer branch");
                default -> value;
            }; }
            @Override public String fromString(String value) { return value; }
        });
        ComboBox<String> channels = new ComboBox<>(); channels.getItems().setAll("mono", "stereo", "5.1", "7.1"); channels.setValue(string(source.get("channels"), "stereo")); channels.setAccessibleText(t("Каналы шины", "Bus channels"));
        TextField volume = field(String.valueOf(number(source.get("volume"), 1)), t("Громкость 0–1", "Volume 0–1"));
        ComboBox<String> filtersRef = new ComboBox<>(); filtersRef.getItems().add("none");
        map(map(store.snapshot().get("dsp")).get("route_chains")).keySet().stream().sorted().map(id -> "dsp.route_chains." + id).forEach(filtersRef.getItems()::add);
        filtersRef.setValue(string(source.get("filters_ref"), "none")); filtersRef.setAccessibleText(t("EQ шины", "Bus EQ"));
        CheckBox muted = new CheckBox(t("Mute шины", "Mute bus")); muted.setSelected(Boolean.TRUE.equals(source.get("mute")));
        VBox content = new VBox(10, label(t("Название", "Name"), "muted"), name,
                label(t("Назначение для Haptic/Subwoofer", "Purpose for Haptic/Subwoofer modes"), "muted"), role,
                label(t("Каналы", "Channels"), "muted"), channels,
                label(t("Уровень 0–1", "Volume 0–1"), "muted"), volume,
                label(t("EQ шины (route chain)", "Bus EQ (route chain)"), "muted"), filtersRef, muted);
        dialog.getDialogPane().setContent(content);
        styleDialog(dialog);
        dialog.showAndWait().filter(javafx.scene.control.ButtonType.OK::equals).ifPresent(result -> {
            double level;
            try { level = Double.parseDouble(volume.getText().trim()); }
            catch (NumberFormatException error) { setNotice(t("Громкость должна быть числом от 0 до 1.", "Volume must be a number from 0 to 1."), true); return; }
            if (name.getText().isBlank() || role.getValue() == null || channels.getValue() == null || !Double.isFinite(level) || level < 0 || level > 1) {
                setNotice(t("Проверьте название, каналы и громкость шины.", "Check the bus name, channels, and volume."), true); return;
            }
            updateRouteConfig(existing == null ? t("Создать шину", "Create bus") : t("Изменить шину", "Edit bus"), config -> {
                Map<String, Object> bus;
                if (existing == null) {
                    String id = "user-" + UUID.randomUUID().toString().substring(0, 8);
                    bus = DefaultConfigMap.map("id", id, "name", name.getText().trim(), "role", role.getValue(), "channels", channels.getValue(), "volume", level, "mute", muted.isSelected());
                    maps(config.get("buses")).add(bus);
                    List<String> active = new ArrayList<>(stringList(activeProfile(config).get("active_buses"))); active.add(id); activeProfile(config).put("active_buses", active);
                } else {
                    String id = string(existing.get("id"), "");
                    bus = maps(config.get("buses")).stream().filter(item -> id.equals(item.get("id"))).findFirst().orElse(null);
                    if (bus == null) return;
                    bus.put("name", name.getText().trim()); bus.put("role", role.getValue()); bus.put("channels", channels.getValue()); bus.put("volume", level); bus.put("mute", muted.isSelected());
                }
                if ("none".equals(filtersRef.getValue())) bus.remove("filters_ref"); else bus.put("filters_ref", filtersRef.getValue());
            });
        });
    }

    private void deleteBus(String id) {
        if (maps(store.snapshot().get("buses")).size() <= 1) { setNotice(t("Нельзя удалить последнюю шину.", "The last bus cannot be deleted."), true); return; }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, t("Связанные маршруты будут удалены, приложения переназначены на другую шину. Удалить?", "Related routes will be removed and applications moved to another bus. Delete it?"), javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        if (getScene() != null) confirm.initOwner(getScene().getWindow());
        confirm.setHeaderText(t("Удалить шину", "Delete bus"));
        confirm.showAndWait().filter(javafx.scene.control.ButtonType.OK::equals).ifPresent(button -> updateRouteConfig(t("Удалить шину ", "Delete bus ") + id, config -> {
            List<Map<String, Object>> buses = maps(config.get("buses"));
            String fallback = buses.stream().map(item -> string(item.get("id"), "")).filter(candidate -> !id.equals(candidate)).findFirst().orElse("");
            buses.removeIf(item -> id.equals(item.get("id")));
            Set<String> removedRoutes = new java.util.HashSet<>();
            maps(config.get("routes")).removeIf(route -> {
                boolean remove = id.equals(route.get("from")) || id.equals(route.get("to"));
                if (remove) removedRoutes.add(string(route.get("id"), ""));
                return remove;
            });
            for (Map<String, Object> profile : maps(config.get("profiles"))) {
                profile.put("active_buses", stringList(profile.get("active_buses")).stream().filter(candidate -> !id.equals(candidate)).toList());
                profile.put("active_routes", stringList(profile.get("active_routes")).stream().filter(candidate -> !removedRoutes.contains(candidate)).toList());
                if (stringList(profile.get("active_buses")).isEmpty() && !fallback.isBlank()) profile.put("active_buses", List.of(fallback));
            }
            for (Map<String, Object> application : maps(config.get("applications"))) if (id.equals(application.get("bus"))) {
                if (fallback.isBlank()) application.remove("bus"); else application.put("bus", fallback);
            }
        }));
    }

    private List<Endpoint> routeEndpoints(Map<String, Object> config) {
        List<Endpoint> result = new ArrayList<>();
        for (Map<String, Object> bus : maps(config.get("buses"))) {
            String id = string(bus.get("id"), ""); result.add(new Endpoint(id, t("Шина · ", "Bus · ") + string(bus.get("name"), id), true, null));
        }
        result.add(new Endpoint("system_default", t("Выход · системный по умолчанию", "Output · system default"), false, null));
        Set<String> knownDevices = new java.util.HashSet<>();
        for (Map<String, Object> device : maps(config.get("devices"))) {
            String role = string(device.get("role"), "");
            if (!Set.of("sink", "output").contains(role)) continue;
            String id = string(device.get("id"), ""); knownDevices.add(id);
            AudioNode online = DeviceSelectorMatcher.resolve(snapshot.sinks(), id, maps(config.get("devices")));
            result.add(new Endpoint(id, t("Выход · ", "Output · ") + outputReferenceName(config, id), false, online));
        }
        for (AudioNode sink : snapshot.sinks()) {
            Map<String, Object> device = matchingDevice(config, sink);
            String id = device == null ? "live:" + sink.id() : string(device.get("id"), "");
            if (knownDevices.add(id)) result.add(new Endpoint(id, t("Выход · ", "Output · ") + nodeDisplayName(sink), false, sink));
        }
        return result;
    }

    private void editRoute(Map<String, Object> existing) {
        Map<String, Object> config = store.snapshot();
        List<Endpoint> endpoints = routeEndpoints(config);
        List<Endpoint> fromEndpoints = endpoints.stream().filter(Endpoint::bus).toList();
        Dialog<javafx.scene.control.ButtonType> dialog = new Dialog<>();
        if (getScene() != null) dialog.initOwner(getScene().getWindow());
        dialog.setTitle(existing == null ? t("Добавить маршрут", "Add route") : t("Изменить маршрут", "Edit route"));
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        TextField name = field(string(existing == null ? null : existing.get("name"), existing == null ? t("Новый маршрут", "New route") : string(existing.get("id"), "")), t("Название маршрута", "Route name"));
        ComboBox<Endpoint> from = endpointCombo(fromEndpoints); from.setPromptText(t("Из шины", "From bus")); from.setAccessibleText(t("Шина-источник", "Source bus"));
        ComboBox<Endpoint> to = endpointCombo(endpoints); to.setPromptText(t("В шину или выход", "To bus or output")); to.setAccessibleText(t("Назначение маршрута", "Route destination"));
        if (existing != null) {
            fromEndpoints.stream().filter(item -> item.id().equals(existing.get("from"))).findFirst().ifPresent(from::setValue);
            endpoints.stream().filter(item -> item.id().equals(existing.get("to"))).findFirst().ifPresent(to::setValue);
        }
        AtomicReference<Map<String, Object>> channelMatrix = new AtomicReference<>(existing != null && existing.get("channel_matrix") instanceof Map<?, ?> matrix
                ? copyMap(map(matrix)) : null);
        double maximumRouteGain = limiterEnabled(config) ? 24 : 0;
        TextField gain = field(String.valueOf(number(existing == null ? null : existing.get("gain_db"), 0)),
                maximumRouteGain > 0 ? t("Gain dB −60…24", "Gain dB −60…24") : t("Gain dB −60…0 (limiter выключен)", "Gain dB −60…0 (limiter disabled)"));
        TextField volume = field(String.valueOf(number(existing == null ? null : existing.get("volume"), 1)), t("Громкость 0–1", "Volume 0–1"));
        TextField delay = field(String.valueOf(number(existing == null ? null : existing.get("delay_ms"), 0)), t("Задержка ms 0–2000", "Delay ms 0–2000"));
        ComboBox<String> channelMap = new ComboBox<>(); channelMap.getItems().setAll("stereo", "mono", "mono_sum", "left", "right", "swap_lr",
                "center", "lfe", "rear_left", "rear_right", "rear_sum", "side_left", "side_right", "side_sum", "surround_sum", "all_sum");
        channelMap.setValue(string(existing == null ? null : existing.get("channel_map"), "stereo")); channelMap.setAccessibleText(t("Channel map", "Channel map"));
        ComboBox<String> polarity = new ComboBox<>(); polarity.getItems().setAll("normal", "invert", "invert_left", "invert_right");
        polarity.setValue(string(existing == null ? null : existing.get("polarity"), "normal"));
        polarity.setAccessibleText(t("Полярность маршрута", "Route polarity"));
        polarity.setCellFactory(items -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : polarityOptionLabel(item)); }
        });
        polarity.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(String item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : polarityOptionLabel(item)); }
        });
        Runnable refreshPolarityOptions = () -> {
            String selected = polarity.getValue();
            Set<String> outputPositions;
            try { outputPositions = channelMatrix.get() == null ? null : Set.copyOf(ChannelMatrix.parse(channelMatrix.get()).outputs()); }
            catch (IllegalArgumentException ignored) { outputPositions = Set.of(); }
            boolean stereo = outputPositions != null ? outputPositions.containsAll(Set.of("FL", "FR"))
                    : !Set.of("mono", "mono_sum", "left", "right", "center", "lfe", "rear_left", "rear_right",
                            "rear_sum", "side_left", "side_right", "side_sum", "surround_sum", "all_sum").contains(channelMap.getValue());
            polarity.getItems().setAll(stereo ? List.of("normal", "invert", "invert_left", "invert_right") : List.of("normal", "invert"));
            if (selected != null && polarity.getItems().contains(selected)) polarity.setValue(selected);
            else polarity.setValue("normal");
        };
        channelMap.valueProperty().addListener((observable, oldValue, newValue) -> refreshPolarityOptions.run());
        refreshPolarityOptions.run();
        Button clearMatrix = actionButton(t("Убрать матрицу", "Clear matrix"), () -> {});
        clearMatrix.setOnAction(event -> {
            channelMatrix.set(null);
            channelMap.setDisable(false);
            clearMatrix.setDisable(true);
            refreshPolarityOptions.run();
        });
        clearMatrix.setDisable(channelMatrix.get() == null);
        Button matrixButton = actionButton(t("Редактировать матрицу каналов…", "Edit channel matrix…"), () -> {
            Endpoint source = from.getValue(), target = to.getValue();
            if (source == null || target == null) {
                setNotice(t("Сначала выберите шину-источник и назначение маршрута.", "Choose a source bus and route destination first."), true);
                return;
            }
            List<String> inputs = routeMatrixBusPositions(config, source.id());
            List<String> outputs = routeMatrixEndpointPositions(config, target);
            if (outputs.isEmpty() && channelMatrix.get() != null) {
                try { outputs = ChannelMatrix.parse(channelMatrix.get()).outputs(); }
                catch (IllegalArgumentException ignored) { }
            }
            if (inputs.isEmpty() || outputs.isEmpty()) {
                setNotice(t("Нужны доступные сведения о каналах источника и назначения.", "Channel layouts for the selected source and destination must be available."), true);
                return;
            }
            Map<String, Object> updated = editChannelMatrix(inputs, outputs, channelMatrix.get(), limiterEnabled(config));
            if (updated != null) {
                channelMatrix.set(updated);
                channelMap.setDisable(true);
                clearMatrix.setDisable(false);
                refreshPolarityOptions.run();
            }
        });
        channelMap.setDisable(channelMatrix.get() != null);
        ComboBox<String> filtersRef = new ComboBox<>(); filtersRef.getItems().add("none");
        map(map(config.get("dsp")).get("route_chains")).keySet().stream().sorted().map(id -> "dsp.route_chains." + id).forEach(filtersRef.getItems()::add);
        String currentFiltersRef = string(existing == null ? null : existing.get("filters_ref"), "none");
        if (!filtersRef.getItems().contains(currentFiltersRef)) filtersRef.getItems().add(currentFiltersRef);
        filtersRef.setValue(currentFiltersRef); filtersRef.setAccessibleText(t("EQ-цепочка маршрута", "Route EQ chain"));
        CheckBox muted = new CheckBox(t("Mute маршрута", "Mute route")); muted.setSelected(Boolean.TRUE.equals(existing == null ? false : existing.get("mute")));
        CheckBox enabled = new CheckBox(t("Маршрут включён", "Route enabled")); enabled.setSelected(existing == null || !Boolean.FALSE.equals(existing.get("enabled")));
        TextField priority = field(String.valueOf((int) number(existing == null ? null : existing.get("priority"), 0)), t("Приоритет 0–1000", "Priority 0–1000"));
        VBox gainField = new VBox(3, label(maximumRouteGain > 0 ? t("Gain, dB · −60…24", "Gain, dB · −60…24")
                : t("Gain, dB · −60…0", "Gain, dB · −60…0"), "muted"), gain);
        VBox volumeField = new VBox(3, label(t("Громкость · 0–1", "Volume · 0–1"), "muted"), volume);
        VBox delayField = new VBox(3, label(t("Задержка, ms · 0–2000", "Delay, ms · 0–2000"), "muted"), delay);
        VBox priorityField = new VBox(3, label(t("Приоритет · 0–1000", "Priority · 0–1000"), "muted"), priority);
        List<Endpoint> fallbackOptions = endpoints.stream().filter(endpoint -> !endpoint.bus()).toList();
        ComboBox<Endpoint> fallback = endpointCombo(fallbackOptions); fallback.setPromptText(t("Без резервного выхода", "No fallback output")); fallback.setAccessibleText(t("Резервный выход", "Fallback output"));
        if (existing != null && existing.get("fallback") != null) fallbackOptions.stream().filter(item -> item.id().equals(existing.get("fallback"))).findFirst().ifPresent(fallback::setValue);
        VBox content = new VBox(8, label(t("Имя", "Name"), "muted"), name, label(t("Источник", "Source"), "muted"), from,
                label(t("Назначение", "Destination"), "muted"), to, new HBox(8, gainField, volumeField), new HBox(8, delayField, priorityField),
                label(t("Channel map", "Channel map"), "muted"), channelMap,
                new HBox(8, matrixButton, clearMatrix),
                label(t("Коэффициенты матрицы: строки — выходы, столбцы — входы. Отрицательный коэффициент инвертирует фазу. Сумма модулей в строке выше 1 требует limiter.",
                        "Matrix rows are outputs and columns are inputs. Negative coefficients invert phase. A row absolute-sum above 1 requires the output limiter."), "muted"),
                label(t("Полярность", "Polarity"), "muted"), polarity,
                label(t("EQ-цепочка (необязательно)", "EQ chain (optional)"), "muted"), filtersRef,
                label(t("Резервный выход при недоступности цели", "Fallback if the destination is unavailable"), "muted"), fallback, muted, enabled);
        dialog.getDialogPane().setContent(content);
        styleDialog(dialog);
        dialog.showAndWait().filter(javafx.scene.control.ButtonType.OK::equals).ifPresent(button -> {
            if (name.getText().isBlank() || from.getValue() == null || to.getValue() == null) { setNotice(t("Заполните имя, шину и назначение.", "Provide a name, source bus, and destination."), true); return; }
            double gainDb, level, delayMs; int priorityValue;
            try { gainDb = Double.parseDouble(gain.getText().trim()); level = Double.parseDouble(volume.getText().trim()); delayMs = Double.parseDouble(delay.getText().trim()); priorityValue = Integer.parseInt(priority.getText().trim()); }
            catch (NumberFormatException error) { setNotice(t("Gain, громкость, задержка и приоритет должны быть числами.", "Gain, volume, delay, and priority must be numeric."), true); return; }
            if (!Double.isFinite(gainDb) || gainDb < -60 || gainDb > maximumRouteGain || !Double.isFinite(level) || level < 0 || level > 1 || !Double.isFinite(delayMs) || delayMs < 0 || delayMs > 2000 || priorityValue < 0 || priorityValue > 1000 || channelMap.getValue() == null) {
                setNotice(t("Проверьте пределы Gain, громкости, задержки, приоритета и channel map.", "Check the Gain, volume, delay, priority, and channel map limits."), true); return;
            }
            String routeId = existing == null ? "route-" + UUID.randomUUID().toString().substring(0, 8) : string(existing.get("id"), "");
            Endpoint target = to.getValue();
            String targetId = target.id();
            if (target.node() != null) {
                Map<String, Object> device = ensureDevice(config, target.node());
                targetId = string(device.get("id"), targetId);
            }
            String fallbackId = null;
            if (fallback.getValue() != null) {
                Endpoint selectedFallback = fallback.getValue(); fallbackId = selectedFallback.id();
                if (selectedFallback.node() != null) fallbackId = string(ensureDevice(config, selectedFallback.node()).get("id"), fallbackId);
            }
            Map<String, Object> route = DefaultConfigMap.map("id", routeId, "name", name.getText().trim(), "from", from.getValue().id(), "to", targetId,
                    "gain_db", gainDb, "volume", level, "mute", muted.isSelected(), "delay_ms", delayMs, "priority", priorityValue,
                    "channel_map", channelMap.getValue(), "polarity", polarity.getValue(), "enabled", enabled.isSelected());
            if (channelMatrix.get() != null) route.put("channel_matrix", channelMatrix.get());
            if (filtersRef.getValue() != null && !"none".equals(filtersRef.getValue())) route.put("filters_ref", filtersRef.getValue());
            if (fallbackId != null) route.put("fallback", fallbackId);
            List<Map<String, Object>> candidateRoutes = maps(config.get("routes"));
            int oldIndex = -1;
            if (existing != null) for (int i = 0; i < candidateRoutes.size(); i++) if (routeId.equals(candidateRoutes.get(i).get("id"))) { oldIndex = i; break; }
            if (oldIndex >= 0) candidateRoutes.set(oldIndex, route); else candidateRoutes.add(route);
            List<String> active = new ArrayList<>(stringList(activeProfile(config).get("active_routes")));
            if (!active.contains(routeId)) active.add(routeId);
            activeProfile(config).put("active_routes", active);
            List<String> errors = ConfigValidator.validate(config);
            if (!errors.isEmpty()) { setNotice(t("Маршрут не сохранён: ", "Route not saved: ") + String.join("; ", errors), true); return; }
            saveRouteConfig(existing == null ? t("Создать маршрут", "Create route") : t("Изменить маршрут", "Edit route"), config);
        });
    }

    private Map<String, Object> editChannelMatrix(List<String> inputs, List<String> outputs,
                                                   Map<String, Object> current, boolean limiterAvailable) {
        if (inputs.isEmpty() || outputs.isEmpty() || inputs.size() > 8 || outputs.size() > 8) return null;
        ChannelMatrix previous = null;
        try { if (current != null) previous = ChannelMatrix.parse(current); }
        catch (IllegalArgumentException ignored) { }

        GridPane grid = new GridPane();
        grid.setHgap(6); grid.setVgap(6); grid.setPadding(new Insets(8));
        grid.add(label(t("Выход ↓ / вход →", "Output ↓ / input →"), "muted"), 0, 0);
        for (int column = 0; column < inputs.size(); column++)
            grid.add(label(inputs.get(column), "card-title"), column + 1, 0);
        TextField[][] cells = new TextField[outputs.size()][inputs.size()];
        for (int row = 0; row < outputs.size(); row++) {
            grid.add(label(outputs.get(row), "card-title"), 0, row + 1);
            for (int column = 0; column < inputs.size(); column++) {
                String output = outputs.get(row), input = inputs.get(column);
                double initial = output.equals(input) ? 1.0 : 0.0;
                if (previous != null && previous.outputs().contains(output) && previous.inputs().contains(input))
                    initial = previous.coefficients().get(previous.outputs().indexOf(output)).get(previous.inputs().indexOf(input));
                TextField coefficient = new TextField(String.format(Locale.ROOT, "%.3f", initial));
                coefficient.setPrefColumnCount(6);
                coefficient.setAccessibleText(t("Коэффициент ", "Coefficient ") + output + " ← " + input);
                cells[row][column] = coefficient;
                grid.add(coefficient, column + 1, row + 1);
            }
        }
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true); scroll.setPrefViewportWidth(650); scroll.setPrefViewportHeight(360);
        Dialog<ButtonType> matrixDialog = new Dialog<>();
        if (getScene() != null) matrixDialog.initOwner(getScene().getWindow());
        matrixDialog.setTitle(t("Матрица каналов", "Channel matrix"));
        matrixDialog.setHeaderText(t("Введите коэффициенты от −1 до 1. Нулевая ячейка не соединяет каналы.",
                "Enter coefficients from −1 to 1. A zero cell leaves those channels unconnected."));
        matrixDialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        Label matrixError = label("", "danger");
        matrixError.setAccessibleText(t("Ошибка матрицы каналов", "Channel matrix error"));
        matrixError.setVisible(false); matrixError.setManaged(false);
        VBox matrixContent = new VBox(8, scroll, matrixError);
        matrixContent.setPadding(new Insets(4));
        matrixDialog.getDialogPane().setContent(matrixContent);
        styleDialog(matrixDialog);
        AtomicReference<ChannelMatrix> validatedMatrix = new AtomicReference<>();
        AtomicBoolean validationAttempted = new AtomicBoolean(false);
        Runnable validateMatrix = () -> {
            validatedMatrix.set(null);
            TextField invalidCell = null;
            String errorMessage = null;
            List<List<Double>> coefficients = new ArrayList<>();
            boolean anyNonZero = false;
            for (TextField[] row : cells) {
                List<Double> values = new ArrayList<>();
                for (TextField cell : row) {
                    try {
                        double coefficient = Double.parseDouble(cell.getText().trim());
                        if (!Double.isFinite(coefficient) || coefficient < -1.0 || coefficient > 1.0) {
                            invalidCell = cell;
                            errorMessage = t("Введите конечное число от −1 до 1.", "Enter a finite number from −1 to 1.");
                            break;
                        }
                        values.add(coefficient);
                        anyNonZero |= coefficient != 0.0;
                    } catch (NumberFormatException ignored) {
                        invalidCell = cell;
                        errorMessage = t("Введите число от −1 до 1.", "Enter a number from −1 to 1.");
                        break;
                    }
                }
                if (errorMessage != null) break;
                coefficients.add(values);
            }
            if (errorMessage == null && !anyNonZero) {
                invalidCell = cells[0][0];
                errorMessage = t("Оставьте хотя бы один ненулевой коэффициент.", "Keep at least one non-zero coefficient.");
            }
            if (errorMessage == null) {
                try {
                    ChannelMatrix matrix = ChannelMatrix.parse(DefaultConfigMap.map(
                            "inputs", List.copyOf(inputs), "outputs", List.copyOf(outputs), "coefficients", coefficients));
                    int boostedRow = -1;
                    for (int row = 0; row < matrix.coefficients().size(); row++) {
                        if (matrix.coefficients().get(row).stream().mapToDouble(Math::abs).sum() > 1.0 + 1e-9) {
                            boostedRow = row;
                            break;
                        }
                    }
                    if (!limiterAvailable && boostedRow >= 0) {
                        invalidCell = cells[boostedRow][0];
                        errorMessage = t("Сумма модулей коэффициентов выхода «" + outputs.get(boostedRow)
                                        + "» может усилить сигнал. Включите limiter или уменьшите коэффициенты.",
                                "The absolute gains for output '" + outputs.get(boostedRow)
                                        + "' can boost the signal. Enable the limiter or reduce those coefficients.");
                    } else validatedMatrix.set(matrix);
                } catch (IllegalArgumentException ignored) {
                    if (errorMessage == null) {
                        invalidCell = cells[0][0];
                        errorMessage = t("Проверьте размеры матрицы и значения коэффициентов.", "Check the matrix dimensions and coefficient values.");
                    }
                }
            }
            if (errorMessage == null) {
                matrixError.setText("");
                matrixError.setVisible(false); matrixError.setManaged(false);
                for (TextField[] row : cells) for (TextField cell : row) cell.setAccessibleHelp(null);
            } else {
                matrixError.setText(errorMessage);
                matrixError.setAccessibleText(errorMessage);
                matrixError.setVisible(true); matrixError.setManaged(true);
                if (invalidCell != null) {
                    invalidCell.setAccessibleHelp(errorMessage);
                    if (validationAttempted.get()) invalidCell.requestFocus();
                }
            }
        };
        javafx.scene.control.Button okButton = (javafx.scene.control.Button) matrixDialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            validationAttempted.set(true);
            validateMatrix.run();
            if (validatedMatrix.get() == null) event.consume();
        });
        for (TextField[] row : cells) for (TextField cell : row)
            cell.focusedProperty().addListener((observable, hadFocus, hasFocus) -> {
                if (validationAttempted.get() && hadFocus && !hasFocus) validateMatrix.run();
            });
        if (matrixDialog.showAndWait().filter(ButtonType.OK::equals).isEmpty()) return null;
        return validatedMatrix.get() == null ? null : new LinkedHashMap<>(validatedMatrix.get().toMap());
    }

    private List<String> routeMatrixBusPositions(Map<String, Object> config, String busId) {
        Map<String, Object> bus = maps(config.get("buses")).stream()
                .filter(candidate -> busId.equals(candidate.get("id"))).findFirst().orElse(null);
        return bus == null ? List.of() : matrixBusPositions(string(bus.get("channels"), "stereo"));
    }

    private List<String> routeMatrixEndpointPositions(Map<String, Object> config, Endpoint endpoint) {
        if (endpoint.bus()) return routeMatrixBusPositions(config, endpoint.id());
        AudioNode node = endpoint.node();
        if (node == null && "system_default".equals(endpoint.id()))
            node = snapshot.sinks().stream().filter(candidate -> candidate.id() == snapshot.defaultSinkId()).findFirst().orElse(null);
        return node == null ? List.of() : matrixStreamPositions(node.channels());
    }

    private static List<String> matrixBusPositions(String layout) {
        return switch (layout) {
            case "mono" -> List.of("MONO");
            case "5.1" -> List.of("FL", "FR", "FC", "LFE", "RL", "RR");
            case "7.1" -> List.of("FL", "FR", "FC", "LFE", "RL", "RR", "SL", "SR");
            default -> List.of("FL", "FR");
        };
    }

    private static List<String> matrixStreamPositions(int channels) {
        return switch (channels) {
            case 1 -> List.of("MONO");
            case 3 -> List.of("FL", "FR", "FC");
            case 4 -> List.of("FL", "FR", "RL", "RR");
            case 5 -> List.of("FL", "FR", "FC", "RL", "RR");
            case 6 -> List.of("FL", "FR", "FC", "LFE", "RL", "RR");
            case 7 -> List.of("FL", "FR", "FC", "LFE", "RL", "RR", "RC");
            case 8 -> List.of("FL", "FR", "FC", "LFE", "RL", "RR", "SL", "SR");
            default -> List.of("FL", "FR");
        };
    }

    private ComboBox<Endpoint> endpointCombo(List<Endpoint> endpoints) {
        ComboBox<Endpoint> combo = new ComboBox<>(); combo.getItems().setAll(endpoints);
        combo.setCellFactory(list -> new javafx.scene.control.ListCell<>() { @Override protected void updateItem(Endpoint item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : item.label()); } });
        combo.setButtonCell(new javafx.scene.control.ListCell<>() { @Override protected void updateItem(Endpoint item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : item.label()); } });
        return combo;
    }

    private void deleteRoute(String id) {
        updateRouteConfig(t("Удалить маршрут ", "Delete route ") + id, config -> {
            maps(config.get("routes")).removeIf(route -> id.equals(route.get("id")));
            for (Map<String, Object> profile : maps(config.get("profiles"))) profile.put("active_routes", stringList(profile.get("active_routes")).stream().filter(candidate -> !id.equals(candidate)).toList());
        });
    }

    private void addBus() { editBus(null); }
    private void addRoute(String busId, AudioNode sink) { editRoute(null); }
    private void addDefaultFilter() {
        updateConfig(config -> {
            Map<String, Object> dsp = map(config.get("dsp"));
            Map<String, Object> eq = map(dsp.get("global_eq"));
            List<Map<String, Object>> filters = maps(eq.get("filters"));
            filters.add(DefaultConfigMap.map("type", "notch", "frequency_hz", 80, "gain_db", -2.0, "q", 2.0, "enabled", true));
        });
    }
    private void createRouteChain() {
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
    private void deleteRouteChain(String id) {
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
    private void updateRouteChain(String id, Consumer<Map<String, Object>> change) {
        updateConfig(config -> {
            Object value = map(map(config.get("dsp")).get("route_chains")).get(id);
            if (value instanceof Map<?, ?>) change.accept(map(value));
        });
    }
    private static boolean isPassFilter(String type) { return "high_pass".equals(type) || "low_pass".equals(type); }

    private void editRouteFilter(String chainId, int index, Map<String, Object> existing) {
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
    private void updateFilter(int index, String key, Object value) {
        updateConfig(config -> { List<Map<String, Object>> filters = maps(map(map(config.get("dsp")).get("global_eq")).get("filters")); if (index >= 0 && index < filters.size()) filters.get(index).put(key, value); });
    }
    private void removeFilter(int index) {
        updateConfig(config -> { List<Map<String, Object>> filters = maps(map(map(config.get("dsp")).get("global_eq")).get("filters")); if (index >= 0 && index < filters.size()) filters.remove(index); });
    }
    private void setEqEnabled(boolean enabled) { updateConfig(config -> map(map(config.get("dsp")).get("global_eq")).put("enabled", enabled)); }
    private void activateProfile(String id) {
        Map<String,Object> config=store.snapshot();Map<String, Object> profile = maps(config.get("profiles")).stream().filter(p -> id.equals(p.get("id"))).findFirst().orElse(null);
        if (profile == null) return;
        updateConfig(updated -> updated.put("active_profile", id));
        AudioNode output=DeviceSelectorMatcher.resolve(snapshot.sinks(),string(profile.get("default_output"),"system_default"),maps(config.get("devices")));
        if(output!=null)backend.setDefaultOutput(output.id()).thenRun(this::refreshBackend);
    }
    private void copyProfile() {
        updateConfig(config -> {
            Map<String, Object> source = activeProfile(config); Map<String, Object> copy = copyMap(source);
            String id = "profile-" + UUID.randomUUID().toString().substring(0, 8); copy.put("id", id); copy.put("name", string(source.get("name"), "Profile") + " copy");
            maps(config.get("profiles")).add(copy);
        });
    }
    private void renameProfile(String id) {
        Map<String, Object> profile = maps(store.snapshot().get("profiles")).stream().filter(p -> id.equals(p.get("id"))).findFirst().orElse(null);
        if (profile == null) return;
        TextInputDialog dialog = new TextInputDialog(string(profile.get("name"), id));
        dialog.setTitle(t("Переименовать профиль", "Rename profile")); dialog.setHeaderText(null); dialog.setContentText(t("Имя", "Name"));
        dialog.showAndWait().map(String::trim).filter(name -> !name.isBlank()).ifPresent(name -> updateConfig(config -> {
            maps(config.get("profiles")).stream().filter(p -> id.equals(p.get("id"))).findFirst().ifPresent(p -> p.put("name", name));
        }));
    }
    private void deleteProfile(String id) {
        Map<String, Object> current = store.snapshot(); List<Map<String, Object>> profiles = maps(current.get("profiles"));
        if (profiles.size() <= 1) { setNotice(t("Нельзя удалить последний профиль.", "The last profile cannot be deleted."), true); return; }
        Map<String, Object> selected = profiles.stream().filter(p -> id.equals(p.get("id"))).findFirst().orElse(null); if (selected == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, t("Удалить профиль «", "Delete profile ") + string(selected.get("name"), id) + "?", javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        confirm.initOwner(getScene().getWindow()); confirm.setHeaderText(null);
        confirm.showAndWait().filter(button -> button == javafx.scene.control.ButtonType.OK).ifPresent(button -> {
            Map<String, Object> config = store.snapshot(); String replacement = id.equals(config.get("active_profile")) ? profiles.stream().filter(p -> !id.equals(p.get("id"))).map(p -> string(p.get("id"), "")).findFirst().orElse("") : null;
            maps(config.get("profiles")).removeIf(p -> id.equals(p.get("id")));
            if (replacement != null) config.put("active_profile", replacement);
            store.saveAsync(config).whenComplete((ignored, error) -> Platform.runLater(() -> {
                if (error != null) { setNotice(t("Конфигурация не сохранена: ", "Configuration was not saved: ") + rootMessage(error), true); return; }
                setNotice(t("Профиль удалён.", "Profile deleted."), false);
                if (replacement != null) {
                    String output = maps(config.get("profiles")).stream().filter(p -> replacement.equals(p.get("id"))).map(p -> string(p.get("default_output"), "system_default")).findFirst().orElse("system_default");
                    AudioNode target=DeviceSelectorMatcher.resolve(snapshot.sinks(),output,maps(config.get("devices")));
                    if(target!=null)backend.setDefaultOutput(target.id()).thenRun(this::refreshBackend);
                }
                showPage(Page.PROFILES);
            }));
        });
    }
    private void updateActiveProfile(String key, Object value) { updateConfig(config -> activeProfile(config).put(key, value)); }
    private void updateUiSetting(String key, String value) { updateConfig(config -> map(config.get("ui")).put(key, value)); if (key.equals("language")) showPage(currentPage); }
    private void updateConfig(Consumer<Map<String, Object>> change) {
        saveConfig(store.updateAsync(change));
    }
    private void updateLimiter(String key, Object value) {
        updateConfig(config -> {
            Map<String, Object> dsp = map(config.get("dsp"));
            Map<String, Object> limiter = map(dsp.get("limiter"));
            dsp.put("limiter", limiter);
            limiter.put(key, value);
        });
    }
    private static boolean limiterEnabled(Map<String, Object> config) {
        return Boolean.TRUE.equals(map(map(config.get("dsp")).get("limiter")).get("enabled"));
    }
    private void saveConfig(java.util.concurrent.CompletableFuture<Void> save) {
        save.whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) setNotice(t("Конфигурация не сохранена: ", "Configuration was not saved: ") + rootMessage(error), true);
            else { setNotice(t("Изменение сохранено в YAML.", "Change saved to YAML."), false); showPage(currentPage); }
        }));
    }

    private void saveConfig(Map<String, Object> config) {
        saveConfig(store.saveAsync(config));
    }

    private void styleDialog(Dialog<?> dialog) {
        if (getScene() == null) return;
        javafx.scene.control.DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().setAll(getScene().getStylesheets());
        pane.getStyleClass().add("app-shell");
        for (String theme : List.of("light-theme", "dark-theme"))
            if (getScene().getRoot().getStyleClass().contains(theme)) pane.getStyleClass().add(theme);
    }

    private void setNotice(String text, boolean error) {
        notice.setText(text); notice.getStyleClass().removeAll("error-banner", "warning", "accent");
        notice.getStyleClass().add(error ? "error-banner" : "status-pill"); notice.setPadding(new Insets(9, 22, 9, 22));
        notice.setVisible(true); notice.setManaged(true); body.setBottom(notice);
    }
    private VBox page(String subtitle) { VBox result = new VBox(18); result.getStyleClass().add("page-content"); result.getChildren().add(label(subtitle, "page-subtitle")); return result; }
    private VBox card(String header) { VBox result = new VBox(12); result.getStyleClass().add("card"); Label title = label(header, "card-title"); result.getChildren().add(title); return result; }
    private VBox metricCard(String name, String value, String detail) { VBox card = card(name); card.getChildren().addAll(label(value, "stat-value"), label(detail, "muted")); return card; }
    private VBox errorCard(String header, String details) { VBox card = card(header); card.getChildren().add(label(details, "danger")); return card; }
    private VBox routeNode(String header, String detail) {
        Label title = label(header, "card-title"); title.setMaxWidth(205);
        Label subtitle = label(detail, "muted"); subtitle.setMaxWidth(205);
        VBox node = new VBox(7, title, subtitle); node.setPrefWidth(220); node.setMaxWidth(220);
        node.getStyleClass().addAll("node-card", "route-node"); return node;
    }
    private Label label(String text, String style) { Label label = new Label(text); label.setWrapText(true); if (style != null && !style.isBlank()) label.getStyleClass().add(style); return label; }
    private Button actionButton(String text, Runnable action) { Button button = new Button(text); button.setMinHeight(36); button.setWrapText(true); button.setOnAction(event -> action.run()); button.setAccessibleText(text); return button; }
    private TextField field(String value, String accessible) { TextField f = new TextField(value); f.setAccessibleText(accessible); f.setPrefColumnCount(8); return f; }
    private javafx.scene.control.ListCell<AudioNode> nodeCell() { return new javafx.scene.control.ListCell<>() { @Override protected void updateItem(AudioNode item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : nodeDisplayName(item)); } }; }
    private void addWire(Pane pane, List<Line> wires, Node from, Node to) {
        Line line = new Line(); line.setStroke(javafx.scene.paint.Color.web("#268a98")); line.setStrokeWidth(2.0); line.setOpacity(0.78); line.setMouseTransparent(true);
        boolean sameColumn = Math.abs(from.getLayoutX() - to.getLayoutX()) < 1;
        line.setStartX(from.getLayoutX() + 220); line.setStartY(from.getLayoutY() + 48);
        line.setEndX(to.getLayoutX() + (sameColumn ? 220 : 0)); line.setEndY(to.getLayoutY() + 48);
        pane.getChildren().addFirst(line); wires.add(line);
    }
    private void makeDraggable(Node node, Pane pane, List<Line> wires) {
        final double[] start = new double[2];
        node.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> { start[0] = event.getSceneX() - node.getLayoutX(); start[1] = event.getSceneY() - node.getLayoutY(); });
        node.addEventHandler(MouseEvent.MOUSE_DRAGGED, event -> {
            Point2D point = pane.sceneToLocal(event.getSceneX(), event.getSceneY());
            node.setLayoutX(Math.max(0, Math.min(pane.getWidth() - node.getBoundsInParent().getWidth(), point.getX() - start[0])));
            node.setLayoutY(Math.max(0, Math.min(pane.getHeight() - 100, point.getY() - start[1])));
            // Lines are recomputed from nearby node geometry after each drag.
            for (Line line : wires) {
                if (Math.abs(line.getStartX() - (node.getLayoutX() + 220)) < 15) { line.setStartX(node.getLayoutX() + 220); line.setStartY(node.getLayoutY() + 48); }
                if (Math.abs(line.getStartX() - node.getLayoutX()) < 15) { line.setStartX(node.getLayoutX()); line.setStartY(node.getLayoutY() + 48); }
                if (Math.abs(line.getEndX() - (node.getLayoutX() + 220)) < 15) { line.setEndX(node.getLayoutX() + 220); line.setEndY(node.getLayoutY() + 48); }
                if (Math.abs(line.getEndX() - node.getLayoutX()) < 15) { line.setEndX(node.getLayoutX()); line.setEndY(node.getLayoutY() + 48); }
            }
        });
    }
    private AudioNode configuredSink() {
        Map<String,Object> config=store.snapshot();String reference=string(activeProfile(config).get("default_output"),"system_default");
        return DeviceSelectorMatcher.resolve(snapshot.sinks(),reference,maps(config.get("devices")));
    }
    private String outputReferenceName(Map<String,Object> config,String reference){if("system_default".equals(reference))return t("Системный выход","System default");for(Map<String,Object> device:maps(config.get("devices")))if(reference.equals(device.get("id")))return string(device.get("name"),string(map(device.get("selector")).get("value"),reference));return reference;}
    private Map<String, Object> activeProfile() { return activeProfile(store.snapshot()); }
    private static Map<String, Object> activeProfile(Map<String, Object> config) {
        String id = string(config.get("active_profile"), "desk");
        return maps(config.get("profiles")).stream().filter(p -> id.equals(p.get("id"))).findFirst().orElseGet(() -> maps(config.get("profiles")).stream().findFirst().orElseGet(LinkedHashMap::new));
    }
    private String pageLabel(Page page) {
        return switch (page) {
            case HOME -> t("Обзор", "Overview"); case APPLICATIONS -> t("Приложения", "Applications");
            case DEVICES -> t("Устройства", "Devices"); case ROUTES -> t("Маршруты", "Routes");
            case DSP -> t("DSP", "DSP"); case CALIBRATION -> t("Калибровка", "Calibration");
            case PROFILES -> t("Профили", "Profiles"); case SETTINGS -> t("Настройки и диагностика", "Settings & diagnostics");
        };
    }
    private String polarityLabel(String polarity) {
        return switch (polarity) {
            case "invert_left" -> t("левый канал", "left channel");
            case "invert_right" -> t("правый канал", "right channel");
            default -> t("все каналы", "all channels");
        };
    }
    private String polarityOptionLabel(String polarity) {
        return switch (polarity) {
            case "normal" -> t("Обычная", "Normal");
            case "invert" -> t("Инвертировать все", "Invert all");
            case "invert_left" -> t("Инвертировать левый", "Invert left");
            case "invert_right" -> t("Инвертировать правый", "Invert right");
            default -> polarity;
        };
    }
    private String t(String ru, String en) { return "en".equals(setting(store.snapshot(), "ui", "language", "ru")) ? en : ru; }
    public static String setting(Map<String, Object> config, String section, String key, String fallback) { return string(map(config.get(section)).get(key), fallback); }
    private List<AudioNode> filtered(List<AudioNode> nodes, String query) {
        if (query == null || query.isBlank()) return nodes;
        String q = query.toLowerCase(Locale.ROOT);
        return nodes.stream().filter(node -> (nodeDisplayName(node) + " " + node.description() + " " + node.name() + " " + node.applicationName() + " " + node.processName() + " " + node.applicationId()).toLowerCase(Locale.ROOT).contains(q)).toList();
    }
    private static boolean matchesSearch(String text, String query) { return query == null || query.isBlank() || text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return value instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>(); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) { return value instanceof List<?> l ? (List<Map<String, Object>>) l : new ArrayList<>(); }
    private static Map<String, Object> copyMap(Map<String, Object> value) { return new LinkedHashMap<>(value); }
    private static String string(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
    private static double number(Object value, double fallback) { return value instanceof Number n ? n.doubleValue() : fallback; }
    private static boolean finite(double value) { return Double.isFinite(value); }
    private static String volumeLabel(double volume) { return Double.isFinite(volume) ? Math.round(volume * 100) + "%" : "—"; }
    private static String rootMessage(Throwable error) {
        Throwable current = error; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
    private static String slug(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", ""); }
    private static int indexOf(List<Map<String, Object>> list, String id) { for (int i=0;i<list.size();i++) if (id.equals(list.get(i).get("id"))) return i; return -1; }
    private static boolean executable(String name) { return java.util.Arrays.stream(System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)).anyMatch(path -> java.nio.file.Files.isExecutable(java.nio.file.Path.of(path, name))); }

    private static final class DefaultConfigMap {
        static Map<String, Object> map(Object... pairs) { Map<String, Object> result = new LinkedHashMap<>(); for (int i=0;i<pairs.length;i+=2) result.put((String)pairs[i], pairs[i+1]); return result; }
    }
}
