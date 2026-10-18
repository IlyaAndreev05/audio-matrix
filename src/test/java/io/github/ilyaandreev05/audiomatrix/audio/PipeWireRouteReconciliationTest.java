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

    private static final class Harness extends PipeWireRoutePart07 {
        final Set<Link> live = new LinkedHashSet<>();
        final List<List<String>> commands = new ArrayList<>();
        boolean createLinks = true, failCreation, failRemoval;

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
                if (createLinks) live.add(new Link(command.get(1), command.get(2)));
                if (failCreation) throw new IOException("Command timed out: pw-link");
            }
            return "";
        }
        @Override public void close() { worker.shutdownNow(); }
    }
}
