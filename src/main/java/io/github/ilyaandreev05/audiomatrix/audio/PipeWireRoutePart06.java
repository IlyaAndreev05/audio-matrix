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

abstract class PipeWireRoutePart06 extends PipeWireRoutePart05 {
    protected void startFilterChain(String processKey, String description, String inputNodeName, String outputNodeName,
                                  List<String> positions, List<Map<String, Object>> filters) throws Exception {
        ManagedProcess existing = filterChainProcesses.get(processKey);
        if (existing != null && existing.process().isAlive()) return;
        String config = PipeWireFilterChainConfig.render(description, inputNodeName, outputNodeName, positions, filters, true);
        Path configFile = Files.createTempFile("audio-matrix-filter-", ".conf");
        try {
            Files.writeString(configFile, config);
            Path ladspaDirectory = filters.stream().anyMatch(filter -> "limiter".equals(filter.get("type")))
                    ? bundledLadspaDirectory() : null;
            ManagedProcess process = startProcess(List.of("pipewire", "-c", configFile.toString()), inputNodeName, configFile, ladspaDirectory);
            filterChainProcesses.put(processKey, process);
            awaitPorts(inputNodeName, "-i", process.process());
            awaitPorts(outputNodeName, "-o", process.process());
        } catch (Exception error) {
            if (!filterChainProcesses.containsKey(processKey)) Files.deleteIfExists(configFile);
            throw error;
        }
    }

    protected ManagedProcess startProcess(List<String> command, String key) throws IOException {
        return startProcess(command, key, null);
    }

    protected ManagedProcess startProcess(List<String> command, String key, Path configFile) throws IOException {
        return startProcess(command, key, configFile, null);
    }

