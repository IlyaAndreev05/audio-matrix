package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;

import io.github.ilyaandreev05.audiomatrix.gnome.BrowserSocketServer;

public final class AppShell extends AppShellUiPart31 {
    public AppShell(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        this(store,backend,themes,null);
    }

    public AppShell(ConfigStore store, PipeWireBackend backend, ThemeManager themes, BrowserSocketServer browserSocketServer) {
        super(store, backend, themes);
        initializeRuntime(browserSocketServer);
        initializeShell();
    }
}
