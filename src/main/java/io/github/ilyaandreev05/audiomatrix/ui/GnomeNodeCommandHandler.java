package io.github.ilyaandreev05.audiomatrix.ui;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;

/** Strict API v5 request decoding and confirmed runtime/config persistence for graph commands. */
final class GnomeNodeCommandHandler {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private GnomeNodeCommandHandler() {}

    sealed interface Payload permits SetVolume, SetMuted, SetRouteEnabled, SelectProfile {}
    record Target(String routeId, String nodeId, String instanceKey) {}
    record SetVolume(Target target, double value) implements Payload {}
    record SetMuted(Target target, boolean muted) implements Payload {}
    record SetRouteEnabled(String routeId, String switchNodeId, String linkId, boolean enabled) implements Payload {}
    record SelectProfile(String profileId) implements Payload {}
    record PropertyKey(String routeId, String nodeId, String instanceKey, String property) {}

    static PropertyKey propertyKey(Payload payload) {
        if (payload instanceof SetVolume volume)
            return new PropertyKey(volume.target().routeId(), volume.target().nodeId(),
                    volume.target().instanceKey(), "volume");
        if (payload instanceof SetMuted muted)
            return new PropertyKey(muted.target().routeId(), muted.target().nodeId(),
                    muted.target().instanceKey(), "muted");
        return null;
    }

    static Object intendedProperty(Payload payload) {
        if (payload instanceof SetVolume volume) return volume.value();
        if (payload instanceof SetMuted muted) return muted.muted();
        throw new IllegalArgumentException("Command is not an absolute node setter");
    }
    static String encodeVolume(Target target, double value) {
        validateTarget(target);
        if (!Double.isFinite(value) || value < 0 || value > 1)
            throw new IllegalArgumentException("Node volume must be between 0 and 1");
        return encode(Map.of("target", targetMap(target), "value", value));
    }

    static String encodeMuted(Target target, boolean muted) {
        validateTarget(target);
        return encode(Map.of("target", targetMap(target), "muted", muted));
    }

    private static Map<String, Object> targetMap(Target target) {
        return Map.of("routeId", target.routeId(), "nodeId", target.nodeId(),
                "instanceKey", target.instanceKey());
    }

    private static void validateTarget(Target target) {
        if (target == null || target.routeId() == null || target.routeId().isBlank()
                || target.nodeId() == null || target.nodeId().isBlank() || target.instanceKey() == null)
            throw new IllegalArgumentException("Node target is malformed");
    }

    private static String encode(Map<String, Object> payload) {
        try { return JSON.writeValueAsString(payload); }
        catch (Exception error) { throw new IllegalStateException("Could not encode graph command JSON", error); }
    }

    static Payload parse(String name, String value) {
        try {
            JsonNode root = JSON.readTree(value);
            return switch (name) {
                case "select_profile" -> {
                    JsonNode object = exactObject(root, Set.of("profileId"), "Profile command");
                    yield new SelectProfile(requiredString(object, "profileId", false));
                }
                case "setNodeVolume" -> {
                    JsonNode object = exactObject(root, Set.of("target", "value"), "Node volume command");
                    JsonNode target = exactObject(object.get("target"),
                            Set.of("routeId", "nodeId", "instanceKey"), "Node target");
                    JsonNode volume = object.get("value");
                    if (!volume.isNumber()) throw malformed("Node volume must be a JSON number");
                    double number = volume.doubleValue();
                    if (!Double.isFinite(number) || number < 0 || number > 1)
                        throw malformed("Node volume must be between 0 and 1");
                    yield new SetVolume(target(target), number);
                }
                case "setNodeMuted" -> {
                    JsonNode object = exactObject(root, Set.of("target", "muted"), "Node mute command");
                    JsonNode target = exactObject(object.get("target"),
                            Set.of("routeId", "nodeId", "instanceKey"), "Node target");
                    JsonNode muted = object.get("muted");
                    if (!muted.isBoolean()) throw malformed("Node mute state must be a JSON boolean");
                    yield new SetMuted(target(target), muted.booleanValue());
                }
                case "setRouteEnabled" -> {
                    JsonNode object = exactObject(root,
                            Set.of("routeId", "switchNodeId", "linkId", "enabled"), "Route state command");
                    JsonNode enabled = object.get("enabled");
                    if (!enabled.isBoolean()) throw malformed("Route state must be a JSON boolean");
                    yield new SetRouteEnabled(requiredString(object, "routeId", false),
                            requiredString(object, "switchNodeId", false),
                            requiredString(object, "linkId", false), enabled.booleanValue());
                }
                default -> throw new IllegalArgumentException("Unsupported graph command");
            };
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Graph command JSON is malformed", invalid);
        }
    }

