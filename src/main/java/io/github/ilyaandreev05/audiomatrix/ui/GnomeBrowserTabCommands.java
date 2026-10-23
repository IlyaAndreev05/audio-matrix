package io.github.ilyaandreev05.audiomatrix.ui;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.gnome.BrowserAdapter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Strict API v5 browser-tab setters backed by the connected browser's acknowledgement. */
final class GnomeBrowserTabCommands {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private GnomeBrowserTabCommands() {}

    record Target(String browser, String instanceId, String profileId, String sessionToken) {}
    record SetMuted(Target target, boolean muted) {}

    static CompletableFuture<Void> setMuted(AppShellUiPart29 shell, String payload) {
        return setMuted(shell.browserAdapters, payload);
    }

    static CompletableFuture<Void> setMuted(List<BrowserAdapter> adapters, String payload) {
        try {
            SetMuted request = parse(payload);
            Target target = request.target();
            BrowserAdapter adapter = adapters.stream().filter(item -> target.browser().equals(item.id()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("The requested browser adapter is unavailable."));
            BrowserAdapter.BrowserCommand command = new BrowserAdapter.BrowserCommand(UUID.randomUUID().toString(),
                    target.browser(), target.instanceId(), target.profileId(), target.sessionToken(), "mute", request.muted());
            CompletableFuture<BrowserAdapter.BrowserCommandAck> result = adapter.command(command);
            if (result == null) throw new IllegalStateException("The browser adapter returned no command completion.");
            return result.thenApply(ack -> {
                if (ack == null || !"ok".equals(ack.status())) {
                    String error = ack == null ? null : ack.error();
                    throw new IllegalStateException(error == null || error.isBlank()
                            ? "The browser did not confirm the mute command." : error);
                }
                return null;
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    static SetMuted parse(String payload) {
        try {
            JsonNode root = exactObject(JSON.readTree(payload), Set.of("target", "muted"), "Browser mute command");
            JsonNode target = exactObject(root.get("target"),
                    Set.of("browser", "instanceId", "profileId", "sessionToken"), "Browser tab target");
            JsonNode muted = root.get("muted");
            if (!muted.isBoolean()) throw new IllegalArgumentException("Browser mute state must be a JSON boolean");
            return new SetMuted(new Target(requiredString(target, "browser"), requiredString(target, "instanceId"),
                    requiredString(target, "profileId"), requiredString(target, "sessionToken")), muted.booleanValue());
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Browser mute command JSON is malformed", invalid);
        }
    }

    private static JsonNode exactObject(JsonNode value, Set<String> fields, String context) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException(context + " must be a JSON object");
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) throw new IllegalArgumentException(context + " has missing or unsupported fields");
        return value;
    }

    private static String requiredString(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank())
            throw new IllegalArgumentException(field + " must be a non-empty string");
        return value.textValue();
    }
}
