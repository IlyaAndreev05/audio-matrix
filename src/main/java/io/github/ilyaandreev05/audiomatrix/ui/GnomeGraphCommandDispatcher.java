package io.github.ilyaandreev05.audiomatrix.ui;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.config.VersionedConfig;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeCommandRevision;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeControlBridge;
import io.github.ilyaandreev05.audiomatrix.gnome.GnomeGraphOutputGain;
import io.github.ilyaandreev05.audiomatrix.graph.GraphProfileLibrary;
import javafx.application.Platform;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Dispatcher of the GNOME graph commands.
 *
 * <p>It serializes the commands through the persistent queue of the shell, applies the absolute
 * setters against the current config revision and keeps the accepted setters of every client.</p>
 */
final class GnomeGraphCommandDispatcher {
    private static final Set<String> GRAPH_OUTPUT_COMMANDS =
            Set.of("set_graph_output_volume", "toggle_graph_output_mute");
    private static final Set<String> V5_GRAPH_COMMANDS =
            Set.of("setNodeVolume", "setNodeMuted", "setRouteEnabled", "select_profile");

    private final AppShellUiPart31 shell;
    private final GnomePersistentCommandQueue gnomeCommands = new GnomePersistentCommandQueue();
    private final Map<GnomeNodeCommandHandler.PropertyKey, AcceptedSetter> acceptedSetters = new HashMap<>();
    private record AcceptedSetter(String clientId, long sequence, long revision, Object expectedProperty) {}

    GnomeGraphCommandDispatcher(AppShellUiPart31 shell) {
        this.shell = shell;
    }

    CompletableFuture<Void> handle(GnomeControlBridge.Command command,
            Function<GnomeControlBridge.Command, CompletableFuture<Void>> fallback) {
        // The presentation command never waits behind a queued audio setter.
        if ("open".equals(command.name())) return fallback.apply(command);
        return gnomeCommands.submit(sequence -> dispatchGnomeCommand(command, sequence, fallback));
    }

    private CompletableFuture<Void> dispatchGnomeCommand(GnomeControlBridge.Command command, long sequence,
            Function<GnomeControlBridge.Command, CompletableFuture<Void>> fallback) {
        if (GRAPH_OUTPUT_COMMANDS.contains(command.name())) {
            if (GnomeCommandRevision.isStale(command, shell.store.revision()))
                return CompletableFuture.failedFuture(new IllegalStateException("GNOME command is based on an old Audio Matrix revision"));
            return setGnomeGraphOutputLevel(command.value(), "toggle_graph_output_mute".equals(command.name()));
        }
        if (V5_GRAPH_COMMANDS.contains(command.name())) {
            try {
                long commandRevision = GnomeCommandRevision.commandRevision(command);
                if (GnomeCommandRevision.requiresExactRevision(command.name())
                        && commandRevision != shell.store.revision())
                    throw new IllegalStateException("GNOME command is based on an old Audio Matrix revision");
                GnomeNodeCommandHandler.Payload payload = GnomeNodeCommandHandler.parse(command.name(), command.value());
                if (payload instanceof GnomeNodeCommandHandler.SetVolume
                        || payload instanceof GnomeNodeCommandHandler.SetMuted)
                    return dispatchAbsoluteSetter(command, payload, commandRevision, sequence);
                if (payload instanceof GnomeNodeCommandHandler.SetRouteEnabled)
                    return executeNodeCommand(payload);
                if (payload instanceof GnomeNodeCommandHandler.SelectProfile profile)
                    return shell.updateGraphRuntimeThenStore(config ->
                            GraphProfileLibrary.activateProfile(config, profile.profileId()));
                throw new IllegalArgumentException("Unsupported graph command");
            } catch (RuntimeException invalid) {
                return CompletableFuture.failedFuture(invalid);
            }
        }
        return fallback.apply(command);
    }

    private CompletableFuture<Void> dispatchAbsoluteSetter(GnomeControlBridge.Command command,
            GnomeNodeCommandHandler.Payload payload, long commandRevision, long sequence) {
        GnomeNodeCommandHandler.PropertyKey key = GnomeNodeCommandHandler.propertyKey(payload);
        VersionedConfig current = shell.store.snapshot();
        long currentRevision = current.revision();
        Map<String, Object> baseline = shell.store.snapshotAtRevision(commandRevision);
        Object baselineProperty = baseline == null ? null : GnomeGraphNodeCommandExecutor.propertyValue(
                baseline, key, shell.dynamicGraphState(key.routeId()), shell.snapshot, shell.physicalSinks());
        Object currentProperty = GnomeGraphNodeCommandExecutor.propertyValue(
                current, key, shell.dynamicGraphState(key.routeId()), shell.snapshot, shell.physicalSinks());
        String clientId = GnomeCommandRevision.clientId(command);
        AcceptedSetter previous = acceptedSetters.get(key);
        boolean supersedes = isNewerSameClientIntent(sequence, commandRevision,
                previous, currentProperty, clientId);
        GnomeCommandRevision.requireAbsoluteSetterRevision(command, currentRevision, baseline != null,
                baselineProperty, currentProperty, supersedes);
        java.util.function.Consumer<Map<String, Object>> propertyGuard = latest -> {
            Object latestProperty = GnomeGraphNodeCommandExecutor.propertyValue(
                    latest, key, shell.dynamicGraphState(key.routeId()), shell.snapshot, shell.physicalSinks());
            long latestRevision = latest instanceof VersionedConfig versioned
                    ? versioned.revision() : shell.store.revision();
            boolean retained = shell.store.snapshotAtRevision(commandRevision) != null;
            boolean latestSupersedes = isNewerSameClientIntent(sequence, commandRevision,
                    previous, latestProperty, clientId);
            GnomeCommandRevision.requireAbsoluteSetterRevision(command, latestRevision, retained,
                    baselineProperty, latestProperty, latestSupersedes);
        };
        return executeNodeCommand(payload, propertyGuard).thenRun(() ->
                rememberAcceptedSetter(key, clientId, sequence,
                        GnomeNodeCommandHandler.intendedProperty(payload), currentProperty));
    }

