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

abstract class AppShellState extends AppShellContract02 {
    protected final ConfigStore store;
    protected final PipeWireBackend backend;
    protected final PipeWireRouteManager routeManager = new PipeWireRouteManager();
    protected final ArrayDeque<RouteHistoryEntry> routeHistory = new ArrayDeque<>();
    protected final ArrayDeque<RouteHistoryEntry> routeRedoHistory = new ArrayDeque<>();
    protected final ThemeManager themes;
    protected GnomeControlBridge gnomeBridge;
    protected List<BrowserAdapter> browserAdapters=List.of();
    protected BrowserSocketServer browserSocketServer;
    private boolean runtimeInitialized;
    protected final ConcurrentMap<String,DynamicInstanceRegistry.ReconcileResult> dynamicGraphStates=new ConcurrentHashMap<>();
    protected final ExecutorService archiveIo = Executors.newSingleThreadExecutor(r -> { Thread thread = new Thread(r, "audio-matrix-config-archive"); thread.setDaemon(true); return thread; });
    protected final ExecutorService calibrationIo = Executors.newVirtualThreadPerTaskExecutor();
    /** Owns PipeWire-derived audio work; a JavaFX pulse can never stall a command acknowledgment. */
    protected final ScheduledExecutorService runtimeAudio = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "audio-matrix-runtime-audio"); thread.setDaemon(true); return thread;
    });
    protected final VBox sidebar = new VBox(8);
    protected final BorderPane body = new BorderPane();
    protected final Label title = new Label();
    protected final Label connection = new Label();
    protected final Label notice = new Label();
    protected Label outputMeterValue;
    protected Label inputMeterValue;
    protected AudioNode selectedInputSource;
    protected boolean inputMeterEnabled;
    protected final Timeline meterRefresh = new Timeline(new KeyFrame(Duration.millis(250), event -> refreshMeterLabel()));
    protected ScheduledFuture<?> routeRefresh;
    protected final TextField search = new TextField();
    protected final Map<Page, ToggleButton> navButtons = new LinkedHashMap<>();
    protected final ApplicationStreamRuleApplier streamRuleApplier;
    protected final ConcurrentMap<Integer, String> appliedDeviceRules = new ConcurrentHashMap<>();
    protected volatile PipeWireBackend.Snapshot snapshot = PipeWireBackend.Snapshot.offline("Waiting for PipeWire");
    protected Page currentPage = Page.HOME;
    protected AudioNode selectedSink;
    protected MeasurementPreview calibrationPreview;
    protected boolean calibrationPreviewSaved;
    protected ImpulseDecayPreview calibrationImpulsePreview;
    protected boolean calibrationImpulsePreviewSaved;
    protected Path calibrationRecordingPath;
    protected CalibrationComparisonPreview calibrationComparisonPreview;
    protected List<CalibrationFilterAdvisor.Suggestion> calibrationFilterSuggestions = List.of();
    protected boolean calibrationFilterPreviewShown;
    protected CalibrationFilterRollback calibrationFilterRollback;
    protected volatile CompletableFuture<MeasurementPreview> calibrationCaptureTask;
    protected CompletableFuture<Void> closeFuture;
    protected volatile boolean closing;
    protected boolean suppressConfigListener;
    protected volatile boolean gnomeCommandInFlight;
    private final AtomicReference<GnomeControlBridge.Command> gnomeAudioCommand = new AtomicReference<>();
    protected volatile long lastMixerCommandWriteMillis;
    /** Coalesced State publication lane; barriers complete only after the real publication. */
    private final GnomePublicationLane gnomePublications;
    protected long lastPublishedRevision = -1;
    protected long lastPublishedCapturedAtMillis = -1;
    /** Coalesces visible renders; a hidden window only records that a render is owed. */
    protected final UiRefreshGate uiRefreshGate;
    protected final AtomicBoolean forcedUiRender = new AtomicBoolean();
    protected final AtomicBoolean graphRuntimeDirty = new AtomicBoolean();
    protected String lastRenderedStructure = "";
    private volatile AudioNode dspReferenceSink;
    protected boolean routesRequested;
    protected long routeHistoryRevision = -1;
    protected boolean routeUndoInProgress;
    protected volatile boolean dspReferenceMode;
    protected volatile boolean dspReferenceUpdateInProgress;
    protected String selectedGraphRouteId = "";
    protected GraphPortRef pendingGraphOutput;
    protected final Set<String> selectedSubgraphNodeIds = ConcurrentHashMap.newKeySet();
    protected CheckBox dspReferenceToggle;
    protected boolean autoApplySuppressedUntilRestart;
    protected final RouteApplyGate routeApplyGate = new RouteApplyGate();
    protected long lastAppliedConnectionGeneration;
    protected int initializedSystemMixerSinkId = -1;

    protected AppShellState(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        this.store = store;
        this.backend = backend;
        this.themes = themes;
        this.streamRuleApplier = new ApplicationStreamRuleApplier(backend);
        this.gnomePublications = new GnomePublicationLane(
                () -> publishGnomeState(snapshot, store.snapshot()), () -> closing);
        this.uiRefreshGate = new UiRefreshGate(Platform::runLater, this::applyPendingUiRefresh,
                this::pauseUiMeters, this::resumeUiMeters, () -> closing);
    }

    /**
     * Tracks whether the shell window can draw. Hidden or iconified windows keep receiving
     * snapshots and audio work but do not rebuild page controls.
     */
    public void onStageVisibilityChanged(boolean visible) { uiRefreshGate.setVisible(visible); }

    /** Attaches the shared browser transport before the shell or bridge starts. */
    protected final void initializeRuntime(BrowserSocketServer server) {
        if(runtimeInitialized)throw new IllegalStateException("Audio Matrix runtime was already initialized");
        browserSocketServer=server;
        browserAdapters=List.copyOf(BrowserAdapterRegistry.createAdapters(server));
        gnomeBridge=new GnomeControlBridge(this::executeGnomeCommand,browserAdapters,this::confirmGnomeCommandState);
        runtimeInitialized=true;
    }

    private CompletableFuture<Void> executeGnomeCommand(GnomeControlBridge.Command command) {
        if ("open".equals(command.name())) return handleGnomeCommand(command);
        gnomeCommandInFlight = true;
        lastMixerCommandWriteMillis = Math.max(lastMixerCommandWriteMillis, System.currentTimeMillis());
        gnomeAudioCommand.set(command);
        return handleGnomeCommand(command);
    }

    /**
     * Completes only after the post-command state is published ahead of its acknowledgment.
     *
     * <p>Rules are re-applied from the {@code runtimeAudio} lane after clearing this command's own
     * in-flight marker, because the store listener may have skipped them while the command was
     * running. The returned future completes on a publisher barrier, never on enqueue and never on
     * a JavaFX hop, so a blocked render pulse cannot turn a real command into a timeout.</p>
     */
    private CompletableFuture<Void> confirmGnomeCommandState(GnomeControlBridge.Command command) {
        if ("open".equals(command.name())) return requestGnomeStatePublication(new CompletableFuture<>());
        return onRuntimeAudio(() -> {
            releaseGnomeAudioCommand(command);
            VersionedConfig config = store.snapshot();
            PipeWireBackend.Snapshot observation = snapshot;
            return applyConfiguredDeviceRules(observation, config)
                    .thenCompose(ignored -> applyConfiguredStreamRules(observation, config));
        }).thenCompose(ignored -> requestGnomeStatePublication(new CompletableFuture<>()))
          .whenComplete((ignored, failure) ->
                  lastMixerCommandWriteMillis = Math.max(lastMixerCommandWriteMillis, System.currentTimeMillis()));
    }

    private void releaseGnomeAudioCommand(GnomeControlBridge.Command command) {
        if (gnomeAudioCommand.compareAndSet(command, null)) gnomeCommandInFlight = false;
    }

    /** Runs audio work on the dedicated lane; a rejected lane simply drops the callback. */
    protected void runRuntimeAudio(Runnable work) { RuntimeAudioLane.run(runtimeAudio, () -> closing, work); }

    /** Runs one asynchronous audio operation on the lane and returns its own completion. */
    protected <T> CompletableFuture<T> onRuntimeAudio(java.util.function.Supplier<CompletableFuture<T>> work) {
        return RuntimeAudioLane.submit(runtimeAudio, () -> closing, work);
    }

    /** Marks the moment a local mixer write starts so an older observation cannot undo it. */
    protected void markMixerCommandWritten() {
        lastMixerCommandWriteMillis = Math.max(lastMixerCommandWriteMillis, System.currentTimeMillis());
    }

    protected void runOnFx(Runnable work) {
        if (closing) return;
        Platform.runLater(() -> { if (!closing) work.run(); });
    }

    /**
     * Queues one coalesced State publication. Snapshot-only requests collapse to the single
     * scheduled drain (which always reads the latest snapshot and config at execution time);
     * every confirmation barrier is completed only after that actual publication.
     */
    protected CompletableFuture<Void> requestGnomeStatePublication(CompletableFuture<Void> barrier) {
        return gnomePublications.request(barrier);
    }

    /** Stops the command, audio, and publication lanes without leaving a barrier hanging. */
    protected void shutdownRuntimeLanes() {
        gnomePublications.close();
        runtimeAudio.shutdownNow();
    }

    /** One coalesced JavaFX refresh; a level-only observation never rebuilds the page. */
    protected void requestUiRefresh() {
        uiRefreshGate.request();
    }

    /** Stops only UI indicators; audio processing, routes, bridge and recording keep running. */
    protected void pauseUiMeters() {
        meterRefresh.stop();
        backend.monitorOutput(null);
        backend.monitorInput(null);
    }

    /** Resumes meter polling and schedules the single render owed after a hidden period. */
    protected void resumeUiMeters() {
        if (closing) return;
        forcedUiRender.set(true);
        meterRefresh.play();
        updateMeterTarget();
    }

    private void applyPendingUiRefresh() {
        if (closing || !uiRefreshGate.visible()) return;
        if (forcedUiRender.getAndSet(false)) {
            showPage(currentPage);
            return;
        }
        PipeWireBackend.Snapshot current = snapshot;
        connection.setText(current.connected() ? t("PipeWire подключён", "PipeWire connected")
                : t("PipeWire недоступен", "PipeWire unavailable"));
        connection.getStyleClass().removeAll("accent", "danger", "warning");
        updateMeterTarget();
        VersionedConfig config = store.snapshot();
        String structure = uiStructureSignature(config, current);
        boolean structureChanged = !structure.equals(lastRenderedStructure);
        lastRenderedStructure = structure;
        boolean graphDirty = graphRuntimeDirty.getAndSet(false);
        if (structureChanged) {
            if (currentPage == Page.GRAPH) refreshGraphPageRuntime();
            else showPage(currentPage);
        } else if (graphDirty && currentPage == Page.GRAPH) refreshGraphPageRuntime();
    }

    protected String uiStructureSignature(VersionedConfig config, PipeWireBackend.Snapshot current) {
        return RouteInputSignature.create(config, current, backend.connectionGeneration()) + ":"
                + RouteInputSignature.dynamicBindings(current) + ":" + config.get("active_route") + ":"
                + config.get("active_profile");
    }

    protected void referenceSinkForDsp(AudioNode sink) { dspReferenceSink = sink; }

    protected AudioNode dspReferenceSink() { return dspReferenceSink; }

    protected DynamicInstanceRegistry.ReconcileResult dynamicGraphState(String routeId) {
        return dynamicGraphStates.get(routeId);
    }

    protected void setDynamicGraphState(String routeId,DynamicInstanceRegistry.ReconcileResult result) {
        if(routeId==null||routeId.isBlank())throw new IllegalArgumentException("Route id is required");
        if(result==null)dynamicGraphStates.remove(routeId);
        else dynamicGraphStates.put(routeId,result);
        graphRuntimeDirty.set(true);
        requestUiRefresh();
    }
}
