package io.github.ilyaandreev05.audiomatrix.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GraphEqBandEditorTest {
    @BeforeAll static void toolkit() throws Exception {
        assumeTrue(System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null,
                "JavaFX interaction tests require a display");
        CountDownLatch ready = new CountDownLatch(1);
        try { Platform.startup(ready::countDown); }
        catch (IllegalStateException alreadyRunning) { ready.countDown(); }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    @Test void editsKeepUnfinishedTextAndNotifyValidityWithoutEnablingDisabledBands() throws Exception {
        onFx(() -> {
            GraphEqBandEditor editor = editor(List.of(band(100, false)));
            AtomicInteger changes = new AtomicInteger();
            editor.setOnChange(changes::incrementAndGet);
            List<TextField> fields = controls(editor, TextField.class);
            fields.getFirst().setText("150");
            assertEquals("150", fields.getFirst().getText());
            assertEquals(150.0, editor.bands().getFirst().get("frequency_hz"));
            assertEquals(false, editor.bands().getFirst().get("enabled"));
            fields.get(2).setText("");
            assertFalse(editor.valid());
            assertEquals("", fields.get(2).getText());
            fields.get(2).setText("2");
            assertTrue(editor.valid());
            fields.getFirst().setText("NaN");
            assertFalse(editor.valid());
            assertEquals(150.0, editor.bands().getFirst().get("frequency_hz"));
            assertTrue(changes.get() >= 4);
        });
    }

    @Test void addingDeletingAndSelectingCanvasPointsUseTheSameBandModel() throws Exception {
        onFx(() -> {
            GraphEqBandEditor editor = editor(List.of(band(100, true), band(1000, true)));
            List<Button> actions = controls(editor, Button.class);
            actions.getFirst().fire();
            assertEquals(3, editor.bands().size());
            actions.get(1).fire();
            assertEquals(2, editor.bands().size());
            GraphEqCanvas canvas = controls(editor, GraphEqCanvas.class).getFirst();
            double x = 42 + Math.log(1000.0 / 20) / Math.log(1000) * (600 - 42 - 12);
            canvas.getOnMousePressed().handle(mouse(MouseEvent.MOUSE_PRESSED, x, 107));
            assertEquals(1, controls(editor, ListView.class).getFirst().getSelectionModel().getSelectedIndex());
            assertEquals("1000", controls(editor, TextField.class).getFirst().getText());
            canvas.getOnMouseDragged().handle(mouse(MouseEvent.MOUSE_DRAGGED, x, 80));
            assertTrue(((Number) editor.bands().get(1).get("gain_db")).doubleValue() > 0);
            assertEquals(0, editor.bands().getFirst().get("gain_db"));
            canvas.getOnMouseReleased().handle(mouse(MouseEvent.MOUSE_RELEASED, x, 80));
            actions.get(1).fire(); actions.get(1).fire();
            assertTrue(editor.bands().isEmpty());
            assertTrue(controls(editor, TextField.class).stream().allMatch(Node::isDisabled));
        });
    }

    @Test void notchGainIsNotPresentedAsAnEffectiveDspParameter() throws Exception {
        onFx(() -> {
            GraphEqBandEditor editor = editor(List.of(band(100, true)));
            @SuppressWarnings("unchecked") ComboBox<String> type = controls(editor, ComboBox.class).getFirst();
            type.setValue("notch");
            assertTrue(controls(editor, TextField.class).get(1).isDisabled());
            controls(editor, CheckBox.class).getFirst().setSelected(false);
            assertEquals(false, editor.bands().getFirst().get("enabled"));
            type.setValue("high_shelf");
            assertFalse(controls(editor, TextField.class).get(1).isDisabled());
        });
    }

    private static GraphEqBandEditor editor(List<Map<String, Object>> bands) {
        return new GraphEqBandEditor(bands, (ru, en) -> en);
    }
    private static Map<String, Object> band(int hz, boolean enabled) {
        return Map.of("type", "peaking", "frequency_hz", hz, "gain_db", 0, "q", 1, "enabled", enabled);
    }
    private static <T> List<T> controls(GraphEqBandEditor editor, Class<T> type) {
        return descendants(editor.view()).filter(type::isInstance).map(type::cast).toList();
    }
    private static Stream<Node> descendants(Node node) {
        return Stream.concat(Stream.of(node), node instanceof Parent parent
                ? parent.getChildrenUnmodifiable().stream().flatMap(GraphEqBandEditorTest::descendants) : Stream.empty());
    }
    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, double x, double y) {
        return new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, true, null);
    }
    private static void onFx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }
}
