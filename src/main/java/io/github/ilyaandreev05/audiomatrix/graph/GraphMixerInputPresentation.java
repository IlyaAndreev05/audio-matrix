package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.DeviceDisplayNames;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Read-only input labels and liveness shared by the desktop and GNOME Mixer controls. */
public final class GraphMixerInputPresentation {
    /** The label of a generated input whose owner cannot be resolved: never a technical port id. */
    public static final String UNAVAILABLE_APPLICATION = "Приложение недоступно";
    private static final String STATIC_FALLBACK = "Вход микшера";

    private GraphMixerInputPresentation() {}

    /** One Mixer input as a panel may show it. */
    public record Input(String name, boolean available) {}

    /**
     * Resolves the human label and the command liveness of one Mixer input.
     *
     * <p>Labels follow the saved topology, so a disabled or offline connection keeps the name of its
     * upstream source instead of falling back to the port id. A generated input is interactive only
     * while its exact stable port, registered instance, live binding, and enabled boundary
     * connection all still exist in the effective graph; the port prefix alone proves nothing.</p>
     *
     * @param effective the reconciled graph published to the panels, or the saved graph when no
     *                  runtime discovery exists yet
     * @param mixer     the Mixer node that owns the ports; only its id is used here
     * @param config    the configuration that holds saved topology and device aliases
     * @param instances the current runtime instances of the route, empty when unknown
     * @param connected whether PipeWire is currently connected
     */
    public static Input describe(AudioGraph effective, MixerNode mixer, String port, Map<String, Object> config,
            Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> instances,
            boolean connected) {
        String mixerId = mixer == null ? "" : mixer.id();
        AudioGraph saved = savedGraph(config, effective == null ? "" : effective.id());
        GraphNode source = source(saved, mixerId, port);
        if (source == null) source = source(effective, mixerId, port);
        boolean generated = DynamicInstanceRegistry.isGeneratedMixerInputPort(port);
        String name = name(config, source, generated);
        if (!generated) return new Input(name, connected);
        DynamicInstanceRegistry.RuntimeInstance owner = owner(instances, port);
        String owned = ownerName(owner);
        if (!owned.isBlank()) name = owned;
        boolean available = connected && owner != null && owner.available() && owner.bindingAvailable()
                && applicationsGenerator(saved, effective, owner.generatorId())
                && boundary(effective, mixerId, port, owner.nodeIds().values());
        return new Input(name, available);
    }

    private static String name(Map<String, Object> config, GraphNode source, boolean generated) {
        if (source instanceof OutputNode output)
            return DeviceDisplayNames.displayName(config, output.deviceId(), output.name());
        if (source instanceof DeviceSlotNode slot)
            return DeviceDisplayNames.displayName(config, slot.deviceId(), slot.name());
        if (generated) {
            String label = friendlyGeneratedName(source);
            return label.isBlank() ? UNAVAILABLE_APPLICATION : label;
        }
        return DeviceDisplayNames.displayLabel(source == null ? "" : source.name(), STATIC_FALLBACK);
    }

    /**
     * A retained generated node is named after its instance key, so a technical prefix is never a
     * label: the caller then falls back to {@link #UNAVAILABLE_APPLICATION} instead of a raw key.
     */
    private static String friendlyGeneratedName(GraphNode source) {
        if (source == null) return "";
        String label = DeviceDisplayNames.displayLabel(source.name(), "");
        for (String prefix : List.of("app:", "process:", "stream:", "device:", "session:", "tab:",
                "browser:", "metadata:", "generated-"))
            if (label.startsWith(prefix)) return "";
        return label;
    }

    /** The saved upstream node of a port; the edge stays informative even while it is disabled. */
    private static GraphNode source(AudioGraph graph, String mixerId, String port) {
        if (graph == null || mixerId.isBlank() || port == null) return null;
        GraphConnection edge = graph.connections().stream()
                .filter(link -> mixerId.equals(link.toNode()) && port.equals(link.toPort()))
                .findFirst().orElse(null);
        GraphNode source = edge == null ? null : node(graph, edge.fromNode());
        if (source instanceof GainNode gain && !gain.endpointNodeId().isBlank()) {
            GraphNode endpoint = node(graph, gain.endpointNodeId());
            if (endpoint != null) source = endpoint;
        }
        return source;
    }

    private static DynamicInstanceRegistry.RuntimeInstance owner(
            Map<DynamicInstanceRegistry.InstanceAddress, DynamicInstanceRegistry.RuntimeInstance> instances,
            String port) {
        if (port == null || instances == null) return null;
        for (DynamicInstanceRegistry.RuntimeInstance instance : instances.values())
            if (port.equals(DynamicInstanceRegistry.stableMixerInputPortId(
                    instance.generatorId(), instance.instanceKey()))) return instance;
        return null;
    }

    private static String ownerName(DynamicInstanceRegistry.RuntimeInstance owner) {
        if (owner == null) return "";
        return firstHuman(owner.overrides().get("display_name"),
                owner.runtimeBinding().get("applicationName"),
                owner.runtimeBinding().get("processName"));
    }

    private static String firstHuman(Object... values) {
        for (Object value : values) {
            if (!(value instanceof String text)) continue;
            String label = DeviceDisplayNames.displayLabel(text, "");
            if (!label.isBlank()) return label;
        }
        return "";
    }

    private static boolean applicationsGenerator(AudioGraph saved, AudioGraph effective, String generatorId) {
        return applicationsGenerator(saved, generatorId) || applicationsGenerator(effective, generatorId);
    }

    private static boolean applicationsGenerator(AudioGraph graph, String generatorId) {
        if (graph == null || generatorId == null) return false;
        GraphNode generator = node(graph, generatorId);
        return generator instanceof DynamicGeneratorNode dynamic && "applications".equals(dynamic.registry());
    }

    private static boolean boundary(AudioGraph effective, String mixerId, String port, Collection<String> ownedNodes) {
        if (effective == null || mixerId.isBlank() || port == null || ownedNodes.isEmpty()) return false;
        for (GraphConnection edge : effective.connections())
            if (edge.enabled() && mixerId.equals(edge.toNode()) && port.equals(edge.toPort())
                    && ownedNodes.contains(edge.fromNode())) return true;
        return false;
    }

    /** The saved topology of a route: generated children and links never belong here. */
    private static AudioGraph savedGraph(Map<String, Object> config, String routeId) {
        if (config == null || routeId == null || routeId.isBlank()) return null;
        try {
            if (GraphCommonVibro.COMMON_ROUTE_ID.equals(routeId)) return GraphCommonVibro.graph(config);
            if (!(config.get("route_library") instanceof List<?> routes)) return null;
            for (Object item : routes) {
                if (!(item instanceof Map<?, ?> route) || !routeId.equals(route.get("id"))) continue;
                @SuppressWarnings("unchecked") Map<String, Object> encoded = (Map<String, Object>) route;
                return GraphCodec.fromMap(encoded);
            }
        } catch (RuntimeException invalid) {
            // A route that no longer decodes keeps no name and no interactive port.
        }
        return null;
    }

    private static GraphNode node(AudioGraph graph, String id) {
        if (graph == null) return null;
        for (GraphNode candidate : graph.nodes()) if (candidate.id().equals(id)) return candidate;
        return null;
    }
}
