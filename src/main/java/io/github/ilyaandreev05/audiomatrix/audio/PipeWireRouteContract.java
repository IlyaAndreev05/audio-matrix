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

abstract class PipeWireRouteContract extends PipeWireRouteState {
    public abstract CompletableFuture<ApplyReport> apply(Map<String, Object> config, PipeWireBackend.Snapshot snapshot);
    public abstract CompletableFuture<ApplyReport> reapplyAfterReconnect(Map<String, Object> config, PipeWireBackend.Snapshot snapshot);
    protected abstract CompletableFuture<ApplyReport> apply(Map<String, Object> config, PipeWireBackend.Snapshot snapshot,
                                                  boolean reconnect);
    public abstract CompletableFuture<Void> clear();
    public abstract boolean active();
    public abstract String status();
    public abstract ApplyReport lastReport();
    public abstract HeadroomEstimate estimateHeadroom(Map<String, Object> config, PipeWireBackend.Snapshot snapshot, AudioNode sink) throws IOException;
    public abstract double maximumEqBoostDb(Map<String, Object> config, PipeWireBackend.Snapshot snapshot, AudioNode sink) throws IOException;
    protected abstract ApplyReport applyNow(Map<String, Object> config, PipeWireBackend.Snapshot snapshot) throws Exception;
    protected abstract ApplyReport rebuildPipeWireGraph(Plan plan, String signature, int transitionMs,
                                             int fadeOutMs, int fadeInMs,
                                             Map<String, List<Link>> originalBeforeApply) throws Exception;
    protected abstract ApplyReport reportAppliedPlan(Plan plan);
    protected abstract boolean observedGraphMatches() throws Exception;
    protected abstract void confirmDesiredLinks() throws Exception;
    protected abstract void refreshObservedLinks() throws Exception;
    protected abstract void fadeRoutePlan(Plan plan, boolean fadeOut, int durationMs);
    protected abstract Plan plan(Map<String, Object> config, PipeWireBackend.Snapshot snapshot) throws IOException;
    protected abstract void updateLiveLevels(Plan previous, Plan plan) throws Exception;
    protected abstract List<Map<String, Object>> routeFilters(Map<String, Object> route, Map<String, Object> config) throws IOException;
    protected abstract List<Map<String, Object>> outputFilters(AudioNode sink, List<Map<String, Object>> devices,
                                            Map<String, Object> config) throws IOException;
    protected abstract List<Map<String, Object>> outputLimiterFilters(Map<String, Object> config);
    protected abstract List<Map<String, Object>> outputLimiterFilters(Map<String, Object> config, String outputDeviceId);
    protected abstract List<Map<String, Object>> applicationFilters(Map<String, Object> rule, Map<String, Object> config) throws IOException;
    protected abstract AudioNode resolveSink(String ref, Map<String, Object> profile, List<AudioNode> sinks,
                                  List<Map<String, Object>> devices, int defaultSinkId);
    protected abstract Set<String> sourceRootRolesForBus(String busId, Map<String, Map<String, Object>> busConfig,
                                              List<Map<String, Object>> configuredRoutes, Set<String> activeBuses,
                                              Set<String> activeRoutes, Set<String> visiting);
    protected abstract void startBus(BusPlan bus) throws Exception;
    protected abstract void startRoute(RoutePlan route) throws Exception;
    protected abstract void startFilterChain(RoutePlan route) throws Exception;
    protected abstract void startChannelMatrix(RoutePlan route) throws Exception;
    protected abstract void startFilterChain(StreamPlan stream) throws Exception;
    protected abstract void startFilterChain(OutputPlan output) throws Exception;
    protected abstract void startOutputLimiter(OutputPlan output) throws Exception;
    protected abstract void startFilterChain(String processKey, String description, String inputNodeName, String outputNodeName,
                                  List<String> positions, List<Map<String, Object>> filters) throws Exception;
    protected abstract ManagedProcess startProcess(List<String> command, String key) throws IOException;
    protected abstract ManagedProcess startProcess(List<String> command, String key, Path configFile) throws IOException;
    protected abstract ManagedProcess startProcess(List<String> command, String key, Path configFile, Path ladspaDirectory) throws IOException;
    protected abstract void awaitPorts(String nodeName, String direction, Process process) throws Exception;
    protected abstract Map<String, List<String>> ports(String direction) throws Exception;
    protected abstract int findNodeId(String nodeName) throws Exception;
    protected abstract void connectAll(List<Link> links) throws Exception;
    protected abstract void disconnectAll(List<Link> links);
    protected abstract void disconnectOwnedLinks();
    protected abstract void stopOwnedProcesses();
    protected abstract void stopProcesses(Map<String, ManagedProcess> processes);
    protected abstract Path bundledLadspaDirectory() throws IOException;
    protected abstract void removeBundledLadspaDirectory();
    protected abstract String processLog(Process process, String nodeName);
    protected abstract void clearNow(boolean restoreStreams) throws Exception;
    protected abstract void restoreAllOriginal();
    protected abstract void restoreOriginal(String streamNode);
    protected abstract String signature(Plan plan, PipeWireBackend.Snapshot snapshot);
    protected abstract boolean processesAlive();
    public abstract CompletableFuture<Void> closeAsync();
    public abstract void close();
}
