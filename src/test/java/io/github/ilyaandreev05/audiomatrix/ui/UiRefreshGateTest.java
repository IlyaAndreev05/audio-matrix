package io.github.ilyaandreev05.audiomatrix.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiRefreshGateTest {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<Runnable> fxQueue = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private String latestState = "initial";
    private String lastRenderedState = "";
    private int renders;

    private UiRefreshGate gate(boolean initiallyVisible) {
        UiRefreshGate gate = new UiRefreshGate(fxQueue::add, () -> {
            renders++;
            lastRenderedState = latestState;
            events.add("render");
        }, () -> events.add("pause"), () -> events.add("resume"), closed::get);
        if (!initiallyVisible) gate.setVisible(false);
        return gate;
    }

    private void pump() {
        List<Runnable> pending = List.copyOf(fxQueue);
        fxQueue.clear();
        pending.forEach(Runnable::run);
    }

    @Test
    void hiddenRequestsRenderNothingAndShowRendersLatestStateExactlyOnce() {
        UiRefreshGate gate = gate(false);
        gate.request();
        gate.request();
        latestState = "newest";
        pump();

        assertEquals(0, renders);
        assertTrue(gate.deferred());
        assertFalse(gate.visible());

        assertTrue(gate.setVisible(true));
        assertEquals(List.of("pause", "resume"), events);
        pump();

        assertEquals(1, renders);
        assertEquals("newest", lastRenderedState);
        assertEquals(List.of("pause", "resume", "render"), events);
        assertFalse(gate.deferred());

        pump();
        assertEquals(1, renders);
    }

    @Test
    void visibleRequestsCollapseIntoOnePendingCallback() {
        UiRefreshGate gate = gate(true);
        gate.request();
        latestState = "second";
        gate.request();
        gate.request();

        assertEquals(1, fxQueue.size());
        assertEquals(0, renders);
        pump();

        assertEquals(1, renders);
        assertEquals("second", lastRenderedState);
    }

    @Test
    void hideStopsMetersAndDropsTheQueuedRenderUntilTheNextShow() {
        UiRefreshGate gate = gate(true);
        gate.request();
        assertTrue(gate.setVisible(false));
        pump();

        assertEquals(0, renders);
        assertTrue(gate.deferred());
        assertEquals(List.of("pause"), events);

        gate.request();
        assertEquals(0, fxQueue.size());
        assertTrue(gate.setVisible(true));
        pump();

        assertEquals(1, renders);
        assertEquals(List.of("pause", "resume", "render"), events);
    }

    @Test
    void backgroundStartsHiddenAndOneShowRendersOnce() {
        UiRefreshGate gate = gate(false);
        assertFalse(gate.visible());
        assertEquals(List.of("pause"), events);

        gate.request();
        pump();
        assertEquals(0, renders);

        gate.setVisible(true);
        pump();
        assertEquals(1, renders);
        assertEquals(List.of("pause", "resume", "render"), events);
    }

    @Test
    void repeatedVisibilityNotificationsDoNothing() {
        UiRefreshGate gate = gate(true);
        assertFalse(gate.setVisible(true));
        assertTrue(gate.setVisible(false));
        assertFalse(gate.setVisible(false));
        assertTrue(gate.setVisible(true));
        pump();

        assertEquals(List.of("pause", "resume", "render"), events);
        assertEquals(1, renders);
    }

    @Test
    void closedShellIgnoresVisibilityAndPendingRenders() {
        UiRefreshGate gate = gate(true);
        gate.request();
        closed.set(true);

        assertFalse(gate.setVisible(false));
        assertFalse(gate.setVisible(true));
        gate.request();
        pump();

        assertEquals(0, renders);
        assertFalse(gate.visible());
        assertEquals(List.of(), events);
    }
}
