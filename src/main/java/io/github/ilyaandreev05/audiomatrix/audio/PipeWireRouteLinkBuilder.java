package io.github.ilyaandreev05.audiomatrix.audio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.BusPlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.Link;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.OutputPlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.Plan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.RoutePlan;
import io.github.ilyaandreev05.audiomatrix.audio.PipeWireRouteTypes.StreamPlan;

/** Pure port-to-link mapping for the independently reconciled audio branches. */
final class PipeWireRouteLinkBuilder {
    private PipeWireRouteLinkBuilder() {}

    static List<Link> bus(BusPlan bus, Map<String, List<String>> inputs,
                      Map<String, List<String>> outputs) throws IOException {
        // Only the session system input has a shared source: its monitor carries the sounds that no
        // application stream owns. Every application is served by its own generated instance, and the
        // control-plane generator is never a routing target.
        if (!io.github.ilyaandreev05.audiomatrix.graph.DynamicInstanceRegistry.SYSTEM_INPUT_BUS_ROLE
                .equals(bus.role())) return List.of();
        List<Link> links = PipeWireRouteManager.channelLinks(
                outputs.getOrDefault(PipeWireBackend.SYSTEM_MIXER_SINK_NAME, List.of()),
                inputs.getOrDefault(bus.nodeName(), List.of()), bus.channelLayout(), false);
        if (links.isEmpty()) throw new IOException("The virtual system input has no compatible bus ports");
        return links;
    }

    static List<Link> route(RoutePlan route, Plan plan, Map<String, List<String>> inputs,
                            Map<String, List<String>> outputs) throws IOException {
        BusPlan sourceBus = plan.buses().get(route.fromBusId());
        if (sourceBus == null) throw new IOException("Source bus is unavailable for route " + route.id());
        List<Link> links = new ArrayList<>();
        String sourceTarget = route.filters().isEmpty() ? route.captureNodeName() : route.filterInputNodeName();
        if (route.channelMatrix() != null) {
            List<Link> intoMatrix = requiredPositionLinks(outputs.getOrDefault(sourceBus.nodeName(), List.of()),
                    inputs.getOrDefault(route.matrixInputNodeName(), List.of()), route.channelMatrix().inputs(),
                    "Channel matrix input for route " + route.id());
            links.addAll(intoMatrix);
            List<Link> fromMatrix = requiredPositionLinks(outputs.getOrDefault(route.matrixOutputNodeName(), List.of()),
                    inputs.getOrDefault(sourceTarget, List.of()), route.positions(),
                    "Channel matrix output for route " + route.id());
            links.addAll(fromMatrix);
        } else {
            List<Link> intoRoute = PipeWireRouteManager.channelLinks(
                    outputs.getOrDefault(sourceBus.nodeName(), List.of()), inputs.getOrDefault(sourceTarget, List.of()),
                    route.channelMap(), true);
            if (intoRoute.isEmpty()) throw new IOException("No compatible source ports for route " + route.id());
            links.addAll(intoRoute);
        }
        if (!route.filters().isEmpty()) {
            List<Link> intoCapture = route.channelMatrix() == null
                    ? PipeWireRouteManager.channelLinks(outputs.getOrDefault(route.filterOutputNodeName(), List.of()),
                            inputs.getOrDefault(route.captureNodeName(), List.of()), route.channelMap(), false)
                    : requiredPositionLinks(outputs.getOrDefault(route.filterOutputNodeName(), List.of()),
                            inputs.getOrDefault(route.captureNodeName(), List.of()), route.positions(), "Route filter " + route.id());
            if (intoCapture.isEmpty()) throw new IOException("Route filter output is unavailable for " + route.id());
            links.addAll(intoCapture);
        }
        List<String> destination = route.targetBusId() == null
                ? outputPorts(route, plan, inputs)
                : inputs.getOrDefault(plan.buses().get(route.targetBusId()).nodeName(), List.of());
        List<Link> out = route.channelMatrix() == null
                ? PipeWireRouteManager.channelLinks(outputs.getOrDefault(route.playbackNodeName(), List.of()), destination,
                        route.channelMap(), true)
                : requiredPositionLinks(outputs.getOrDefault(route.playbackNodeName(), List.of()), destination,
                        route.positions(), destinationPositions(route, plan), "Route destination " + route.id());
        if (out.isEmpty()) throw new IOException("No compatible PipeWire ports from route " + route.id() + " to its destination");
        if (route.channelMatrix() == null && route.sink() != null && route.sink().channels() > 2
                && ("stereo".equals(route.channelMap()) || "swap_lr".equals(route.channelMap()))
                && out.size() != 2)
            throw new IOException("Route destination has incomplete stereo ports for " + route.id());
        links.addAll(out);
        return List.copyOf(new LinkedHashSet<>(links));
    }

    static List<Link> output(OutputPlan output, Map<String, List<String>> inputs,
                             Map<String, List<String>> outputs) throws IOException {
        List<Link> links = new ArrayList<>();
        if (!output.filters().isEmpty()) {
            String next = output.limiterFilters().isEmpty() ? output.sink().name() : output.limiterInputNodeName();
            List<Link> eq = outputLinks(output, outputs.getOrDefault(output.filterOutputNodeName(), List.of()),
                    inputs.getOrDefault(next, List.of()), "Output filter " + output.sink().name());
            links.addAll(eq);
        }
        if (!output.limiterFilters().isEmpty()) {
            List<Link> limiter = outputLinks(output, outputs.getOrDefault(output.limiterOutputNodeName(), List.of()),
                    inputs.getOrDefault(output.sink().name(), List.of()), "Output limiter " + output.sink().name());
            links.addAll(limiter);
        }
        return List.copyOf(links);
    }

