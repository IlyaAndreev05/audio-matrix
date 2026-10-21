package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.ThemeManager;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireBackend;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;

abstract class AppShellUiPart04 extends AppShellUiPart04GraphCanvas {
    protected AppShellUiPart04(ConfigStore store, PipeWireBackend backend, ThemeManager themes) {
        super(store, backend, themes);
    }
}
