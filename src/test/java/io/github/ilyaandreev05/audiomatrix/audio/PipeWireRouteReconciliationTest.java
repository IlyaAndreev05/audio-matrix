package io.github.ilyaandreev05.audiomatrix.audio;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PipeWireRouteReconciliationTest {
    private static final PipeWireRouteManager.Link LINK = new PipeWireRouteManager.Link("app:out", "audiomatrix_bus:in");

    @Test void commandSuccessIsNotObservedSuccess() throws Exception {
        try (Harness runtime = new Harness()) {
            runtime.createLinks = false;
            runtime.connectAll(List.of(LINK));
            assertTrue(runtime.desiredLinks.contains(LINK));
            assertFalse(runtime.observedLinks.contains(LINK));
            assertThrows(IOException.class, runtime::confirmDesiredLinks);
            runtime.publishDiagnostics();
            assertEquals(1, runtime.diagnostics().missingLinks());
            assertEquals(0, runtime.diagnostics().observedLinks());
        }
    }

    @Test void confirmsActualLinksAndDetectsLaterExternalRemoval() throws Exception {
        try (Harness runtime = new Harness()) {
            runtime.connectAll(List.of(LINK));
            runtime.confirmDesiredLinks();
            assertTrue(runtime.observedGraphMatches());
            runtime.live.clear();
            assertFalse(runtime.observedGraphMatches());
            assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("--wait")));
        }
    }

    @Test void timedOutCreationCanBeConfirmedAndRemainsOwnedForRollback() throws Exception {
        try (Harness runtime = new Harness()) {
            runtime.failCreation = true;
            runtime.connectAll(List.of(LINK));
            assertTrue(runtime.ownedLinks.contains(LINK));
            runtime.disconnectOwnedLinks();
            assertTrue(runtime.live.isEmpty());
            assertTrue(runtime.ownedLinks.isEmpty());
        }
    }

    @Test void failedRemovalIsRetainedAndReportedInsteadOfClaimingCleanup() throws Exception {
        try (Harness runtime = new Harness()) {
            runtime.connectAll(List.of(LINK));
            runtime.failRemoval = true;
            runtime.disconnectOwnedLinks();
            assertTrue(runtime.ownedLinks.contains(LINK));
            assertFalse(runtime.transitionWarnings.isEmpty());
        }
    }

    @Test void observedExistingLinksAreNotCreatedAgain() throws Exception {
        try (Harness runtime = new Harness()) {
            runtime.live.add(LINK);
            runtime.refreshObservedLinks();
            runtime.connectAll(List.of(LINK));
            runtime.confirmDesiredLinks();
            assertTrue(runtime.commands.stream().allMatch(command -> command.contains("-l")));
        }
    }

    @Test void replacingOneOwnerLeavesTheRemainingBranchLinksUntouched() throws Exception {
        PipeWireRouteManager.Link other = new PipeWireRouteManager.Link("route-other:out", "sink-other:in");
        PipeWireRouteManager.Link replacement = new PipeWireRouteManager.Link("route:out", "sink-new:in");
        try (Harness runtime = new Harness()) {
            runtime.live.addAll(List.of(LINK, other));
            runtime.ownedLinks.addAll(runtime.live);
            runtime.branchOwnedLinks.put("route:changed", new LinkedHashSet<>(List.of(LINK)));
            runtime.branchOwnedLinks.put("route:stable", new LinkedHashSet<>(List.of(other)));
            runtime.observedLinks.addAll(runtime.live);

            PipeWireRouteTypes.BranchDiagnostic diagnostic = PipeWireRouteBranchLinkDelta.reconcile(
                    runtime, new PipeWireRouteBranchLinkDelta.BranchOwner("route:changed", "changed", "route", true, true),
                    List.of(replacement), List.of("route-process:changed"), List.of(), null);

            assertEquals("", diagnostic.error());
            assertEquals("changed", diagnostic.ownerId());
            assertEquals("route", diagnostic.kind());
            assertTrue(diagnostic.selected());
            assertTrue(diagnostic.available());
            assertFalse(diagnostic.processAlive());
            assertEquals(List.of(replacement), diagnostic.desiredLinks());
            assertEquals(List.of(replacement), diagnostic.observedLinks());
            assertEquals(List.of("route-process:changed"), diagnostic.desiredProcesses());
            assertEquals(new LinkedHashSet<>(List.of(other)), runtime.branchOwnedLinks.get("route:stable"));
            assertTrue(runtime.live.containsAll(List.of(replacement, other)));
            assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("-d")
                    && command.get(2).equals(other.output()) && command.get(3).equals(other.input())));
        }
    }

    @Test void failedBranchCreationRollsBackOnlyThatOwner() {
        PipeWireRouteManager.Link other = new PipeWireRouteManager.Link("route-other:out", "sink-other:in");
        PipeWireRouteManager.Link replacement = new PipeWireRouteManager.Link("route:out", "sink-new:in");
        try (Harness runtime = new Harness()) {
            runtime.live.addAll(List.of(LINK, other));
            runtime.ownedLinks.addAll(runtime.live);
            runtime.branchOwnedLinks.put("route:changed", new LinkedHashSet<>(List.of(LINK)));
            runtime.branchOwnedLinks.put("route:stable", new LinkedHashSet<>(List.of(other)));
            runtime.observedLinks.addAll(runtime.live);
            runtime.failCreationLink = replacement;

            PipeWireRouteTypes.BranchDiagnostic diagnostic = PipeWireRouteBranchLinkDelta.reconcile(
                    runtime, new PipeWireRouteBranchLinkDelta.BranchOwner("route:changed", "changed", "route", true, true),
                    List.of(replacement), List.of("route-process:changed"), List.of(), null);

            assertFalse(diagnostic.error().isEmpty());
            assertTrue(runtime.live.containsAll(List.of(LINK, other)));
            assertFalse(runtime.live.contains(replacement));
            assertEquals(new LinkedHashSet<>(List.of(LINK)), runtime.branchOwnedLinks.get("route:changed"));
            assertEquals(new LinkedHashSet<>(List.of(other)), runtime.branchOwnedLinks.get("route:stable"));
        }
    }


    private static final class Harness extends PipeWireRoutePart07 {
        final Set<Link> live = new LinkedHashSet<>();
        final List<List<String>> commands = new ArrayList<>();
        boolean createLinks = true, failCreation, failRemoval;
        PipeWireRouteManager.Link failCreationLink;

        @Override protected String run(List<String> command, Duration timeout) throws Exception {
            commands.add(List.copyOf(command));
            if (command.equals(List.of("pw-link", "-l"))) {
                StringBuilder listing = new StringBuilder();
                for (Link link : live) listing.append(link.output()).append("\n  |-> ").append(link.input()).append('\n');
                return listing.toString();
            }
            if (command.contains("-d")) {
                if (failRemoval) throw new IOException("test removal failure");
                live.remove(new Link(command.get(2), command.get(3)));
            } else {
                PipeWireRouteManager.Link link = new PipeWireRouteManager.Link(command.get(1), command.get(2));
                if (createLinks && !link.equals(failCreationLink)) live.add(link);
                if (failCreation || link.equals(failCreationLink))
                    throw new IOException("Command timed out: pw-link");
            }
            return "";
        }
        @Override public void close() { worker.shutdownNow(); }
    }
}
