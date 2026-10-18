package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;
import io.github.ilyaandreev05.audiomatrix.graph.GraphCommonVibro;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Applies explicitly requested Audio Matrix routing by owning PipeWire loopback nodes and links. */

abstract class PipeWireRoutePart02 extends PipeWireRoutePart01 {
    protected ApplyReport rebuildPipeWireGraph(Plan plan, String signature, int transitionMs,
                                             int fadeOutMs, int fadeInMs,
                                             Map<String, List<Link>> originalBeforeApply) throws Exception {
        try {
            if (active && appliedPlan != null) fadeRoutePlan(appliedPlan, true, fadeOutMs);
            // Preserve each stream's pre-Audio-Matrix destinations before replacing any links.
            String linkListing = run(List.of("pw-link", "-l"), COMMAND_TIMEOUT);
            for (StreamPlan stream : plan.streams())
                originalStreamLinks.computeIfAbsent(stream.node().name(), ignored -> linksFromNode(linkListing, stream.node().name()));
            Map<String, List<Link>> rollbackLinks = deepLinks(originalStreamLinks);
            originalBeforeApply.putAll(rollbackLinks);

            stopOwnedProcesses();
            disconnectOwnedLinks();
            desiredLinks.clear();

            Set<String> requiredBusIds = new LinkedHashSet<>();
            plan.routes().forEach(route -> {
                requiredBusIds.add(route.fromBusId());
                if (route.targetBusId() != null) requiredBusIds.add(route.targetBusId());
            });
            for (String busId : requiredBusIds) {
                BusPlan bus = plan.buses().get(busId);
                if (bus == null) throw new IOException("Missing bus " + busId + " in active route plan");
                startBus(bus);
            }
            for (RoutePlan route : plan.routes()) {
                startRoute(route);
                if (route.channelMatrix() != null) startChannelMatrix(route);
                if (!route.filters().isEmpty()) startFilterChain(route);
            }
            for (OutputPlan output : plan.outputs().values()) {
                if (!output.filters().isEmpty()) startFilterChain(output);
                if (!output.limiterFilters().isEmpty()) startOutputLimiter(output);
            }
            for (StreamPlan stream : plan.streams()) if (!stream.filters().isEmpty()) startFilterChain(stream);

            // Apply independent level and mute controls to the bus and route nodes.
            for (String busId : requiredBusIds) {
                BusPlan bus = plan.buses().get(busId);
                int nodeId = findNodeId(bus.nodeName());
                run(List.of("wpctl", "set-volume", Integer.toString(nodeId), Double.toString(bus.volume())), COMMAND_TIMEOUT);
                run(List.of("wpctl", "set-mute", Integer.toString(nodeId), bus.muted() ? "1" : "0"), COMMAND_TIMEOUT);
            }
            for (RoutePlan route : plan.routes()) {
                int nodeId = findNodeId(route.playbackNodeName());
                double volume = effectiveRoutePlaybackVolume(plan, route);
                boolean muted = effectiveRoutePlaybackMuted(plan, route);
                double initialVolume = fadeInMs > 0 || muted ? 0.0 : volume;
                run(List.of("wpctl", "set-volume", Integer.toString(nodeId), Double.toString(initialVolume),
                        "--limit", Double.toString(MixLevelMath.maxRouteVolume())), COMMAND_TIMEOUT);
                run(List.of("wpctl", "set-mute", Integer.toString(nodeId), muted ? "1" : "0"), COMMAND_TIMEOUT);
            }

            Map<String, List<String>> inputs = ports("-i");
            Map<String, List<String>> outputs = ports("-o");
            refreshObservedLinks();
            BusPlan mainBus = plan.buses().get(GraphCommonVibro.MAIN_SOURCE_ID);
            if (mainBus != null) {
                List<Link> systemCopy = channelLinks(outputs.getOrDefault(PipeWireBackend.SYSTEM_MIXER_SINK_NAME, List.of()),
                        inputs.getOrDefault(mainBus.nodeName(), List.of()), mainBus.channelLayout(), false);
                if (systemCopy.isEmpty()) throw new IOException("The virtual system input is unavailable; Main Mixer has no application source");
                connectAll(systemCopy);
            }
            for (RoutePlan route : plan.routes()) {
                BusPlan sourceBus = plan.buses().get(route.fromBusId());
                String sourceTarget = route.filters().isEmpty() ? route.captureNodeName() : route.filterInputNodeName();
                if (route.channelMatrix() != null) {
                    List<Link> intoMatrix = positionLinks(outputs.getOrDefault(sourceBus.nodeName(), List.of()),
                            inputs.getOrDefault(route.matrixInputNodeName(), List.of()));
                    if (intoMatrix.size() != route.channelMatrix().inputs().size())
                        throw new IOException("Channel matrix input ports are incomplete for route " + route.id());
                    connectAll(intoMatrix);
                    List<Link> fromMatrix = positionLinks(outputs.getOrDefault(route.matrixOutputNodeName(), List.of()),
                            inputs.getOrDefault(sourceTarget, List.of()));
                    if (fromMatrix.size() != route.positions().size())
                        throw new IOException("Channel matrix output ports are incomplete for route " + route.id());
                    connectAll(fromMatrix);
                } else {
                    List<Link> intoRoute = channelLinks(
                            outputs.getOrDefault(sourceBus.nodeName(), List.of()),
                            inputs.getOrDefault(sourceTarget, List.of()),
                            route.channelMap(), true);
                    connectAll(intoRoute);
                }
                if (!route.filters().isEmpty()) {
                    List<Link> intoDelay = route.channelMatrix() == null
                            ? channelLinks(outputs.getOrDefault(route.filterOutputNodeName(), List.of()),
                                    inputs.getOrDefault(route.captureNodeName(), List.of()), route.channelMap(), false)
                            : positionLinks(outputs.getOrDefault(route.filterOutputNodeName(), List.of()),
                                    inputs.getOrDefault(route.captureNodeName(), List.of()));
                    connectAll(intoDelay);
                }

                List<String> destinationPorts;
                if (route.targetBusId() != null) {
                    BusPlan targetBus = plan.buses().get(route.targetBusId());
                    destinationPorts = inputs.getOrDefault(targetBus.nodeName(), List.of());
                } else {
                    OutputPlan output = plan.outputs().get(route.sink().name());
                    destinationPorts = output == null ? inputs.getOrDefault(route.sink().name(), List.of())
                            : inputs.getOrDefault(output.firstInputNodeName(), List.of());
                }
                List<Link> outOfRoute = route.channelMatrix() == null
                        ? channelLinks(outputs.getOrDefault(route.playbackNodeName(), List.of()), destinationPorts,
                                route.channelMap(), true)
                        : positionLinks(outputs.getOrDefault(route.playbackNodeName(), List.of()), destinationPorts);
                if (outOfRoute.isEmpty()) throw new IOException("No compatible PipeWire ports from route " + route.id() + " to its destination");
                connectAll(outOfRoute);
            }

            for (OutputPlan output : plan.outputs().values()) {
                if (!output.filters().isEmpty()) {
                    String nextInput = output.limiterFilters().isEmpty() ? output.sink().name() : output.limiterInputNodeName();
                    connectAll(channelLinks(outputs.getOrDefault(output.filterOutputNodeName(), List.of()),
                            inputs.getOrDefault(nextInput, List.of()), "stereo", false));
                }
                if (!output.limiterFilters().isEmpty()) connectAll(channelLinks(
                        outputs.getOrDefault(output.limiterOutputNodeName(), List.of()),
                        inputs.getOrDefault(output.sink().name(), List.of()), "stereo", false));
            }

            for (StreamPlan stream : plan.streams()) {
                BusPlan targetBus = plan.buses().get(stream.busId());
                List<Link> previous = originalStreamLinks.getOrDefault(stream.node().name(), List.of());
                String firstTarget = stream.filters().isEmpty() ? targetBus.nodeName() : stream.filterInputNodeName();
                List<Link> intoBus = channelLinks(
                        outputs.getOrDefault(stream.node().name(), List.of()),
                        inputs.getOrDefault(firstTarget, List.of()),
                        stream.filters().isEmpty() ? targetBus.channelLayout() : "stereo", true);
                List<Link> fromFilter = stream.filters().isEmpty() ? List.of() : channelLinks(
                        outputs.getOrDefault(stream.filterOutputNodeName(), List.of()),
                        inputs.getOrDefault(targetBus.nodeName(), List.of()), targetBus.channelLayout(), true);
                if (intoBus.isEmpty() || (!stream.filters().isEmpty() && fromFilter.isEmpty())) {
                    skippedStreamCount++;
                    transitionWarnings.add("Skipped stream " + stream.node().name()
                            + " because its PipeWire ports do not match bus " + stream.busId());
                    continue;
                }
                disconnectAll(previous);
                connectAll(intoBus);
                if (!stream.filters().isEmpty())
                    connectAll(fromFilter);
            }

            Set<String> activeStreamNames = new HashSet<>();
            plan.streams().forEach(stream -> activeStreamNames.add(stream.node().name()));
            for (String name : new ArrayList<>(originalStreamLinks.keySet())) {
                if (activeStreamNames.contains(name)) continue;
                restoreOriginal(name);
                originalStreamLinks.remove(name);
            }
            confirmDesiredLinks();
            if (fadeInMs > 0) fadeRoutePlan(plan, false, fadeInMs);
            active = true;
            appliedSignature = signature;
            appliedPlan = plan;
            activeTransitionMs = transitionMs;
            return reportAppliedPlan(plan);
        } catch (Exception error) {
            disconnectOwnedLinks();
            stopOwnedProcesses();
            // Keep newly captured originals as well as streams managed by the previous plan.
            originalStreamLinks.clear();
            originalStreamLinks.putAll(originalBeforeApply);
            restoreAllOriginal();
            observedLinks.clear();
            skippedStreamCount = 0;
            active = false;
            appliedSignature = "";
            appliedPlan = null;
            desiredLinks.clear();
            lastReport = new ApplyReport(0, 0, 0, List.copyOf(transitionWarnings));
            status = "Route apply failed; original stream restoration attempted: " + message(error);
            throw error;
        }
    }
}
