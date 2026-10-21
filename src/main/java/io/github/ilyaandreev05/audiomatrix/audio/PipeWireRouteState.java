package io.github.ilyaandreev05.audiomatrix.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ilyaandreev05.audiomatrix.config.ChannelMatrix;

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

abstract class PipeWireRouteState extends PipeWireRouteStaticSupport04 implements AutoCloseable {

    protected final ObjectMapper json = new ObjectMapper();
    protected final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audio-matrix-route-manager"); t.setDaemon(true); return t;
    });
    protected final Map<String, ManagedProcess> busProcesses = new LinkedHashMap<>();
    protected final Map<String, ManagedProcess> routeProcesses = new LinkedHashMap<>();
    protected final Map<String, ManagedProcess> filterChainProcesses = new LinkedHashMap<>();
    protected final Map<String, List<Link>> originalStreamLinks = new LinkedHashMap<>();
    protected final Set<Link> ownedLinks = new LinkedHashSet<>();
    protected final Set<Link> desiredLinks = new LinkedHashSet<>();
    protected final Set<Link> observedLinks = new HashSet<>();
    protected final Map<String, Set<Link>> branchOwnedLinks = new LinkedHashMap<>();
    protected final Map<String, String> processSpecifications = new LinkedHashMap<>();
    protected volatile Map<String, BranchDiagnostic> branchDiagnostics = Map.of();
    protected int skippedStreamCount;
    protected volatile boolean active;
    protected volatile boolean closed;
    protected volatile String status = "Routes are not applied";
    protected volatile String appliedSignature = "";
    protected volatile Plan appliedPlan;
    protected volatile int activeTransitionMs = 120;
    protected volatile ApplyReport lastReport = new ApplyReport(0, 0, 0, List.of());
    protected final List<String> transitionWarnings = new ArrayList<>();
    protected Path bundledLadspaDirectory;
    protected int unavailableOutputs, conflictingOutputs, commandTimeouts;
    protected volatile RouteDiagnostics diagnostics = new RouteDiagnostics(0, 0, 0, 0, 0, 0, false);

    protected String run(List<String> command, Duration timeout) throws Exception {
        try { return PipeWireCommandRunner.run(command, timeout); }
        catch (IOException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("Command timed out:")) commandTimeouts++;
            throw error;
        }
    }

    protected void publishDiagnostics() {
        int missing = 0, present = 0;
        for (Link link : desiredLinks) {
            if (observedLinks.contains(link)) present++; else missing++;
        }
        diagnostics = new RouteDiagnostics(desiredLinks.size(), present, missing,
                unavailableOutputs, conflictingOutputs, commandTimeouts, active);
    }
    protected CompletableFuture<Void> closeFuture;

}
