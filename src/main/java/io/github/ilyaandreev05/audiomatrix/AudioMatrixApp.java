package io.github.ilyaandreev05.audiomatrix;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import io.github.ilyaandreev05.audiomatrix.config.ConfigValidator;
import io.github.ilyaandreev05.audiomatrix.ui.AppShell;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.application.Platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class AudioMatrixApp extends Application {
    private ConfigStore configStore;
    private PipeWireBackend backend;
    private ThemeManager themeManager;
    private AppShell shell;
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
        if ("root".equals(System.getProperty("user.name"))) {
            System.err.println("Audio Matrix refuses to run as root. Start it in the logged-in desktop session.");
            System.exit(2);
        }
        launch(args);
    }

    private static void validateConfig(Path path) {
        try {
            var mapper = mapperFor(path);
            Map<String, Object> config = mapper.readValue(path.toFile(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            List<String> errors = ConfigValidator.validate(config);
            if (errors.isEmpty()) { System.out.println("Configuration is valid: " + path.toAbsolutePath()); return; }
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
        configStore = new ConfigStore(ConfigStore.defaultPath());
        backend = new PipeWireBackend();
        themeManager = new ThemeManager();
        shell = new AppShell(configStore, backend, themeManager);
        Scene scene = new Scene(shell, 1360, 860);
        scene.getStylesheets().add(getClass().getResource("/styles/app.css").toExternalForm());
        String theme = AppShell.setting(configStore.snapshot(), "ui", "theme", "system");
        themeManager.apply(scene, theme);
        stage.setTitle("Audio Matrix");
        stage.setMinWidth(1024); stage.setMinHeight(680);
        stage.setScene(scene);
        stage.show();
        shell.start();
        stage.setOnCloseRequest(event -> {
            if (servicesClosed) return;
            event.consume();
            if (closeInProgress) return;
            closeInProgress = true;
            shell.closeAsync().whenComplete((ignored, error) -> Platform.runLater(() -> {
                closeServicesAfterRoutes();
                stage.close();
            }));
        });
    }

    private void closeServices() {
        if (servicesClosed) return;
        servicesClosed = true;
        if (shell != null) shell.close();
        if (themeManager != null) themeManager.close();
        if (backend != null) backend.close();
        if (configStore != null) configStore.close();
    }

    private void closeServicesAfterRoutes() {
        if (servicesClosed) return;
        servicesClosed = true;
        if (themeManager != null) themeManager.close();
        if (backend != null) backend.close();
        if (configStore != null) configStore.close();
    }
    @Override public void stop() { closeServices(); }
}
