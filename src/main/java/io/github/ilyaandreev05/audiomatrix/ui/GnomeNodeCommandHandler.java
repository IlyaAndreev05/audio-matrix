package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.config.ConfigStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/** Request decoding and async persistence for idempotent GNOME graph commands. */
final class GnomeNodeCommandHandler {
    private GnomeNodeCommandHandler() {}

    static CompletableFuture<Void> execute(String name, String value,
            Function<Consumer<Map<String, Object>>, CompletableFuture<Void>> apply) {
        String[] fields = value.split("\\t", -1);
        return switch (name) {
            case "setNodeVolume" -> volume(fields, apply);
            case "setNodeMuted" -> muted(fields, apply);
            case "setRouteEnabled" -> route(fields, apply);
            default -> CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported graph command"));
        };
    }

    private static CompletableFuture<Void> volume(String[] fields,
            Function<Consumer<Map<String, Object>>, CompletableFuture<Void>> apply) {
        if (fields.length != 3) return malformed("Node volume command is malformed");
        double value;
        try { value = Double.parseDouble(fields[2]); }
        catch (NumberFormatException invalid) { return malformed("Node volume is invalid"); }
        if (!Double.isFinite(value)) return malformed("Node volume is invalid");
        double target = Math.clamp(value, 0, 1);
        return apply.apply(config -> GraphNodeCommand.setVolume(config, fields[0], fields[1], target));
    }

    private static CompletableFuture<Void> muted(String[] fields,
            Function<Consumer<Map<String, Object>>, CompletableFuture<Void>> apply) {
        if (fields.length != 3 || !List.of("true", "false").contains(fields[2]))
            return malformed("Node mute command is malformed");
        return apply.apply(config -> GraphNodeCommand.setMuted(config, fields[0], fields[1], Boolean.parseBoolean(fields[2])));
    }

    private static CompletableFuture<Void> route(String[] fields,
            Function<Consumer<Map<String, Object>>, CompletableFuture<Void>> apply) {
        if (fields.length != 4 || !List.of("true", "false").contains(fields[3]))
            return malformed("Route state command is malformed");
        return apply.apply(config -> GraphNodeCommand.setRouteEnabled(config, fields[0], fields[1],
                fields[2], Boolean.parseBoolean(fields[3])));
    }

    private static CompletableFuture<Void> malformed(String message) {
        return CompletableFuture.failedFuture(new IllegalArgumentException(message));
    }

    static CompletableFuture<Void> applyRuntimeThenStore(ConfigStore store, Consumer<Map<String, Object>> change,
            boolean runtimeActive, Function<Map<String, Object>, CompletableFuture<?>> applyRuntime,
            Consumer<Throwable> saveFailed, Runnable saved) {
        Map<String, Object> candidate = store.snapshot();
        try { change.accept(candidate); }
        catch (RuntimeException invalid) { return CompletableFuture.failedFuture(invalid); }
        CompletableFuture<?> applied;
        try { applied = runtimeActive ? applyRuntime.apply(candidate) : CompletableFuture.completedFuture(null); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        return applied.thenRun(() -> store.updateAsync(change).whenComplete((ignored, error) -> {
            if (error == null) saved.run();
            else saveFailed.accept(error);
        }));
    }
}
