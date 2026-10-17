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

abstract class PipeWireRoutePart07 extends PipeWireRoutePart06 {
    protected String signature(Plan plan, PipeWireBackend.Snapshot snapshot) {
        List<String> streamPlans = plan.streams().stream()
                .map(stream -> stream.node().name() + ":" + stream.busId() + ":" + stream.positions() + ":" + stream.filters().hashCode())
                .sorted().toList();
        List<String> outputPlans = plan.outputs().values().stream()
                .map(output -> output.sink().name() + ":" + output.positions() + ":" + output.filters().hashCode() + ":" + output.limiterFilters().hashCode())
                .sorted().toList();
        List<String> sinkNames = snapshot.sinks().stream().filter(sink -> !sink.name().startsWith("audiomatrix_")).map(AudioNode::name).sorted().toList();
        return plan.masterVolume() + ":" + plan.masterMuted() + ":" + plan.buses().hashCode() + ":" + plan.routes().hashCode()
                + ":" + streamPlans + ":" + outputPlans + ":" + sinkNames + ":" + snapshot.defaultSinkId();
    }

    protected boolean processesAlive() {
        return busProcesses.values().stream().allMatch(process -> process.process().isAlive())
                && routeProcesses.values().stream().allMatch(process -> process.process().isAlive())
                && filterChainProcesses.values().stream().allMatch(process -> process.process().isAlive());
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closeFuture != null) return closeFuture;
        closed = true;
        closeFuture = CompletableFuture.runAsync(() -> {
            try { clearNow(true); removeBundledLadspaDirectory(); }
            catch (Exception error) {
                disconnectOwnedLinks();
                stopOwnedProcesses();
                restoreAllOriginal();
                originalStreamLinks.clear();
                active = false;
                appliedSignature = "";
                status = "Routes stopped; cleanup reported: " + message(error);
                removeBundledLadspaDirectory();
            }
        }, worker).whenComplete((ignored, error) -> worker.shutdown());
        return closeFuture;
    }

    @Override public void close() { closeAsync(); }
}
