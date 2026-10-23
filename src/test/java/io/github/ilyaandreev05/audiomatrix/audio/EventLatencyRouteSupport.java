package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.github.ilyaandreev05.audiomatrix.audio.EventLatencyLiveSupport.destinationNode;
import static io.github.ilyaandreev05.audiomatrix.audio.PipeWireChannelMatrixRouteLiveSmokeSupport.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies observed, real manager owners and the complete fixture path; never creates a route itself. */
final class EventLatencyRouteSupport {
    private EventLatencyRouteSupport() {}

    static EventPath eventPath(PipeWireRouteManager manager, Map<String, Object> runtime,
                               PipeWireBackend.Snapshot snapshot) throws IOException {
        List<String> eventBuses = PipeWireRouteStaticSupport01.strings(runtime.get("event_input_buses"));
        assertEquals(1, eventBuses.size(), "The actual compiled fixture must contain its dedicated event input bus");
        PipeWireRouteManager.Plan plan = manager.plan(runtime, snapshot);
        assertTrue(plan.outputs().keySet().stream().allMatch(Set.of(RouteSmokePcmSupport.PRIMARY_SINK,
                        RouteSmokePcmSupport.EVENT_SINK)::contains),
                "The compiled fixture must target only the two private virtual outputs");
        PipeWireRouteManager.BusPlan bus = plan.buses().get(eventBuses.getFirst());
        assertNotNull(bus, "The event source must compile to a real managed bus");
        List<PipeWireRouteManager.RoutePlan> routes = plan.routes().stream()
                .filter(route -> bus.id().equals(route.fromBusId())).toList();
        assertEquals(1, routes.size(), "The fixture event bus must have exactly one output route");
        PipeWireRouteManager.RoutePlan route = routes.getFirst();
        assertNotNull(route.sink(), "The private event output must resolve before measuring");
        assertEquals(RouteSmokePcmSupport.EVENT_SINK, route.sink().name());
        assertTrue(route.filters().isEmpty() && route.channelMatrix() == null,
                "This onset probe requires the fixture's unfiltered, scalar-gain event route");
        return new EventPath(bus.nodeName(), route.id(), route.captureNodeName(), route.playbackNodeName());
    }

    static void applyUntilRouted(PipeWireBackend backend, PipeWireRouteManager manager,
                                 Map<String, Object> runtime, EventPath path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        Exception failure = null;
        while (System.nanoTime() < deadline) {
            try {
                manager.apply(runtime, backend.refresh().get(5, TimeUnit.SECONDS)).get(20, TimeUnit.SECONDS);
                if (completeBranch(manager.branchDiagnostics().get(RouteSmokePcmSupport.ESS_MONITOR)) &&
                        completeBranch(manager.branchDiagnostics().get(path.routeId()))) return;
            } catch (Exception error) {
                failure = error;
            }
            Thread.sleep(200);
        }
        throw new IOException("The actual event-input and route owners did not become fully observed: "
                + manager.branchDiagnostics(), failure);
    }

    private static boolean completeBranch(PipeWireRouteManager.BranchDiagnostic branch) {
        return branch != null && branch.selected() && branch.available() && branch.processAlive()
                && branch.error().isEmpty() && !branch.desiredLinks().isEmpty()
                && new LinkedHashSet<>(branch.desiredLinks()).equals(new LinkedHashSet<>(branch.observedLinks()));
    }

    static void assertMeasurementTopology(PipeWireRouteManager manager, EventPath path,
                                          List<String> playerLinks, List<String> captureLinks, List<String> links) {
        assertTrue(completeBranch(manager.branchDiagnostics().get(RouteSmokePcmSupport.ESS_MONITOR)),
                "The event-input owner must observe every desired link: " + manager.branchDiagnostics());
        assertTrue(completeBranch(manager.branchDiagnostics().get(path.routeId())),
                "The actual managed route must observe every desired link: " + manager.branchDiagnostics());
        assertNodeLinks(links, RouteSmokePcmSupport.ESS_MONITOR, path.busName());
        assertNodeLinks(links, path.busName(), path.routeCapture());
        assertNodeLinks(links, path.routePlayback(), RouteSmokePcmSupport.EVENT_SINK);
        assertEquals(new LinkedHashSet<>(playerLinks), links.stream()
                        .filter(value -> value.startsWith(EventLatencyLiveSupport.PLAYER + ":"))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                "The ESS-ID player must connect only to the isolated input, never directly to a routed output");
        assertEquals(new LinkedHashSet<>(captureLinks), links.stream()
                        .filter(value -> destinationNode(value).equals(EventLatencyLiveSupport.CAPTURE))
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                "One clocked recorder must have exactly the reference FL and routed FR source taps");
        assertTrue(links.stream().filter(value -> destinationNode(value).equals(RouteSmokePcmSupport.EVENT_SINK))
                        .allMatch(value -> value.startsWith(path.routePlayback() + ":")),
                "No player or alternate source may bypass the compiled event route: " + links);
        assertFalse(links.stream().anyMatch(value -> value.startsWith("audiomatrix_events_discard:")),
                "The event service's discard side must never connect to another output");
    }

    private static void assertNodeLinks(List<String> links, String from, String to) {
        long count = links.stream().filter(value -> value.startsWith(from + ":") && destinationNode(value).equals(to)).count();
        assertEquals(2, count, "Both channels of the actual path must be linked: " + from + " -> " + to + ": " + links);
    }

    static Map<String, Integer> ownedEventNodes(EventPath path) throws Exception {
        Set<String> expected = Set.of(path.busName(), path.routeCapture(), path.routePlayback());
        Map<String, Integer> result = new LinkedHashMap<>();
        for (JsonNode item : new ObjectMapper().readTree(run("pw-dump", "-N"))) {
            String name = item.path("info").path("props").path("node.name").asText("");
            if (expected.contains(name)) result.put(name, item.path("id").asInt());
        }
        assertEquals(expected, result.keySet(), "The event owner must have a live bus and both real route nodes");
        return result;
    }

    record EventPath(String busName, String routeId, String routeCapture, String routePlayback) {}
}