    protected ManagedProcess startProcess(List<String> command, String key, Path configFile, Path ladspaDirectory) throws IOException {
        Path log = Files.createTempFile("audio-matrix-pw-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            if (ladspaDirectory != null) {
                String systemPath = builder.environment().getOrDefault("LADSPA_PATH", "");
                builder.environment().put("LADSPA_PATH", ladspaDirectory + (systemPath.isBlank() ? "" : java.io.File.pathSeparator + systemPath));
            }
            Process process = builder.start();
            return new ManagedProcess(process, log, List.copyOf(command), configFile);
        } catch (IOException error) {
            Files.deleteIfExists(log);
            throw error;
        }
    }

    protected void awaitPorts(String nodeName, String direction, Process process) throws Exception {
        for (int i = 0; i < START_ATTEMPTS; i++) {
            if (!process.isAlive()) throw new IOException("pw-loopback exited while creating " + nodeName + ": " + processLog(process, nodeName));
            if (!ports(direction).getOrDefault(nodeName, List.of()).isEmpty()) return;
            Thread.sleep(START_WAIT_MS);
        }
        throw new IOException("PipeWire ports did not appear for " + nodeName + ": " + processLog(process, nodeName));
    }

    protected Map<String, List<String>> ports(String direction) throws Exception {
        String listing = run(List.of("pw-link", direction), COMMAND_TIMEOUT);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String line : listing.split("\\R")) {
            String port = line.trim(); int colon = port.lastIndexOf(':');
            if (colon <= 0 || colon == port.length() - 1) continue;
            result.computeIfAbsent(port.substring(0, colon), ignored -> new ArrayList<>()).add(port);
        }
        return result;
    }

    protected int findNodeId(String nodeName) throws Exception {
        JsonNode root = json.readTree(run(List.of("pw-dump", "-N"), Duration.ofSeconds(4)));
        if (!root.isArray()) throw new IOException("pw-dump returned an unexpected document");
        for (JsonNode object : root) {
            if (nodeName.equals(object.path("info").path("props").path("node.name").asText())) return object.path("id").asInt(-1);
        }
        throw new IOException("PipeWire node was not found: " + nodeName);
    }

    protected void connectAll(List<Link> links) throws Exception {
        if (links.isEmpty()) throw new IOException("No compatible PipeWire channel ports were found");
        for (Link link : links) {
            if (observedLinks.contains(link)) {
                if (isAudioMatrixGraphLink(link))
                    ownedLinks.add(link);
                continue;
            }
            try {
                run(List.of("pw-link", "--wait", link.output(), link.input()), COMMAND_TIMEOUT);
                observedLinks.add(link);
                ownedLinks.add(link);
            } catch (Exception error) {
                List<Link> live = PipeWireLinkOperations.allLinks(run(List.of("pw-link", "-l"), COMMAND_TIMEOUT));
                if (!live.contains(link)) throw error;
                observedLinks.add(link);
                ownedLinks.add(link);
            }
        }
    }

    protected void disconnectAll(List<Link> links) {
        for (Link link : links) try {
            run(List.of("pw-link", "-d", link.output(), link.input()), COMMAND_TIMEOUT);
            observedLinks.remove(link);
            ownedLinks.remove(link);
        } catch (Exception ignored) {}
    }

    protected void disconnectOwnedLinks() {
        disconnectAll(new ArrayList<>(ownedLinks)); ownedLinks.clear();
    }

    protected void stopOwnedProcesses() {
        stopProcesses(filterChainProcesses); stopProcesses(routeProcesses); stopProcesses(busProcesses);
    }

    protected void stopProcesses(Map<String, ManagedProcess> processes) {
        for (ManagedProcess managed : new ArrayList<>(processes.values())) {
            Process process = managed.process();
            process.destroy();
            try { if (!process.waitFor(700, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); process.waitFor(700, TimeUnit.MILLISECONDS); } }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            try { Files.deleteIfExists(managed.log()); } catch (IOException ignored) {}
            if (managed.configFile() != null) try { Files.deleteIfExists(managed.configFile()); } catch (IOException ignored) {}
        }
        processes.clear();
    }

    protected Path bundledLadspaDirectory() throws IOException {
        if (bundledLadspaDirectory != null && Files.isRegularFile(bundledLadspaDirectory.resolve("libaudiomatrix_limiter.so")))
            return bundledLadspaDirectory;
        Path directory = Files.createTempDirectory("audio-matrix-ladspa-");
        try (InputStream plugin = PipeWireRouteManager.class.getResourceAsStream("/native/libaudiomatrix_limiter.so")) {
            if (plugin == null) throw new IOException("The bundled Audio Matrix limiter plugin is missing");
            Files.copy(plugin, directory.resolve("libaudiomatrix_limiter.so"));
            bundledLadspaDirectory = directory;
            return directory;
        } catch (Exception error) {
            try { Files.deleteIfExists(directory.resolve("libaudiomatrix_limiter.so")); Files.deleteIfExists(directory); }
            catch (IOException ignored) {}
            if (error instanceof IOException io) throw io;
            throw new IOException("Could not prepare the bundled Audio Matrix limiter plugin", error);
        }
    }

    protected void removeBundledLadspaDirectory() {
        Path directory = bundledLadspaDirectory;
        bundledLadspaDirectory = null;
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }

    protected String processLog(Process process, String nodeName) {
        ManagedProcess managed = busProcesses.values().stream().filter(item -> item.process() == process).findFirst()
                .orElseGet(() -> routeProcesses.values().stream().filter(item -> item.process() == process).findFirst()
                        .orElseGet(() -> filterChainProcesses.values().stream().filter(item -> item.process() == process).findFirst().orElse(null)));
        if (managed == null) return "no process log for " + nodeName;
        try { return Files.readString(managed.log()).trim(); } catch (IOException e) { return "no process log for " + nodeName; }
    }

    protected void clearNow(boolean restoreStreams) throws Exception {
        if (appliedPlan != null) fadeRoutePlan(appliedPlan, true, activeTransitionMs);
        disconnectOwnedLinks();
        stopOwnedProcesses();
        if (restoreStreams) restoreAllOriginal();
        originalStreamLinks.clear(); observedLinks.clear(); skippedStreamCount = 0;
        active = false; appliedSignature = ""; appliedPlan = null;
        status = transitionWarnings.isEmpty() ? "Routes are not applied"
                : "Routes are not applied · " + String.join("; ", transitionWarnings);
        transitionWarnings.clear();
    }

    protected void restoreAllOriginal() {
        for (String stream : new ArrayList<>(originalStreamLinks.keySet())) restoreOriginal(stream);
    }

    protected void restoreOriginal(String streamNode) {
        List<Link> original = originalStreamLinks.getOrDefault(streamNode, List.of());
        Set<String> outputPorts;
        Set<String> inputPorts;
        try {
            outputPorts = new HashSet<>(ports("-o").getOrDefault(streamNode, List.of()));
            inputPorts = new HashSet<>(); ports("-i").values().forEach(inputPorts::addAll);
        } catch (Exception e) { return; }
        for (Link link : original) if (outputPorts.contains(link.output()) && inputPorts.contains(link.input()))
            try { run(List.of("pw-link", "--wait", link.output(), link.input()), COMMAND_TIMEOUT); } catch (Exception ignored) {}
    }
}