    private static Target target(JsonNode target) {
        return new Target(requiredString(target, "routeId", false),
                requiredString(target, "nodeId", false),
                requiredString(target, "instanceKey", true));
    }

    private static JsonNode exactObject(JsonNode value, Set<String> fields, String context) {
        if (value == null || !value.isObject()) throw malformed(context + " must be a JSON object");
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) throw malformed(context + " has missing or unsupported fields");
        return value;
    }

    private static String requiredString(JsonNode object, String field, boolean allowEmpty) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || (!allowEmpty && value.textValue().isBlank()))
            throw malformed(field + " must be a " + (allowEmpty ? "string" : "non-empty string"));
        return value.textValue();
    }

    private static IllegalArgumentException malformed(String message) {
        return new IllegalArgumentException(message);
    }


    static CompletableFuture<Void> applyRuntimeThenStore(ConfigStore store, Consumer<Map<String, Object>> change,
            boolean runtimeActive, Function<Map<String, Object>, CompletableFuture<?>> applyRuntime,
            Consumer<Throwable> saveFailed, Runnable saved) {
        return applyRuntimeThenStore(store, change, runtimeActive, applyRuntime, applyRuntime, saveFailed, saved);
    }

    static CompletableFuture<Void> applyRuntimeThenStore(ConfigStore store, Consumer<Map<String, Object>> change,
            boolean runtimeActive, Function<Map<String, Object>, CompletableFuture<?>> applyRuntime,
            Function<Map<String, Object>, CompletableFuture<?>> applyRollback,
            Consumer<Throwable> saveFailed, Runnable saved) {
        Map<String, Object> previous = store.snapshot();
        try { change.accept(previous); }
        catch (RuntimeException invalid) { return CompletableFuture.failedFuture(invalid); }
        CompletableFuture<?> applied;
        try {
            applied = runtimeActive ? applyRuntime.apply(previous) : CompletableFuture.completedFuture(null);
            if (applied == null)
                applied = CompletableFuture.failedFuture(new IllegalStateException("Runtime apply returned no completion result"));
        } catch (RuntimeException failure) {
            applied = CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<Void> work = applied.thenCompose(ignored -> store.updateAsync(change)
                .whenComplete((savedConfig, error) -> {
                    if (error != null) notifyFailure(saveFailed, unwrap(error));
                })).thenRun(() -> notifySaved(saved));
        return work.handle((ignored, error) -> error).thenCompose(error -> {
            if (error == null) return CompletableFuture.completedFuture(null);
            Throwable failure = unwrap(error);
            if (!runtimeActive) return CompletableFuture.failedFuture(failure);
            CompletableFuture<?> rollback;
            try {
                rollback = applyRollback.apply(store.snapshot());
                if (rollback == null) throw new IllegalStateException("Runtime rollback returned no completion result");
            } catch (RuntimeException rollbackError) {
                failure.addSuppressed(rollbackError);
                return CompletableFuture.failedFuture(failure);
            }
            return rollback.handle((ignored, rollbackError) -> {
                if (rollbackError != null) failure.addSuppressed(unwrap(rollbackError));
                throw new CompletionException(failure);
            });
        });
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static void notifyFailure(Consumer<Throwable> callback, Throwable error) {
        try { callback.accept(error); } catch (RuntimeException ignored) { }
    }

    private static void notifySaved(Runnable callback) {
        try { callback.run(); } catch (RuntimeException ignored) { }
    }
}
