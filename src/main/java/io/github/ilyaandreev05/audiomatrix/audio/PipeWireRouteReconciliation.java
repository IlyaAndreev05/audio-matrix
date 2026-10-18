package io.github.ilyaandreev05.audiomatrix.audio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Desired edges are recorded separately from a fresh, authoritative pw-link observation. */
abstract class PipeWireRouteReconciliation extends PipeWireRoutePart05 {
    protected boolean observedGraphMatches() throws Exception {
        refreshObservedLinks();
        return !desiredLinks.isEmpty() && observedLinks.containsAll(desiredLinks);
    }

    protected void confirmDesiredLinks() throws Exception {
        refreshObservedLinks();
        Set<Link> missing = new java.util.LinkedHashSet<>(desiredLinks);
        missing.removeAll(observedLinks);
        if (!missing.isEmpty()) throw new IOException("PipeWire did not confirm desired links: " + missing);
    }

    protected void refreshObservedLinks() throws Exception {
        observedLinks.clear();
        observedLinks.addAll(PipeWireLinkOperations.allLinks(run(List.of("pw-link", "-l"), COMMAND_TIMEOUT)));
    }

    protected void connectAll(List<Link> links) throws Exception {
        if (links.isEmpty()) throw new IOException("No compatible PipeWire channel ports were found");
        for (Link link : links) {
            desiredLinks.add(link);
            if (observedLinks.contains(link)) {
                if (isAudioMatrixGraphLink(link)) ownedLinks.add(link);
                continue;
            }
            // --wait keeps pw-link alive after creation; it is not an acknowledgement option.
            // Register before execution: a timed-out command may still have created the link.
            ownedLinks.add(link);
            try { run(List.of("pw-link", link.output(), link.input()), COMMAND_TIMEOUT); }
            catch (Exception error) {
                try { refreshObservedLinks(); }
                catch (Exception observation) { error.addSuppressed(observation); throw error; }
                if (!observedLinks.contains(link)) throw error;
            }
        }
    }

    protected void disconnectAll(List<Link> links) {
        for (Link link : links) {
            try {
                run(List.of("pw-link", "-d", link.output(), link.input()), COMMAND_TIMEOUT);
                observedLinks.remove(link);
                ownedLinks.remove(link);
                desiredLinks.remove(link);
            } catch (Exception error) {
                try {
                    refreshObservedLinks();
                    if (!observedLinks.contains(link)) {
                        ownedLinks.remove(link);
                        desiredLinks.remove(link);
                        continue;
                    }
                } catch (Exception observation) { error.addSuppressed(observation); }
                transitionWarnings.add("Could not confirm link removal " + link + ": " + message(error));
            }
        }
    }

    protected void disconnectOwnedLinks() {
        disconnectAll(new ArrayList<>(ownedLinks));
    }

    protected void restoreAllOriginal() {
        for (String stream : new ArrayList<>(originalStreamLinks.keySet())) restoreOriginal(stream);
    }

    protected void restoreOriginal(String streamNode) {
        List<Link> original = originalStreamLinks.getOrDefault(streamNode, List.of());
        if (original.isEmpty()) return;
        Set<String> outputPorts;
        Set<String> inputPorts;
        try {
            outputPorts = new HashSet<>(ports("-o").getOrDefault(streamNode, List.of()));
            inputPorts = new HashSet<>(); ports("-i").values().forEach(inputPorts::addAll);
            refreshObservedLinks();
        } catch (Exception error) {
            transitionWarnings.add("Could not inspect original stream links: " + message(error));
            return;
        }
        for (Link link : original) {
            if (!outputPorts.contains(link.output()) || !inputPorts.contains(link.input())) {
                transitionWarnings.add("Original stream endpoint unavailable: " + link);
                continue;
            }
            if (observedLinks.contains(link)) continue;
            try {
                run(List.of("pw-link", link.output(), link.input()), COMMAND_TIMEOUT);
                refreshObservedLinks();
                if (!observedLinks.contains(link)) throw new IOException("Link not observed");
            } catch (Exception error) {
                transitionWarnings.add("Could not confirm original stream link " + link + ": " + message(error));
            }
        }
    }
}
