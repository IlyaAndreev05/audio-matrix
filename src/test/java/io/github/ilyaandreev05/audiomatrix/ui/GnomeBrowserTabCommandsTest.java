package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.gnome.BrowserAdapter;
import io.github.ilyaandreev05.audiomatrix.gnome.FirefoxBrowserAdapter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class GnomeBrowserTabCommandsTest {
    private static final String PAYLOAD = "{\"target\":{\"browser\":\"chrome\",\"instanceId\":\"instance-7\","
            + "\"profileId\":\"profile-2\",\"sessionToken\":\"session-9\"},\"muted\":true}";

    @Test void parsesExactBrowserSessionTargetAndBooleanMuteState() {
        GnomeBrowserTabCommands.Target target = new GnomeBrowserTabCommands.Target(
                "chrome", "instance-7", "profile-2", "session-9");
        assertEquals(new GnomeBrowserTabCommands.SetMuted(target, true), GnomeBrowserTabCommands.parse(PAYLOAD));
        assertEquals(new GnomeBrowserTabCommands.SetMuted(target, false),
                GnomeBrowserTabCommands.parse(PAYLOAD.replace("true", "false")));
    }

    @Test void rejectsDuplicateTrailingMissingUnknownAndWrongTypeFields() {
        for (String invalid : List.of("not-json", "", "null", "[]", "{}", PAYLOAD + " {}",
                PAYLOAD.replace("\"muted\":true", "\"muted\":true,\"muted\":false"),
                PAYLOAD.replace("\"browser\":\"chrome\"", "\"browser\":\"chrome\",\"browser\":\"firefox\""),
                PAYLOAD.replace("\"muted\":true", "\"muted\":\"true\""),
                PAYLOAD.replace("\"muted\":true", "\"muted\":1"),
                PAYLOAD.replace("\"muted\":true", "\"muted\":null"),
                PAYLOAD.replace(",\"muted\":true", ""),
                PAYLOAD.replace("\"muted\":true", "\"muted\":true,\"value\":0.5"),
                PAYLOAD.replace("\"browser\":\"chrome\"", "\"browser\":\"chrome\",\"tabId\":7"),
                "{\"target\":null,\"muted\":true}", "{\"target\":[],\"muted\":true}")) {
            assertThrows(IllegalArgumentException.class, () -> GnomeBrowserTabCommands.parse(invalid), invalid);
        }
        Map<String, String> fields = Map.of("browser", "chrome", "instanceId", "instance-7",
                "profileId", "profile-2", "sessionToken", "session-9");
        fields.forEach((field, value) -> {
            String original = "\"" + field + "\":\"" + value + "\"";
            for (String invalid : List.of("\"\"", "\"  \"", "null", "7", "true", "[]", "{}")) {
                String payload = PAYLOAD.replace(original, "\"" + field + "\":" + invalid);
                assertThrows(IllegalArgumentException.class, () -> GnomeBrowserTabCommands.parse(payload), payload);
            }
            String missing = PAYLOAD.replace(original + ",", "").replace("," + original, "");
            assertThrows(IllegalArgumentException.class, () -> GnomeBrowserTabCommands.parse(missing), missing);
        });
        assertThrows(IllegalArgumentException.class, () -> GnomeBrowserTabCommands.parse(null));
    }

    @Test void sendsOnlyToExactBrowserAndWaitsForItsRealAcknowledgement() {
        TestAdapter other = new TestAdapter("other");
        TestAdapter chrome = new TestAdapter("chrome");
        CompletableFuture<Void> result = GnomeBrowserTabCommands.setMuted(List.of(other, chrome), PAYLOAD);

        assertFalse(result.isDone());
        assertTrue(other.commands.isEmpty());
        assertEquals(1, chrome.commands.size());
        BrowserAdapter.BrowserCommand command = chrome.commands.getFirst();
        assertFalse(command.requestId().isBlank());
        assertEquals(new BrowserAdapter.BrowserCommand(command.requestId(), "chrome", "instance-7", "profile-2",
                "session-9", "mute", true), command);
        chrome.response.complete(new BrowserAdapter.BrowserCommandAck(command.requestId(), "ok", ""));
        result.join();

        GnomeBrowserTabCommands.setMuted(List.of(chrome), PAYLOAD.replace("true", "false")).join();
        BrowserAdapter.BrowserCommand second = chrome.commands.get(1);
        assertNotEquals(command.requestId(), second.requestId());
        assertEquals(Boolean.FALSE, second.muted());
    }

    @Test void propagatesBrowserErrorAndUnavailableCommandFailures() {
        TestAdapter rejected = new TestAdapter("chrome");
        CompletableFuture<Void> result = GnomeBrowserTabCommands.setMuted(List.of(rejected), PAYLOAD);
        rejected.response.complete(new BrowserAdapter.BrowserCommandAck("request", "error", "Tab is unavailable."));
        assertEquals("Tab is unavailable.", assertThrows(CompletionException.class, result::join).getCause().getMessage());

        TestAdapter disconnected = new TestAdapter("chrome");
        CompletableFuture<Void> unavailable = GnomeBrowserTabCommands.setMuted(List.of(disconnected), PAYLOAD);
        IOException failure = new IOException("Browser disconnected.");
        disconnected.response.completeExceptionally(failure);
        assertSame(failure, assertThrows(CompletionException.class, unavailable::join).getCause());

        assertThrows(CompletionException.class, () -> GnomeBrowserTabCommands.setMuted(List.of(), PAYLOAD).join());
        CompletableFuture<Void> firefox = GnomeBrowserTabCommands.setMuted(List.of(new FirefoxBrowserAdapter()),
                PAYLOAD.replace("chrome", "firefox"));
        assertThrows(CompletionException.class, firefox::join);
    }

    @Test void malformedPayloadNeverReachesAnAdapterAndOnlyOkAcknowledgementsSucceed() {
        TestAdapter adapter = new TestAdapter("chrome");
        assertThrows(CompletionException.class, () -> GnomeBrowserTabCommands.setMuted(List.of(adapter), "{}").join());
        assertTrue(adapter.commands.isEmpty());

        for (BrowserAdapter.BrowserCommandAck ack : List.of(
                new BrowserAdapter.BrowserCommandAck("request", "pending", ""),
                new BrowserAdapter.BrowserCommandAck("request", "error", ""))) {
            TestAdapter invalid = new TestAdapter("chrome");
            invalid.response.complete(ack);
            assertThrows(CompletionException.class,
                    () -> GnomeBrowserTabCommands.setMuted(List.of(invalid), PAYLOAD).join());
        }
        TestAdapter noAck = new TestAdapter("chrome");
        noAck.response.complete(null);
        assertThrows(CompletionException.class, () -> GnomeBrowserTabCommands.setMuted(List.of(noAck), PAYLOAD).join());
    }

    private static final class TestAdapter implements BrowserAdapter {
        private final String id;
        private final List<BrowserCommand> commands = new ArrayList<>();
        private final CompletableFuture<BrowserCommandAck> response = new CompletableFuture<>();

        private TestAdapter(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public boolean matches(String applicationId, String process, String applicationName) { return false; }
        @Override public Map<String, Object> capabilities(int streamCount, boolean capturedTabStream) { return Map.of(); }
        @Override public CompletableFuture<BrowserCommandAck> command(BrowserCommand command) {
            commands.add(command);
            return response;
        }
    }
}
