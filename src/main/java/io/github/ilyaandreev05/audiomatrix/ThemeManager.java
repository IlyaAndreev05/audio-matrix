package io.github.ilyaandreev05.audiomatrix;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.scene.Scene;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ThemeManager implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Process monitor;
    private volatile String preference = "system";
    private volatile Scene scene;

    public void apply(Scene scene, String preference) {
        this.scene = scene;
        this.preference = preference;
        applyResolved();
        if ("system".equals(preference) && monitor == null) Thread.ofVirtual().name("audio-matrix-theme-watch").start(this::watchSystemTheme);
    }
    private void applyResolved() {
        Scene target = scene;
        if (target == null) return;
        boolean dark = "dark".equals(preference) || ("system".equals(preference) && systemPrefersDark());
        Application.setUserAgentStylesheet(dark ? new PrimerDark().getUserAgentStylesheet() : new PrimerLight().getUserAgentStylesheet());
        target.getRoot().getStyleClass().removeAll("dark-theme", "light-theme");
        target.getRoot().getStyleClass().add(dark ? "dark-theme" : "light-theme");
    }
    private void watchSystemTheme() {
        while (!closed.get() && "system".equals(preference)) {
            try {
                monitor = new ProcessBuilder("gsettings", "monitor", "org.gnome.desktop.interface", "color-scheme").redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(monitor.getInputStream()))) {
                    while (!closed.get() && "system".equals(preference) && reader.readLine() != null)
                        javafx.application.Platform.runLater(this::applyResolved);
                }
                if (!closed.get() && "system".equals(preference)) Thread.sleep(1000);
            } catch (Exception e) {
                if (!closed.get()) try { Thread.sleep(5000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
    private static boolean systemPrefersDark() {
        try {
            Process p = new ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "color-scheme").redirectErrorStream(true).start();
            if (!p.waitFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return false; }
            return new String(p.getInputStream().readAllBytes()).contains("prefer-dark");
        } catch (Exception e) { return false; }
    }
    @Override public void close() { closed.set(true); if (monitor != null) monitor.destroyForcibly(); }
}
