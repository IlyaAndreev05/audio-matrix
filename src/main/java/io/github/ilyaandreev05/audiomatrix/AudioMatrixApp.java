package io.github.ilyaandreev05.audiomatrix;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.ConfigValidator;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCodec;
import io.github.ilyaandreev05.audiomatrix.graph.GraphPipeWireCompiler;
import io.github.ilyaandreev05.audiomatrix.gnome.BrowserSocketServer;
import io.github.ilyaandreev05.audiomatrix.ui.AppShell;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class AudioMatrixApp extends Application {
    private ConfigStore configStore;
    private PipeWireBackend backend;
    private ThemeManager themeManager;
    private AppShell shell;
    private InstanceControlServer instanceControl;
    private BrowserSocketServer browserSocketServer;
    private boolean closeInProgress;
    private boolean servicesClosed;

    public static void main(String[] args) {
        if (args.length > 0 && "--validate-config".equals(args[0])) {
            Path path = args.length > 1 ? Path.of(args[1]) : ConfigStore.defaultPath();
            validateConfig(path); System.exit(0); return;
        }
        if (args.length > 0 && "--export-default-config".equals(args[0])) {
            Path path = args.length > 1 ? Path.of(args[1]) : Path.of("config.example.json");
            exportDefaultConfig(path); System.exit(0); return;
        }
        if (args.length > 0 && "--diagnose-pipewire".equals(args[0])) { diagnosePipeWire(); System.exit(0); return; }
        if (args.length > 0 && "--install-octine-profiles".equals(args[0])) {
            runSetup(() -> io.github.ilyaandreev05.audiomatrix.config.OctineSetupCli.installProfiles(args.length > 1 ? Path.of(args[1]) : ConfigStore.defaultPath())); return;
        }
        if (args.length > 0 && "--replace-with-octine-profiles".equals(args[0])) {
            runSetup(() -> io.github.ilyaandreev05.audiomatrix.config.OctineSetupCli.replaceProfiles(args.length > 1 ? Path.of(args[1]) : ConfigStore.defaultPath())); return;
        }
        if (args.length > 2 && "--bind-device".equals(args[0])) {
            runSetup(() -> io.github.ilyaandreev05.audiomatrix.config.OctineSetupCli.bindDevice(args.length > 3 ? Path.of(args[3]) : ConfigStore.defaultPath(), args[1], args[2])); return;
        }
        if (args.length > 3 && "--upgrade-device".equals(args[0])) {
            runSetup(() -> io.github.ilyaandreev05.audiomatrix.config.OctineSetupCli.upgradeDevice(args.length > 4 ? Path.of(args[4]) : ConfigStore.defaultPath(), args[1], args[2], args[3])); return;
        }
        if (args.length > 2 && "--assign-slot".equals(args[0])) {
            runSetup(() -> io.github.ilyaandreev05.audiomatrix.config.OctineSetupCli.assignSlot(args.length > 3 ? Path.of(args[3]) : ConfigStore.defaultPath(), args[1], args[2])); return;
        }
        if (args.length > 1 && "--activate-profile".equals(args[0])) {
            runSetup(() -> io.github.ilyaandreev05.audiomatrix.config.OctineSetupCli.activateProfile(args.length > 2 ? Path.of(args[2]) : ConfigStore.defaultPath(), args[1])); return;
        }
        if (args.length > 0 && "--quit".equals(args[0])) {
            try { InstanceControlServer.requestQuitAndWait(Duration.ofSeconds(20)); }
            catch (Exception e) { System.err.println("Could not stop Audio Matrix cleanly: " + e.getMessage()); System.exit(1); }
            return;
        }
        if ("root".equals(System.getProperty("user.name"))) {
            System.err.println("Audio Matrix refuses to run as root. Start it in the logged-in desktop session.");
            System.exit(2);
        }
        boolean background = List.of(args).contains("--background");
        try {
            InstanceControlServer server = InstanceControlServer.acquireOrForward(background ? "background" : "open");
            if (server == null) {
                if (background) {
                    System.err.println("Audio Matrix is already running; stop the existing process before starting its background service.");
                    System.exit(3);
                }
                return;
            }
            currentInstanceControl = server;
            launch(args);
        } catch (Exception e) {
            if (currentInstanceControl != null) currentInstanceControl.close();
            System.err.println("Could not establish the Audio Matrix user-session instance: " + e.getMessage());
            System.exit(3);
        }
    }

    private static void validateConfig(Path path) {
        try {
            var mapper = mapperFor(path);
            Map<String, Object> config = mapper.readValue(path.toFile(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            int schema = config.get("schema_version") instanceof Number number ? number.intValue() : -1;
            if (schema < io.github.ilyaandreev05.audiomatrix.config.DefaultConfig.SCHEMA_VERSION) {
                ConfigStore.upgradedInMemory(config);
                System.out.println("Configuration schema " + schema + " migrates in memory to schema "
                        + io.github.ilyaandreev05.audiomatrix.config.DefaultConfig.SCHEMA_VERSION
                        + "; the file itself was not modified.");
            }
            List<String> errors = ConfigValidator.validate(config);
            if (errors.isEmpty()) {
                String activeRoute = String.valueOf(config.getOrDefault("active_route", ""));
                if (!activeRoute.isBlank()) {
                    List<?> routes = config.get("route_library") instanceof List<?> list ? list : List.of();
                    Map<String,Object> route = null;
                    for (Object candidate : routes) if (candidate instanceof Map<?,?> item && activeRoute.equals(item.get("id"))) {
                        Map<String,Object> selected = new java.util.LinkedHashMap<>();
                        for (Map.Entry<?,?> entry : item.entrySet()) selected.put(String.valueOf(entry.getKey()), entry.getValue());
                        route = selected;
                        break;
                    }
                    if (route == null) throw new IllegalArgumentException("The active route could not be found in route_library.");
                    GraphPipeWireCompiler.compile(GraphCodec.fromMap(route), config);
                }
                System.out.println("Configuration and active graph are valid: " + path.toAbsolutePath()); return;
            }
            errors.forEach(error -> System.err.println("- " + error)); System.exit(1);
        } catch (Exception e) { System.err.println("Configuration could not be validated: " + e.getMessage()); System.exit(1); }
    }

    private static void exportDefaultConfig(Path path) {
        try {
            var mapper = mapperFor(path);
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), io.github.ilyaandreev05.audiomatrix.config.DefaultConfig.create());
            System.out.println("Wrote default configuration: " + path.toAbsolutePath());
        } catch (Exception e) { System.err.println("Could not write default configuration: " + e.getMessage()); System.exit(1); }
    }

    private static void runSetup(SetupCommand command) {
        try { System.exit(command.run()); }
        catch (Exception error) { System.err.println("Octine setup failed: " + error.getMessage()); System.exit(1); }
    }
    @FunctionalInterface interface SetupCommand { int run() throws Exception; }
    private static com.fasterxml.jackson.databind.ObjectMapper mapperFor(Path path) {
        return path.getFileName().toString().endsWith(".yaml") || path.getFileName().toString().endsWith(".yml")
                ? new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                : new com.fasterxml.jackson.databind.ObjectMapper();
    }

    private static void diagnosePipeWire() {
        try (PipeWireBackend backend = new PipeWireBackend()) {
            PipeWireBackend.Snapshot snapshot = backend.refresh().get(10, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("PipeWire connected: " + snapshot.connected());
            if (!snapshot.connected()) { System.err.println(snapshot.error()); System.exit(1); return; }
            System.out.println("Default sink id: " + snapshot.defaultSinkId());
            System.out.println("Sinks:");
            for (var node : snapshot.sinks()) System.out.printf("- %d %s | volume=%s | muted=%s%n", node.id(), node.description(), Double.isFinite(node.volume()) ? String.format(java.util.Locale.ROOT, "%.3f", node.volume()) : "unknown", node.muted());
            System.out.println("Sources: " + snapshot.sources().size() + "; active streams: " + snapshot.streams().size());
        } catch (Exception e) { System.err.println("PipeWire diagnostic failed: " + e.getMessage()); System.exit(1); }
    }

    @Override public void start(Stage stage) throws Exception {
        Platform.setImplicitExit(false);
        configStore = new ConfigStore(ConfigStore.defaultPath());
        backend = new PipeWireBackend();
        try { browserSocketServer = BrowserSocketServer.start(); }
        catch (IOException | RuntimeException failure) {
            System.err.println("Chrome browser bridge is unavailable: " + failure.getMessage());
        }
        themeManager = new ThemeManager();
        shell = new AppShell(configStore, backend, themeManager, browserSocketServer);
        Scene scene = new Scene(shell, 1360, 860);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        String theme = AppShell.setting(configStore.snapshot(), "ui", "theme", "system");
        themeManager.apply(scene, theme);
        stage.setTitle("Audio Matrix");
        stage.setMinWidth(1024); stage.setMinHeight(680);
        stage.setScene(scene);
        boolean background = getParameters().getRaw().contains("--background");
        boolean openGraph = getParameters().getRaw().contains("--open-graph")
                || "1".equals(System.getenv("AUDIO_MATRIX_OPEN_GRAPH"));
        if (!background) stage.show();
        shell.start();
        if (openGraph) shell.openGraphEditor();
        instanceControl = currentInstanceControl;
        if (instanceControl != null) instanceControl.setHandler(command -> Platform.runLater(() -> {
            if ("open".equals(command)) {
                if (!stage.isShowing()) stage.show();
                stage.requestFocus();
                shell.openGraphEditor();
            } else if ("quit".equals(command)) {
                shutdownApplication();
            }
        }));
        stage.setOnCloseRequest(event -> {
            event.consume();
            stage.hide();
        });
    }

    private void shutdownApplication() {
        if (servicesClosed || closeInProgress) return;
        closeInProgress = true;
        shell.closeAsync().whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (error != null) System.err.println("Audio Matrix cleanup reported: " + error.getMessage());
            closeServicesAfterRoutes();
            Platform.exit();
        }));
    }

    private void closeServices() {
        if (servicesClosed) return;
        servicesClosed = true;
        if (shell != null) shell.close();
        if (themeManager != null) themeManager.close();
        if (backend != null) backend.close();
        if (browserSocketServer != null) browserSocketServer.close();
        if (configStore != null) configStore.close();
        if (instanceControl != null) instanceControl.close();
        else if (currentInstanceControl != null) currentInstanceControl.close();
        currentInstanceControl = null;
    }

    private void closeServicesAfterRoutes() {
        if (servicesClosed) return;
        servicesClosed = true;
        if (themeManager != null) themeManager.close();
        if (backend != null) backend.close();
        if (configStore != null) configStore.close();
        if (browserSocketServer != null) browserSocketServer.close();
        if (instanceControl != null) instanceControl.close();
        else if (currentInstanceControl != null) currentInstanceControl.close();
        currentInstanceControl = null;
    }
    @Override public void stop() { closeServices(); }

    private static volatile InstanceControlServer currentInstanceControl;
}
