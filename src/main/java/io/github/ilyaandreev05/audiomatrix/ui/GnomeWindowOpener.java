package io.github.ilyaandreev05.audiomatrix.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.util.concurrent.CompletableFuture;

/** Shows the route editor when GNOME asks to open the background application. */
final class GnomeWindowOpener {
    private GnomeWindowOpener() {}

    static CompletableFuture<Void> open(Scene scene, Runnable selectEditor) {
        CompletableFuture<Void> opened = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                if (scene == null || !(scene.getWindow() instanceof Stage stage))
                    throw new IllegalStateException("Audio Matrix window is unavailable");
                stage.setIconified(false);
                stage.show();
                selectEditor.run();
                stage.toFront();
                stage.requestFocus();
                opened.complete(null);
            } catch (RuntimeException failure) {
                opened.completeExceptionally(failure);
            }
        });
        return opened;
    }
}
