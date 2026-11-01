package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.gnome.GnomeControlBridge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class GnomeCommandConfirmationTest {
    @Test void runtimeEditsSkipSavedRulesAndHoldGuardThroughFreshObservationAndPublication() {
        for (GnomeControlBridge.Command command : runtimeCommands()) {
            Confirmation confirmation = new Confirmation();
            CompletableFuture<Void> result = confirmation.confirm(command);

            assertTrue(GnomeCommandConfirmation.runtimeStreamControl(command));
            assertEquals(List.of("refresh"), confirmation.events);
            assertFalse(result.isDone());
            confirmation.observation.complete(null);
            assertEquals(List.of("refresh", "publish"), confirmation.events);
            assertFalse(result.isDone());
            confirmation.publication.complete(null);
            assertTrue(result.isDone());
            result.join();
            assertEquals(List.of("refresh", "publish", "release"), confirmation.events);
            assertFalse(confirmation.savedRules.isDone());
        }
    }

    @Test void appAndProcessReleaseBeforeOneSavedRuleApplyAndWaitForPublication() {
        for (String target : List.of("app:org.player", "process:player")) {
            for (String name : List.of("set_app_volume", "toggle_app_mute")) {
                Confirmation confirmation = new Confirmation();
                GnomeControlBridge.Command command = new GnomeControlBridge.Command(name, target + "\t0.35");
                CompletableFuture<Void> result = confirmation.confirm(command);

                assertFalse(GnomeCommandConfirmation.runtimeStreamControl(command));
                assertEquals(List.of("release", "apply"), confirmation.events);
                assertFalse(result.isDone());
                confirmation.savedRules.complete(null);
                assertEquals(List.of("release", "apply", "publish"), confirmation.events);
                assertFalse(result.isDone());
                confirmation.publication.complete(null);
                assertTrue(result.isDone());
                result.join();
                assertEquals(List.of("release", "apply", "publish"), confirmation.events);
                assertFalse(confirmation.observation.isDone());
            }
        }
    }

    @Test void failedFreshObservationReleasesEveryRuntimeCommandWithoutApplyingOrPublishing() {
        for (GnomeControlBridge.Command command : runtimeCommands()) {
            Confirmation confirmation = new Confirmation();
            CompletableFuture<Void> result = confirmation.confirm(command);
            IllegalStateException failure = new IllegalStateException("Fresh observation failed");

            confirmation.observation.completeExceptionally(failure);
            assertFailure(result, failure);
            assertEquals(List.of("refresh", "release"), confirmation.events);
        }
    }

    @Test void failedPublicationReleasesEveryRuntimeCommandWithoutApplyingSavedRules() {
        for (GnomeControlBridge.Command command : runtimeCommands()) {
            Confirmation confirmation = new Confirmation();
            CompletableFuture<Void> result = confirmation.confirm(command);
            confirmation.observation.complete(null);
            IllegalStateException failure = new IllegalStateException("Publication failed");

            confirmation.publication.completeExceptionally(failure);
            assertFailure(result, failure);
            assertEquals(List.of("refresh", "publish", "release"), confirmation.events);
        }
    }

    @Test void synchronousRefreshOrPublicationFailureStillReleasesTheRuntimeGuardOnce() {
        for (GnomeControlBridge.Command command : runtimeCommands()) {
            for (boolean failPublication : List.of(false, true)) {
                Confirmation confirmation = new Confirmation();
                IllegalStateException failure = new IllegalStateException("Supplier failed");
                if (failPublication) confirmation.publicationFailure = failure;
                else confirmation.observationFailure = failure;

                CompletableFuture<Void> result = confirmation.confirm(command);
                if (failPublication) confirmation.observation.complete(null);
                assertFailure(result, failure);
                assertEquals(failPublication ? List.of("refresh", "publish", "release")
                        : List.of("refresh", "release"), confirmation.events);
            }
        }
    }

    @Test void savedRuleFailureDoesNotPublishOrReleaseTwice() {
        Confirmation confirmation = new Confirmation();
        CompletableFuture<Void> result = confirmation.confirm(new GnomeControlBridge.Command("set_app_volume", "app:org.player\t0.35"));
        IllegalStateException failure = new IllegalStateException("Saved-rule apply failed");

        confirmation.savedRules.completeExceptionally(failure);
        assertFailure(result, failure);
        assertEquals(List.of("release", "apply"), confirmation.events);
    }


    private static List<GnomeControlBridge.Command> runtimeCommands() {
        return List.of(new GnomeControlBridge.Command("set_stream_volume", "10\t0.35"),
                new GnomeControlBridge.Command("toggle_stream_mute", "10"),
                new GnomeControlBridge.Command("set_app_volume", "stream:music\t0.35"),
                new GnomeControlBridge.Command("toggle_app_mute", "stream:music"));
    }

    private static void assertFailure(CompletableFuture<Void> result, RuntimeException failure) {
        assertTrue(result.isDone());
        assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
    }

    private static final class Confirmation {
        final List<String> events = new ArrayList<>();
        final CompletableFuture<Void> savedRules = new CompletableFuture<>();
        final CompletableFuture<Void> observation = new CompletableFuture<>();
        final CompletableFuture<Void> publication = new CompletableFuture<>();
        RuntimeException observationFailure;
        RuntimeException publicationFailure;

        CompletableFuture<Void> confirm(GnomeControlBridge.Command command) {
            return GnomeCommandConfirmation.confirm(command, () -> {
                events.add("apply");
                return savedRules;
            }, () -> {
                events.add("refresh");
                if (observationFailure != null) throw observationFailure;
                return observation;
            }, () -> {
                events.add("publish");
                if (publicationFailure != null) throw publicationFailure;
                return publication;
            }, () -> events.add("release"));
        }
    }
}