    static List<Link> stream(StreamPlan stream, Plan plan, Map<String, List<String>> inputs,
                             Map<String, List<String>> outputs) throws IOException {
        BusPlan bus = plan.buses().get(stream.busId());
        if (bus == null) throw new IOException("Target bus is unavailable for stream " + stream.node().name());
        if (!stream.instanceKey().isEmpty() && stream.sourceNodeId().isBlank())
            throw new IOException("Captured stream has no explicit graph source node identity");
        String first = stream.filters().isEmpty() ? bus.nodeName() : stream.filterInputNodeName();
        List<Link> into = PipeWireRouteManager.channelLinks(outputs.getOrDefault(stream.node().name(), List.of()),
                inputs.getOrDefault(first, List.of()), stream.filters().isEmpty() ? bus.channelLayout() : "stereo", true);
        if (into.isEmpty()) throw new IOException("Stream ports do not match bus " + stream.busId());
        if (stream.filters().isEmpty()) return into;
        List<Link> filtered = PipeWireRouteManager.channelLinks(outputs.getOrDefault(stream.filterOutputNodeName(), List.of()),
                inputs.getOrDefault(bus.nodeName(), List.of()), bus.channelLayout(), true);
        if (filtered.isEmpty()) throw new IOException("Stream filter ports do not match bus " + stream.busId());
        List<Link> result = new ArrayList<>(into);
        result.addAll(filtered);
        return List.copyOf(result);
    }

    private static List<String> outputPorts(RoutePlan route, Plan plan,
                                            Map<String, List<String>> inputs) throws IOException {
        if (route.sink() == null) throw new IOException("Route " + route.id() + " has no resolved output");
        OutputPlan output = plan.outputs().get(route.sink().name());
        String node = output == null ? route.sink().name() : output.firstInputNodeName();
        List<String> ports = inputs.getOrDefault(node, List.of());
        if (ports.isEmpty()) throw new IOException("Output ports are unavailable for route " + route.id());
        return ports;
    }

    private static List<String> destinationPositions(RoutePlan route, Plan plan) {
        if (route.targetBusId() != null) return plan.buses().get(route.targetBusId()).positions();
        OutputPlan output = plan.outputs().get(route.sink().name());
        if (output != null) return output.positions();
        List<String> positions = PipeWireRouteManager.sinkPositions(route.sink());
        if (positions.isEmpty() && route.sink().channels() == 2
                && route.sink().identity() != null && route.sink().identity().channelPositions().isBlank()
                && List.of("FL", "FR").containsAll(route.positions()))
            return List.of("FL", "FR");
        return positions;
    }

    private static List<Link> outputLinks(OutputPlan output, List<String> outputs, List<String> inputs,
                                          String context) throws IOException {
        if (PipeWireRouteManager.sinkPositions(output.sink()).isEmpty() && output.sink().channels() <= 2) {
            List<Link> links = PipeWireRouteManager.channelLinks(outputs, inputs, "stereo", false);
            if (links.isEmpty()) throw new IOException(context + " ports are unavailable");
            return links;
        }
        return requiredPositionLinks(outputs, inputs, output.positions(), context);
    }

    private static List<Link> requiredPositionLinks(List<String> outputs, List<String> inputs,
                                                    List<String> positions, String context) throws IOException {
        return requiredPositionLinks(outputs, inputs, positions, positions, context);
    }

    private static List<Link> requiredPositionLinks(List<String> outputs, List<String> inputs,
                                                    List<String> positions, List<String> inputPositions,
                                                    String context) throws IOException {
        Map<String, String> source = uniqueNamedPorts(outputs, positions, context);
        Map<String, String> destination = uniqueNamedPorts(inputs, inputPositions, context);
        List<Link> links = new ArrayList<>();
        for (String position : positions) {
            if (!source.containsKey(position) || !destination.containsKey(position))
                throw new IOException(context + " has incomplete ports for " + position);
            links.add(new Link(source.get(position), destination.get(position)));
        }
        return links;
    }

    private static Map<String, String> uniqueNamedPorts(List<String> ports, List<String> positions,
                                                         String context) throws IOException {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String port : ports) {
            String leaf = port.substring(port.lastIndexOf(':') + 1);
            String position = leaf.substring(leaf.lastIndexOf('_') + 1).toUpperCase(java.util.Locale.ROOT);
            if (position.matches("[0-9]+")) {
                try {
                    int index = Integer.parseInt(position);
                    if (index >= positions.size()) continue;
                    position = positions.get(index);
                } catch (NumberFormatException ignored) { continue; }
            }
            if (!java.util.Set.of("FL", "FR", "FC", "LFE", "RL", "RR", "RC", "SL", "SR", "MONO").contains(position))
                continue;
            if (result.putIfAbsent(position, port) != null)
                throw new IOException(context + " has ambiguous duplicate ports for " + position);
        }
        return result;
    }
}
