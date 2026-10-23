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
    protected Timeline routeRefresh;
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
    protected boolean routesRequested;
    protected long routeHistoryRevision = -1;
    protected boolean routeUndoInProgress;
    protected boolean dspReferenceMode;
    protected boolean dspReferenceUpdateInProgress;
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
    }

    /** Attaches the shared browser transport before the shell or bridge starts. */
    protected final void initializeRuntime(BrowserSocketServer server) {
        if(runtimeInitialized)throw new IllegalStateException("Audio Matrix runtime was already initialized");
        browserSocketServer=server;
        browserAdapters=List.copyOf(BrowserAdapterRegistry.createAdapters(server));
        gnomeBridge=new GnomeControlBridge(this::executeGnomeCommand,browserAdapters,this::confirmGnomeCommandState);
        runtimeInitialized=true;
    }

    private CompletableFuture<Void> executeGnomeCommand(GnomeControlBridge.Command command) {
        gnomeCommandInFlight = true;
        return handleGnomeCommand(command);
    }

    /** Completes only after a fresh post-command observation is published ahead of its ACK. */
    private CompletableFuture<Void> confirmGnomeCommandState(GnomeControlBridge.Command command) {
        CompletableFuture<Void> reconcile = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                gnomeCommandInFlight = false;
                var config = store.snapshot();
                applyConfiguredDeviceRules(snapshot,config);
                applyConfiguredStreamRules(snapshot,config);
                reconcile.complete(null);
            } catch (RuntimeException failure) {
                reconcile.completeExceptionally(failure);
            }
        });
        return reconcile.thenCompose(ignored -> "open".equals(command.name())
                ? CompletableFuture.completedFuture(snapshot) : backend.refresh()).thenCompose(next -> {
            CompletableFuture<Void> published = new CompletableFuture<>();
            Platform.runLater(() -> {
                try {
                    if (next.capturedAtMillis() >= snapshot.capturedAtMillis()) snapshot = next;
                    publishGnomeState();
                    published.complete(null);
                } catch (RuntimeException failure) {
                    published.completeExceptionally(failure);
                }
            });
            return published;
        }).whenComplete((ignored,failure) -> gnomeCommandInFlight = false);
    }

    protected DynamicInstanceRegistry.ReconcileResult dynamicGraphState(String routeId) {
        return dynamicGraphStates.get(routeId);
    }

    protected void setDynamicGraphState(String routeId,DynamicInstanceRegistry.ReconcileResult result) {
        if(routeId==null||routeId.isBlank())throw new IllegalArgumentException("Route id is required");
        if(result==null)dynamicGraphStates.remove(routeId);
        else dynamicGraphStates.put(routeId,result);
        if(currentPage==Page.GRAPH)Platform.runLater(this::refreshGraphPageRuntime);
    }
}