    private static boolean isNewerSameClientIntent(long sequence, long commandRevision,
            AcceptedSetter previous, Object currentProperty, String clientId) {
        return !clientId.isBlank() && previous != null && clientId.equals(previous.clientId())
                && sequence > previous.sequence() && commandRevision < previous.revision()
                && Objects.equals(currentProperty, previous.expectedProperty());
    }

    private void rememberAcceptedSetter(GnomeNodeCommandHandler.PropertyKey key, String clientId,
            long sequence, Object intendedProperty, Object currentProperty) {
        long revision = shell.store.revision();
        acceptedSetters.entrySet().removeIf(entry -> revision - entry.getValue().revision() >= 128);
        acceptedSetters.put(key, new AcceptedSetter(clientId, sequence, revision,
                GnomeGraphNodeCommandExecutor.propertyAfter(currentProperty, intendedProperty)));
    }

    private CompletableFuture<Void> executeNodeCommand(GnomeNodeCommandHandler.Payload payload) {
        return executeNodeCommand(payload, null);
    }

    private CompletableFuture<Void> executeNodeCommand(GnomeNodeCommandHandler.Payload payload,
            java.util.function.Consumer<Map<String, Object>> propertyGuard) {
        String routeId = payload instanceof GnomeNodeCommandHandler.SetRouteEnabled route
                ? route.routeId() : GnomeNodeCommandHandler.propertyKey(payload).routeId();
        return GnomeGraphNodeCommandExecutor.execute(payload, shell.store, shell.backend, shell.snapshot, shell.physicalSinks(),
                shell.dynamicGraphState(routeId), shell.routeManager.active() && shell.snapshot.connected(), propertyGuard,
                candidate -> shell.applyRuntimeGraph(candidate, shell.snapshot, false, false, true),
                candidate -> shell.applyRuntimeGraph(candidate, shell.snapshot, false, false, false),
                this::applyGeneratedApplicationRules,
                this::rollbackGeneratedApplicationRules,
                error -> Platform.runLater(() -> shell.setNotice(shell.t("Настройка применена, но не сохранена: ",
                        "Runtime setting applied but could not be saved: ") + AppShellBase.rootMessage(error), true)),
                () -> Platform.runLater(() -> shell.setNotice(shell.t("Настройка применена.", "Setting applied."), false)));
    }

    private CompletableFuture<?> applyGeneratedApplicationRules(Map<String, Object> candidate) {
        return shell.streamRuleApplier.apply(shell.snapshot, AppShellBase.maps(candidate.get("applications")), Map.of(), false,
                (stream, error) -> applicationRuleFailure(stream, error));
    }

    private CompletableFuture<?> rollbackGeneratedApplicationRules(Map<String, Object> candidate) {
        return shell.streamRuleApplier.apply(shell.snapshot, AppShellBase.maps(candidate.get("applications")), Map.of(), true,
                (stream, error) -> applicationRuleFailure(stream, error));
    }

    private void applicationRuleFailure(AudioNode stream, Throwable error) {
        Platform.runLater(() -> shell.setNotice(shell.t("Не удалось применить правило приложения ",
                "Could not apply application rule for ") + shell.nodeDisplayName(stream) + ": "
                + AppShellBase.rootMessage(error), true));

    }

    private CompletableFuture<Void> setGnomeGraphOutputLevel(String value, boolean toggleMute) {
        String[] fields = value.split("\\t", -1);
        if (fields.length != (toggleMute ? 3 : 4)
                || fields[0].isBlank() || fields[1].isBlank() || fields[2].isBlank())
            return CompletableFuture.failedFuture(new IllegalArgumentException("Graph output reference is malformed"));
        Double level = null;
        if (!toggleMute) {
            try { level = Double.parseDouble(fields[3]); }
            catch (NumberFormatException invalid) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Graph output volume is invalid"));
            }
            if (!Double.isFinite(level))
                return CompletableFuture.failedFuture(new IllegalArgumentException("Graph output volume is invalid"));
        }
        GnomeGraphOutputGain.Target target = new GnomeGraphOutputGain.Target(fields[0], fields[1], fields[2]);
        Double requestedLevel = level;
        return shell.updateRouteConfigCommand(shell.t("Изменить уровень выхода маршрута", "Change graph output level"),
                config -> GnomeGraphOutputGain.set(config, target, requestedLevel, toggleMute));
    }
}
