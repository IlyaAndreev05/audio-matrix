package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;

public final class AppShell extends AppShellUiPart31 {
    public AppShell(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
        initializeShell();
    }
}
