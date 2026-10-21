package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.AudioMatrixOwnedStream;
import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Reconciles volatile backend/browser observations with stable, persisted generator instances. */
public final class DynamicInstanceRegistry {
    private static final Set<String> OVERRIDE_FIELDS = Set.of("gain", "muted", "display_name", "icon", "pinned",
            "group_id", "order", "expanded", "show_children", "show_when_offline");
    private DynamicInstanceRegistry() {}

    public enum Confidence { HIGH, LOW }

    /** One current observation. runtimeBinding is deliberately not serialized into generator config. */
    public record InstanceSnapshot(String registry, String stableKey, String displayName, String selector,
                                   List<String> channels, Map<String, Object> runtimeBinding,
                                   Confidence confidence) {
        public InstanceSnapshot {
            if (!Set.of("applications", "outputs", "browser_tabs").contains(registry))
                throw new IllegalArgumentException("Unsupported instance registry: " + registry);
            if (stableKey == null || stableKey.isBlank()) throw new IllegalArgumentException("Instance stable key must not be blank");
            displayName = displayName == null || displayName.isBlank() ? stableKey : displayName;
            selector = selector == null ? "" : selector;
            channels = channels == null ? List.of() : List.copyOf(channels);
            runtimeBinding = immutableObject(runtimeBinding);
            confidence = Objects.requireNonNull(confidence, "confidence");
        }
    }

    public record InstanceAddress(String generatorId, String instanceKey) {}

    /** Runtime-only state corresponding to one persisted generator instance. */
    public record RuntimeInstance(String generatorId, String instanceKey, Map<String, String> nodeIds,
                                  String compositeId, boolean available, boolean bindingAvailable,
                                  boolean controlAvailable, Confidence confidence,
                                  Map<String, Object> runtimeBinding, Map<String, Object> overrides) {
        public RuntimeInstance {
            nodeIds = Map.copyOf(nodeIds);
            runtimeBinding = immutableObject(runtimeBinding);
            overrides = immutableObject(overrides);
        }
        public boolean gainControlAvailable() { return bindingAvailable && controlAvailable; }
        public boolean muteControlAvailable() {
            return runtimeBinding.containsKey("sessionToken")
                    ? available && !text(runtimeBinding.get("sessionToken")).isBlank()
                    : bindingAvailable && controlAvailable;
        }
    }

    public record GainState(double gain, boolean muted) {
        public GainState {
            if (!Double.isFinite(gain) || gain < 0 || gain > 1)
                throw new IllegalArgumentException("Generated gain must be between 0 and 1");
        }
    }

    @FunctionalInterface
    public interface ApplicationGainResolver {
        /** Resolve the canonical applications[] rule for this selector; return null to use the generator default. */
        GainState resolve(String selector, InstanceSnapshot instance);
    }

    @FunctionalInterface
    public interface EndpointGainResolver {
        /** Resolve saved endpoint/device level by stable DeviceIdentity key; observation is null while offline. */
        GainState resolve(String instanceKey, InstanceSnapshot observation);
    }

    public record ReconcileResult(AudioGraph configuredGraph, AudioGraph effectiveGraph,
                                  Map<InstanceAddress, RuntimeInstance> instances,
                                  List<GraphSubgraph> generatedSubgraphs, boolean configChanged) {
        public ReconcileResult {
            instances = Map.copyOf(instances);
            generatedSubgraphs = List.copyOf(generatedSubgraphs);
        }
    }

    /** Stable application key: application ID, process fallback, then non-PID stream metadata. */
    public static InstanceSnapshot application(AudioNode stream) {
        return application(stream, "", "");
    }

    /** canonicalAdapterId is a logical browser identity, never a PipeWire application_id/rule key. */
    public static InstanceSnapshot application(AudioNode stream, String canonicalAdapterId, String displayName) {
        Objects.requireNonNull(stream, "stream");
        String appId = safe(stream.applicationId());
        String process = safe(stream.processName());
        String canonical = safe(canonicalAdapterId);
        String stableKey;
        String selector;
        Confidence confidence;
        if (!canonical.isBlank()) {
            stableKey = "browser-parent:" + canonical;
            selector = !appId.isBlank() ? "application:" + appId
                    : !process.isBlank() ? "process:" + process : "metadata:" + sha256(stream.name());
            confidence = appId.isBlank() && process.isBlank() ? Confidence.LOW : Confidence.HIGH;
        } else if (!appId.isBlank()) {
            stableKey = "app:" + appId;
            selector = "application:" + appId;
            confidence = Confidence.HIGH;
        } else if (!process.isBlank()) {
            stableKey = "process:" + process;
            selector = "process:" + process;
            confidence = Confidence.HIGH;
        } else {
            String metadata = String.join("\u0000", safe(stream.applicationName()), safe(stream.name()),
                    safe(stream.description()), safe(stream.mediaClass()), String.valueOf(stream.channels()));
            String digest = sha256(metadata);
            stableKey = "stream:" + digest;
            selector = "metadata:" + digest;
            confidence = Confidence.LOW;
        }
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("streams", List.of(streamBinding(stream)));
        binding.put("applicationId", appId);
        binding.put("processName", process);
        binding.put("applicationName", safe(stream.applicationName()));
        binding.put("canonicalApplicationId", canonical);
        binding.put("audioMatrixOwned", AudioMatrixOwnedStream.matches(stream));
        binding.put("hasPcmBinding", !appId.isBlank() || !process.isBlank());
        return new InstanceSnapshot("applications", stableKey,
                firstNonBlank(displayName, stream.applicationName(), stream.description(), stream.name(), stableKey), selector,
                channels(stream), binding, confidence);
    }

    /** A browser snapshot preserves a logical parent without fabricating a PipeWire stream. */
    public static InstanceSnapshot browserParentApplication(String browser, String applicationId,
                                                            String processName, String displayName) {
        String adapter = requireIdentityPart(browser, "browser");
        String appId = safe(applicationId);
        String process = safe(processName);
        String selector = !appId.isBlank() ? "application:" + appId
                : !process.isBlank() ? "process:" + process : "application:unassigned";
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("streams", List.of());
        binding.put("instanceKey", "browser-parent:" + adapter);
        binding.put("applicationId", appId);
        binding.put("processName", process);
        binding.put("applicationName", safe(displayName));
        binding.put("canonicalApplicationId", adapter);
        binding.put("browserAdapter", adapter);
        binding.put("audioMatrixOwned", false);
        binding.put("hasPcmBinding", false);
        return new InstanceSnapshot("applications", "browser-parent:" + adapter,
                firstNonBlank(displayName, adapter), selector, List.of("FL", "FR"), binding, Confidence.HIGH);
    }

    /** Stable output key uses the existing port/profile/channel-aware DeviceIdentity. */
    public static InstanceSnapshot output(AudioNode output) {
        return output(output, false, null);
    }

    /** A fresh output is selected only when a caller explicitly requests it and identity is reliable. */
    public static InstanceSnapshot output(AudioNode output, boolean selected) {
        return output(output, selected, null);
    }

    /** savedEndpointLevel is read from the canonical endpoint/device config, never from instance overrides. */
    public static InstanceSnapshot output(AudioNode output, boolean selected, GainState savedEndpointLevel) {
        Objects.requireNonNull(output, "output");
        DeviceIdentity identity = output.identity();
        if (identity == null) throw new IllegalArgumentException("Output snapshot has no DeviceIdentity");
        String stableKey = "device:" + identity.fingerprint();
        if (!identity.reliable()) {
            String metadata = String.join("\u0000", safe(output.name()), safe(output.description()), safe(output.mediaClass()));
            stableKey += ":" + sha256(metadata);
        }
        String deviceId = safe(output.name());
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("nodeId", output.id());
        binding.put("deviceId", deviceId);
        binding.put("identity", identity.toMap());
        binding.put("volume", finiteLevel(output.volume(), 1.0));
        binding.put("muted", output.muted());
        if (savedEndpointLevel != null) {
            binding.put("savedEndpointGain", savedEndpointLevel.gain());
            binding.put("savedEndpointMuted", savedEndpointLevel.muted());
        }
        binding.put("selected", selected && identity.reliable());
        return new InstanceSnapshot("outputs", stableKey,
                firstNonBlank(output.description(), output.name(), stableKey),
                "device:" + identity.fingerprint(), channels(output), binding,
                identity.reliable() ? Confidence.HIGH : Confidence.LOW);
    }

    /** Browser-tab identity intentionally omits tabId; that value is only a runtime binding. */
    public static InstanceSnapshot browserTab(String browser, String instanceId, String profileId,
                                              String sessionToken, int tabId, String title, boolean audible,
                                              boolean muted, String volumeCapability, String captureStreamKey) {
        return browserTab(browser, instanceId, profileId, sessionToken, tabId, title, audible, muted,
                volumeCapability, captureStreamKey, "");
    }

    public static InstanceSnapshot browserTab(String browser, String instanceId, String profileId,
                                              String sessionToken, int tabId, String title, boolean audible,
                                              boolean muted, String volumeCapability, String captureStreamKey,
                                              String applicationRuleId) {
        return browserTab(browser, instanceId, profileId, sessionToken, tabId, title, audible, muted,
                volumeCapability, captureStreamKey, applicationRuleId, "", "");
    }

    public static InstanceSnapshot browserTab(String browser, String instanceId, String profileId,
                                              String sessionToken, int tabId, String title, boolean audible,
                                              boolean muted, String volumeCapability, String captureStreamKey,
                                              String applicationRuleId, String parentApplicationId,
                                              String parentProcessName) {
        String adapter = requireIdentityPart(browser, "browser");
        String instance = requireIdentityPart(instanceId, "browser instanceId");
        String profile = requireIdentityPart(profileId, "browser profileId");
        String token = requireIdentityPart(sessionToken, "browser sessionToken");
        String stableKey = "tab:" + adapter + ":" + instance + ":" + profile + ":" + token;
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("browser", adapter);
        binding.put("instanceId", instance);
        binding.put("profileId", profile);
        binding.put("tabId", tabId);
        binding.put("sessionToken", token);
        binding.put("audible", audible);
        binding.put("muted", muted);
        binding.put("volumeCapability", safe(volumeCapability));
        binding.put("captureStreamKey", safe(captureStreamKey));
        binding.put("captureApplicationId", "io.github.ilyaandreev05.audiomatrix.chrome-tab-capture");
        binding.put("nodeName", safe(captureStreamKey).isBlank() ? "" : "audio-matrix-chrome-" + captureStreamKey);
        binding.put("applicationRuleId", safe(applicationRuleId));
        binding.put("parentApplicationId", safe(parentApplicationId));
        binding.put("parentProcessName", safe(parentProcessName));
        String selector = safe(captureStreamKey).isBlank() ? "browser-tab:" + stableKey : "capture:" + captureStreamKey;
        return new InstanceSnapshot("browser_tabs", stableKey, firstNonBlank(title, stableKey), selector,
                List.of("FL", "FR"), binding, Confidence.HIGH);
    }

    public static String stableNodeId(String generatorId, String instanceKey, String role) {
        return "generated-" + UUID.nameUUIDFromBytes((generatorId + "\u0000" + instanceKey + "\u0000" + role)
                .getBytes(StandardCharsets.UTF_8));
    }

    public static String stableCompositeId(String generatorId, String instanceKey) {
        return "subgraph-" + UUID.nameUUIDFromBytes((generatorId + "\u0000" + instanceKey + "\u0000composite")
                .getBytes(StandardCharsets.UTF_8));
    }

    public static RuntimeInstance requireInstance(ReconcileResult result, String generatorId, String instanceKey,
                                                  boolean requireAvailable) {
        RuntimeInstance instance = result.instances().get(new InstanceAddress(generatorId, instanceKey));
        if (instance == null) throw new IllegalArgumentException("Generated instance does not exist: " + instanceKey);
        if (requireAvailable && !instance.available())
            throw new IllegalArgumentException("Generated instance is no longer available: " + instanceKey);
        return instance;
    }

    public static DynamicGeneratorNode generator(ReconcileResult result, String generatorId) {
        return result.configuredGraph().nodes().stream()
                .filter(DynamicGeneratorNode.class::isInstance).map(DynamicGeneratorNode.class::cast)
                .filter(node -> node.id().equals(generatorId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Dynamic generator does not exist: " + generatorId));
    }

    /** Returns the generated child Gain node; callers resolve the canonical app/endpoint setting separately. */
    public static GainNode generatedGain(ReconcileResult result, String generatorId, String instanceKey) {
        RuntimeInstance instance = requireInstance(result, generatorId, instanceKey, false);
        Set<String> generatedIds = new LinkedHashSet<>(instance.nodeIds().values());
        return result.effectiveGraph().nodes().stream()
                .filter(GainNode.class::isInstance).map(GainNode.class::cast)
                .filter(node -> generatedIds.contains(node.id())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Generated instance has no Gain node: " + instanceKey));
    }

    public static String generatedGainNodeId(ReconcileResult result, String generatorId, String instanceKey) {
        return generatedGain(result, generatorId, instanceKey).id();
    }

    public static String generatedRegistry(ReconcileResult result, String generatorId) {
        return generator(result, generatorId).registry();
    }

    public static String generatedNodeId(ReconcileResult result, String generatorId, String instanceKey, String role) {
        RuntimeInstance instance = requireInstance(result, generatorId, instanceKey, false);
        String id = instance.nodeIds().get(role);
        if (id == null) throw new IllegalArgumentException("Generated role does not exist: " + role);
        return id;
    }

    public static String stableOutputPortId(String generatorId, String deviceIdentityFingerprint) {
        return "generated-output-" + UUID.nameUUIDFromBytes((generatorId + "\u0000" + deviceIdentityFingerprint + "\u0000switch-port")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Creates the persisted stable-key record without any runtime bindings or inferred selection. */
    public static Map<String, Object> seedInstanceRecord(String generatorId, String stableKey, List<String> roles) {
        if (generatorId == null || generatorId.isBlank() || stableKey == null || stableKey.isBlank())
            throw new IllegalArgumentException("Generator ID and stable instance key are required");
        if (roles == null || roles.isEmpty()) throw new IllegalArgumentException("An instance needs template roles");
        Map<String, String> nodeIds = new LinkedHashMap<>();
        for (String role : roles) {
            if (role == null || role.isBlank()) throw new IllegalArgumentException("Template role must not be blank");
            nodeIds.put(role, stableNodeId(generatorId, stableKey, role));
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("node_ids", nodeIds);
        record.put("composite_id", stableCompositeId(generatorId, stableKey));
        record.put("overrides", new LinkedHashMap<String, Object>());
        return record;
    }

    public static Map<String, Object> seedOutputInstanceRecord(String generatorId, DeviceIdentity identity) {
        if (identity == null || !identity.reliable())
            throw new IllegalArgumentException("Only a reliable DeviceIdentity can seed an output instance");
        return seedInstanceRecord(generatorId, "device:" + identity.fingerprint(), List.of("gain", "output"));
    }
    public static ReconcileResult reconcile(AudioGraph configuredGraph,
                                           Map<String, List<InstanceSnapshot>> snapshotsByGeneratorId) {
        return reconcile(configuredGraph, snapshotsByGeneratorId, Map.of(), null, null);
    }

    public static ReconcileResult reconcile(AudioGraph configuredGraph,
                                           Map<String, List<InstanceSnapshot>> snapshotsByGeneratorId,
                                           ApplicationGainResolver applicationGainResolver) {
        return reconcile(configuredGraph, snapshotsByGeneratorId, Map.of(), applicationGainResolver, null);
    }

    /** parentCompositeIds associates nested generators with their containing instance composite. */
    public static ReconcileResult reconcile(AudioGraph configuredGraph,
                                           Map<String, List<InstanceSnapshot>> snapshotsByGeneratorId,
                                           Map<String, String> parentCompositeIds,
                                           ApplicationGainResolver applicationGainResolver) {
        return reconcile(configuredGraph, snapshotsByGeneratorId, parentCompositeIds, applicationGainResolver, null);
    }

    public static ReconcileResult reconcile(AudioGraph configuredGraph,
                                           Map<String, List<InstanceSnapshot>> snapshotsByGeneratorId,
                                           Map<String, String> parentCompositeIds,
                                           ApplicationGainResolver applicationGainResolver,
                                           EndpointGainResolver endpointGainResolver) {
        Objects.requireNonNull(configuredGraph, "configuredGraph");
        Map<String, List<InstanceSnapshot>> snapshots = snapshotsByGeneratorId == null ? Map.of() : snapshotsByGeneratorId;
        List<GraphNode> configuredNodes = new ArrayList<>(configuredGraph.nodes());
        List<GraphNode> effectiveNodes = new ArrayList<>(configuredGraph.nodes());
        List<GraphConnection> effectiveConnections = new ArrayList<>();
        List<GraphSubgraph> generatedSubgraphs = new ArrayList<>();
        Map<InstanceAddress, RuntimeInstance> runtimeInstances = new LinkedHashMap<>();
        Set<String> generatorIds = configuredGraph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(GraphNode::id).collect(java.util.stream.Collectors.toSet());
        effectiveConnections.addAll(configuredGraph.connections().stream().filter(edge ->
                !generatorIds.contains(edge.fromNode()) && !generatorIds.contains(edge.toNode())).toList());
        Map<String, String> parents = parentCompositeIds == null ? Map.of() : parentCompositeIds;
        boolean changed = false;
        for (int generatorIndex = 0; generatorIndex < configuredNodes.size(); generatorIndex++) {
            if (!(configuredNodes.get(generatorIndex) instanceof DynamicGeneratorNode original)) continue;
            List<InstanceSnapshot> incoming = snapshots.getOrDefault(original.id(), List.of());
            Map<String, InstanceSnapshot> observed = mergeSnapshots(original, incoming);
            Map<String, Object> records = mutableObject(original.instances());
            Map<String, Object> normalizedRecords = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : records.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) continue;
                normalizedRecords.put(entry.getKey(), deep(entry.getValue()));
            }
            for (Map.Entry<String, InstanceSnapshot> entry : observed.entrySet()) {
                Map<String, Object> record = object(normalizedRecords.get(entry.getKey()));
                Map<String, String> ids = nodeIds(record.get("node_ids"));
                for (Map<String, Object> templateNode : original.templateNodes()) {
                    String role = text(templateNode.get("id"));
                    if (!role.isBlank()) ids.putIfAbsent(role, stableNodeId(original.id(), entry.getKey(), role));
                }
                record.put("node_ids", ids);
                record.putIfAbsent("composite_id", stableCompositeId(original.id(), entry.getKey()));
                record.putIfAbsent("overrides", new LinkedHashMap<String, Object>());
                normalizedRecords.put(entry.getKey(), record);
            }
            if (!original.retainOnDisappear()) {
                for (String instanceKey : List.copyOf(normalizedRecords.keySet())) {
                    if (!observed.containsKey(instanceKey)) normalizedRecords.remove(instanceKey);
                }
            }
            boolean generatorChanged = !deepEquals(normalizedRecords, original.instances());
            changed |= generatorChanged;
            DynamicGeneratorNode current = generatorChanged ? original.withInstances(normalizedRecords) : original;
            configuredNodes.set(generatorIndex, current);
            for (int i = 0; i < effectiveNodes.size(); i++)
                if (current.id().equals(effectiveNodes.get(i).id())) { effectiveNodes.set(i, current); break; }
            List<Target> targets = targets(configuredGraph, original);
            AudioGraph template = templateGraph(original);
            for (Map.Entry<String, Object> recordEntry : normalizedRecords.entrySet()) {
                String instanceKey = recordEntry.getKey();
                Map<String, Object> record = object(recordEntry.getValue());
                InstanceSnapshot observation = observed.get(instanceKey);
                if ("outputs".equals(original.registry()))
                    changed |= ensureOutputSwitchPort(configuredNodes, effectiveNodes, current, instanceKey, observation);
                Map<String, String> ids = nodeIds(record.get("node_ids"));
                Map<String, Object> overrides = object(record.get("overrides"));
                boolean available = observation != null && instanceAvailable(observation);
                boolean bindingAvailable = observation != null && bindingAvailable(observation);
                boolean controlAvailable = observation != null && controlAvailable(original, observation);
                Confidence confidence = observation == null ? confidenceFromKey(instanceKey) : observation.confidence();
                Map<String, Object> runtimeBinding = observation == null ? Map.of() : observation.runtimeBinding();
                RuntimeInstance runtime = new RuntimeInstance(original.id(), instanceKey, ids,
                        text(record.get("composite_id")), available, bindingAvailable, controlAvailable,
                        confidence, runtimeBinding, overrides);
                runtimeInstances.put(new InstanceAddress(original.id(), instanceKey), runtime);
                List<GraphNode> children = materialize(original, template, observation, instanceKey, ids, overrides,
                        applicationGainResolver, endpointGainResolver);
                effectiveNodes.addAll(children);
                for (GraphConnection edge : template.connections()) {
                    String from = ids.get(edge.fromNode()), to = ids.get(edge.toNode());
                    if (from == null || to == null) continue;
                    effectiveConnections.add(new GraphConnection(stableConnectionId(original.id(), instanceKey,
                            "template:" + edge.id()), from, edge.fromPort(), to, edge.toPort(), bindingAvailable && edge.enabled(), edge.gain()));
                }
                addBoundaryConnections(original, template, instanceKey, ids, targets, observation, bindingAvailable,
                        configuredNodes, effectiveConnections);
                List<String> memberIds = children.stream().map(GraphNode::id).toList();
                String compositeId = text(record.get("composite_id"));
                generatedSubgraphs.add(new GraphSubgraph(compositeId, instanceName(observation, instanceKey, overrides),
                        memberIds, false, blankToNull(parents.get(original.id())), original.id()));
            }
        }
        AudioGraph saved = new AudioGraph(configuredGraph.id(), configuredGraph.name(), configuredNodes, configuredGraph.connections());
        AudioGraph effective = new AudioGraph(configuredGraph.id(), configuredGraph.name(), effectiveNodes, effectiveConnections);
        return new ReconcileResult(saved, effective, runtimeInstances, generatedSubgraphs, changed);
    }

    /** Mutates only one generator-instance override in the saved route map. */
    public static boolean setInstanceOverride(Map<String, Object> config, String routeId, String generatorId,
                                              String instanceKey, String field, Object value) {
        if (!OVERRIDE_FIELDS.contains(field)) throw new IllegalArgumentException("Unsupported generated instance override: " + field);
        Map<String, Object> route = route(config, routeId);
        Object rawNodes = route.get("nodes");
        if (!(rawNodes instanceof List<?> rawList)) throw new IllegalArgumentException("Route has no graph nodes");
        @SuppressWarnings("unchecked") List<Object> nodes = (List<Object>) rawList;
        for (int index = 0; index < nodes.size(); index++) {
            Object item = nodes.get(index);
            Map<String, Object> node = object(item);
            if (!generatorId.equals(node.get("id")) || !"dynamic_generator".equals(node.get("type"))) continue;
            Map<String, Object> parameters = mutableNestedObject(node, "parameters");
            String registry = text(parameters.getOrDefault("registry", "applications"));
            if (("applications".equals(registry) || "outputs".equals(registry)) && ("gain".equals(field) || "muted".equals(field)))
                throw new IllegalArgumentException("Application/output gain and mute are stored in their canonical rule or endpoint settings");
            Map<String, Object> instances = mutableNestedObject(parameters, "instances");
            Object rawRecord = instances.get(instanceKey);
            if (!(rawRecord instanceof Map<?, ?>)) throw new IllegalArgumentException("Generated instance does not exist: " + instanceKey);
            Map<String, Object> record = mutableObject(rawRecord);
            Map<String, Object> overrides = mutableObject(record.get("overrides"));
            if (value == null) {
                if (!overrides.containsKey(field)) return false;
                overrides.remove(field);
            } else {
                validateOverrideValue(field, value);
                if (overrides.containsKey(field) && deepEquals(overrides.get(field), value)) return false;
                overrides.put(field, deep(value));
            }
            record.put("overrides", overrides);
            instances.put(instanceKey, record);
            parameters.put("instances", instances);
            node.put("parameters", parameters);
            nodes.set(index, node);
            return true;
        }
        throw new IllegalArgumentException("Dynamic generator does not exist: " + generatorId);
    }

    /** A sparse getter that preserves explicit Boolean.FALSE as a present value. */
    public static java.util.Optional<Object> instanceOverride(Map<String, Object> config, String routeId,
                                                              String generatorId, String instanceKey, String field) {
        Map<String, Object> route = route(config, routeId);
        if (!(route.get("nodes") instanceof List<?> nodes)) return java.util.Optional.empty();
        for (Object item : nodes) {
            Map<String, Object> node = object(item);
            if (!generatorId.equals(node.get("id")) || !"dynamic_generator".equals(node.get("type"))) continue;
            Map<String, Object> parameters = object(node.get("parameters"));
            Map<String, Object> instances = object(parameters.get("instances"));
            Map<String, Object> record = object(instances.get(instanceKey));
            Map<String, Object> overrides = object(record.get("overrides"));
            return overrides.containsKey(field) ? java.util.Optional.ofNullable(deep(overrides.get(field)))
                    : java.util.Optional.empty();
        }
        throw new IllegalArgumentException("Dynamic generator does not exist: " + generatorId);
    }

    /** Reads the saved template baseline, not a live/reconciled Gain value. */
    public static GainState instanceGainBaseline(Map<String, Object> config, String routeId, String generatorId) {
        Map<String, Object> route = route(config, routeId);
        AudioGraph graph = GraphCodec.fromMap(route);
        DynamicGeneratorNode generator = graph.nodes().stream().filter(DynamicGeneratorNode.class::isInstance)
                .map(DynamicGeneratorNode.class::cast).filter(node -> node.id().equals(generatorId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Dynamic generator does not exist: " + generatorId));
        if (!"browser_tabs".equals(generator.registry()))
            throw new IllegalArgumentException("Per-instance gain overrides are available only for browser tabs");
        AudioGraph template = templateGraph(generator);
        GainNode gain = template.nodes().stream().filter(GainNode.class::isInstance).map(GainNode.class::cast)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Browser tab template has no Gain node"));
        return new GainState(generator.initialGain(), gain.muted());
    }

    public static boolean instanceMutedBaseline(Map<String, Object> config, String routeId, String generatorId) {
        return instanceGainBaseline(config, routeId, generatorId).muted();
    }

    /** Rehomes saved generator groups and every explicit instance group override. */
    public static boolean rehomeInstanceGroupOverrides(Map<String, Object> config, String oldGroupId,
                                                       String newGroupId) {
        if (oldGroupId == null || oldGroupId.isBlank()) return false;
        boolean changed = false;
        if (config.get("route_library") instanceof List<?> rawRoutes) {
            @SuppressWarnings("unchecked") List<Object> routes = (List<Object>) rawRoutes;
            for (int i = 0; i < routes.size(); i++) {
                Map<String, Object> route = object(routes.get(i));
                if (rehomeGraphMap(route, oldGroupId, newGroupId)) {
                    routes.set(i, route);
                    changed = true;
                }
            }
        }
        if (config.get("saved_copies") instanceof List<?> rawCopies) {
            @SuppressWarnings("unchecked") List<Object> copies = (List<Object>) rawCopies;
            for (int i = 0; i < copies.size(); i++) {
                Map<String, Object> copy = object(copies.get(i));
                Map<String, Object> route = object(copy.get("route"));
                if (rehomeGraphMap(route, oldGroupId, newGroupId)) {
                    copy.put("route", route);
                    copies.set(i, copy);
                    changed = true;
                }
            }
        }
        if (config.get("common_vibro_graph") instanceof Map<?, ?> rawCommon) {
            Map<String, Object> common = object(rawCommon);
            if (rehomeGraphMap(common, oldGroupId, newGroupId)) {
                config.put("common_vibro_graph", common);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean rehomeGraphMap(Map<String, Object> graph, String oldGroupId, String newGroupId) {
        if (!(graph.get("nodes") instanceof List<?> rawNodes)) return false;
        @SuppressWarnings("unchecked") List<Object> nodes = (List<Object>) rawNodes;
        boolean changed = false;
        for (int i = 0; i < nodes.size(); i++) {
            Map<String, Object> node = object(nodes.get(i));
            if (!"dynamic_generator".equals(node.get("type"))) continue;
            Map<String, Object> parameters = object(node.get("parameters"));
            boolean generatorChanged = false;
            if (oldGroupId.equals(parameters.get("group_id"))) {
                parameters.put("group_id", newGroupId == null ? "" : newGroupId);
                generatorChanged = true;
            }
            Map<String, Object> instances = mutableObject(parameters.get("instances"));
            boolean instancesChanged = false;
            for (Map.Entry<String, Object> instance : instances.entrySet()) {
                Map<String, Object> record = object(instance.getValue());
                Map<String, Object> overrides = mutableObject(record.get("overrides"));
                if (!oldGroupId.equals(overrides.get("group_id"))) continue;
                if (newGroupId == null || newGroupId.isBlank()) overrides.remove("group_id");
                else overrides.put("group_id", newGroupId);
                record.put("overrides", overrides);
                instance.setValue(record);
                instancesChanged = true;
            }
            if (instancesChanged) {
                parameters.put("instances", instances);
                generatorChanged = true;
            }
            if (generatorChanged) {
                node.put("parameters", parameters);
                nodes.set(i, node);
                changed = true;
            }
        }
        return changed;
    }

    private static Map<String, InstanceSnapshot> mergeSnapshots(DynamicGeneratorNode generator,
                                                                 List<InstanceSnapshot> snapshots) {
        Map<String, InstanceSnapshot> result = new LinkedHashMap<>();
        if (snapshots == null) return result;
        if ("applications".equals(generator.registry()) && EventSoundStudioSource.SELECTOR.equals(generator.selector()))
            return result;
        for (InstanceSnapshot snapshot : snapshots) {
            if (snapshot == null || !generator.registry().equals(snapshot.registry())
                    || !matchesGenerator(generator, snapshot) || excluded(generator, snapshot)) continue;
            InstanceSnapshot previous = result.get(snapshot.stableKey());
            result.put(snapshot.stableKey(), previous == null ? snapshot : merge(previous, snapshot));
        }
        return result;
    }

    private static boolean matchesGenerator(DynamicGeneratorNode generator, InstanceSnapshot snapshot) {
        String selector = generator.selector();
        if ("applications".equals(generator.registry())) {
            if ("application:unassigned".equals(selector) || "application:*".equals(selector)) return true;
            if (selector.startsWith("application:"))
                return selector.substring("application:".length()).equals(text(snapshot.runtimeBinding().get("applicationId")));
            if (selector.startsWith("process:"))
                return selector.substring("process:".length()).equals(text(snapshot.runtimeBinding().get("processName")));
            return false;
        }
        if ("outputs".equals(generator.registry())) {
            if ("device:*".equals(selector) || "device:all".equals(selector)) return true;
            if (!selector.startsWith("device:") || snapshot.confidence() != Confidence.HIGH) return false;
            String fingerprint = identityFingerprint(snapshot);
            return !fingerprint.isBlank() && selector.substring("device:".length()).equals(fingerprint);
        }
        if ("browser_tabs".equals(generator.registry())) {
            if ("browser:*".equals(selector) || "browser_tabs:*".equals(selector)) return true;
            if (selector.startsWith("browser:"))
                return selector.substring("browser:".length()).equals(text(snapshot.runtimeBinding().get("browser")));
            return false;
        }
        return false;
    }


    private static InstanceSnapshot merge(InstanceSnapshot first, InstanceSnapshot next) {
        if (!"applications".equals(first.registry())) return next;
        boolean firstPcm = Boolean.TRUE.equals(first.runtimeBinding().get("hasPcmBinding"));
        boolean nextPcm = Boolean.TRUE.equals(next.runtimeBinding().get("hasPcmBinding"));
        InstanceSnapshot primary = !firstPcm && nextPcm ? next : first;
        Map<String, Object> binding = mutableObject(primary.runtimeBinding());
        List<Object> streams = new ArrayList<>();
        for (InstanceSnapshot item : List.of(first, next)) {
            if (item.runtimeBinding().get("streams") instanceof List<?> list) streams.addAll(list);
        }
        binding.put("streams", streams);
        binding.put("hasPcmBinding", firstPcm || nextPcm);
        binding.put("audioMatrixOwned", Boolean.TRUE.equals(first.runtimeBinding().get("audioMatrixOwned"))
                || Boolean.TRUE.equals(next.runtimeBinding().get("audioMatrixOwned")));
        return new InstanceSnapshot(primary.registry(), primary.stableKey(), primary.displayName(), primary.selector(),
                primary.channels(), binding, first.confidence().ordinal() < next.confidence().ordinal() ? first.confidence() : next.confidence());
    }

    private static boolean excluded(DynamicGeneratorNode generator, InstanceSnapshot snapshot) {
        if ("applications".equals(snapshot.registry())) {
            Map<String, Object> binding = snapshot.runtimeBinding();
            String appId = text(binding.get("applicationId"));
            if ("io.github.ilyaandree05.eventsoundstudio".equals(appId)
                    || "application:io.github.ilyaandree05.eventsoundstudio".equals(snapshot.selector())
                    || appId.equals("io.github.ilyaandree05.audiomatrix")
                    || appId.startsWith("io.github.ilyaandree05.audiomatrix.")
                    || Boolean.TRUE.equals(binding.get("audioMatrixOwned"))) return true;
        }
        for (String selector : generator.excludedSelectors()) {
            if (selector.equals(snapshot.stableKey()) || selector.equals(snapshot.selector())) return true;
            if (selector.equals("application:" + text(snapshot.runtimeBinding().get("applicationId")))
                    || selector.equals("process:" + text(snapshot.runtimeBinding().get("processName")))) return true;
        }
        return false;
    }

    private static List<GraphNode> materialize(DynamicGeneratorNode generator, AudioGraph template,
                                               InstanceSnapshot observation, String instanceKey,
                                               Map<String, String> ids, Map<String, Object> overrides,
                                               ApplicationGainResolver resolver,
                                               EndpointGainResolver endpointGainResolver) {
        List<GraphNode> result = new ArrayList<>();
        String display = instanceName(observation, instanceKey, overrides);
        List<String> observedChannels = observation == null ? List.of() : observation.channels();
        String selector = observation == null ? offlineSelector(generator.registry(), instanceKey) : observation.selector();
        for (GraphNode node : template.nodes()) {
            String role = node.id();
            String id = ids.getOrDefault(role, stableNodeId(generator.id(), instanceKey, role));
            List<String> nodeChannels = observedChannels.isEmpty() ? channels(node) : observedChannels;
            String name = childName(display, node.name(), role);
            if (node instanceof SourceNode source) {
                String childSelector = selector.isBlank() ? source.selector() : selector;
                result.add(new SourceNode(id, name, childSelector, nodeChannels));
            } else if (node instanceof GainNode gain) {
                double value = gain.gain();
                boolean muted = gain.muted();
                if ("applications".equals(generator.registry())) {
                    value = generator.initialGain();
                    InstanceSnapshot resolverInput = observation == null
                            ? offlineApplicationSnapshot(instanceKey, selector, nodeChannels) : observation;
                    GainState resolved = resolver == null ? null : resolver.resolve(selector, resolverInput);
                    if (resolved != null) { value = resolved.gain(); muted = resolved.muted(); }
                } else if ("outputs".equals(generator.registry())) {
                    GainState saved = endpointGainResolver == null ? null : endpointGainResolver.resolve(instanceKey, observation);
                    if (saved != null) {
                        value = saved.gain();
                        muted = saved.muted();
                    } else if (observation != null) {
                        Map<String, Object> endpoint = observation.runtimeBinding();
                        value = number(endpoint.get("savedEndpointGain"),
                                number(endpoint.get("volume"), generator.initialGain()));
                        muted = Boolean.TRUE.equals(endpoint.getOrDefault("savedEndpointMuted", endpoint.get("muted")));
                    } else {
                        value = generator.initialGain();
                    }
                    value = generator.initialGain();
                    if (overrides.containsKey("gain")) value = number(overrides.get("gain"), value);
                    if (overrides.containsKey("muted")) muted = Boolean.TRUE.equals(overrides.get("muted"));
                }
                result.add(new GainNode(id, name, nodeChannels, value, muted, gain.endpointNodeId()));
            } else if (node instanceof OutputNode output) {
                String deviceId = text(observation == null ? null : observation.runtimeBinding().get("deviceId"));
                if (deviceId.isBlank()) deviceId = output.deviceId();
                if (deviceId.isBlank() || "device:unbound".equals(deviceId)) deviceId = fallbackDeviceId(instanceKey);
                result.add(new OutputNode(id, name, deviceId, nodeChannels, output.channelMatrix()));
            } else {
                GraphNode copy = GraphNodeCopies.copy(node, id, ids);
                result.add(copy);
            }
        }
        return result;
    }

    private static String offlineSelector(String registry, String instanceKey) {
        if ("applications".equals(registry)) {
            if (instanceKey.startsWith("app:")) return "application:" + instanceKey.substring("app:".length());
            if (instanceKey.startsWith("process:")) return "process:" + instanceKey.substring("process:".length());
            return "metadata:" + instanceKey.substring("stream:".length());
        }
        if ("browser_tabs".equals(registry)) return "capture:unavailable-" + sha256(instanceKey);
        return "";
    }

    private static InstanceSnapshot offlineApplicationSnapshot(String instanceKey, String selector, List<String> channels) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("applicationId", instanceKey.startsWith("app:") ? instanceKey.substring("app:".length()) : "");
        binding.put("processName", instanceKey.startsWith("process:") ? instanceKey.substring("process:".length()) : "");
        binding.put("audioMatrixOwned", false);
        return new InstanceSnapshot("applications", instanceKey, instanceKey, selector, channels, binding,
                confidenceFromKey(instanceKey));
    }

    private static void addBoundaryConnections(DynamicGeneratorNode generator, AudioGraph template, String instanceKey,
                                               Map<String, String> ids, List<Target> targets,
                                               InstanceSnapshot observation, boolean bindingAvailable,
                                               List<GraphNode> graphNodes, List<GraphConnection> result) {
        if (targets.isEmpty()) return;
        boolean outputRegistry = "outputs".equals(generator.registry());
        Set<String> connectedInputs = new LinkedHashSet<>();
        Set<String> connectedOutputs = new LinkedHashSet<>();
        template.connections().stream().filter(GraphConnection::enabled).forEach(edge -> {
            connectedInputs.add(edge.toNode() + "\u0000" + edge.toPort());
            connectedOutputs.add(edge.fromNode() + "\u0000" + edge.fromPort());
        });
        Map<String, GraphNode> graphNodesById = new LinkedHashMap<>();
        graphNodes.forEach(node -> graphNodesById.put(node.id(), node));
        String fingerprint = outputRegistry ? outputFingerprint(observation, instanceKey) : "";
        String outputPort = fingerprint.isBlank() ? "" : stableOutputPortId(generator.id(), fingerprint);
        boolean selectedDirectOutput = observation != null && observation.confidence() == Confidence.HIGH
                && generator.selector().startsWith("device:") && !"device:*".equals(generator.selector())
                && generator.selector().equals(observation.selector())
                && identityReliable(observation);
        for (GraphNode node : template.nodes()) {
            if (outputRegistry) {
                for (AudioPort input : node.inputs()) {
                    if (connectedInputs.contains(node.id() + "\u0000" + input.id())) continue;
                    for (Target target : targets) {
                        GraphNode anchor = graphNodesById.get(target.nodeId());
                        if (anchor instanceof SwitchNode) {
                            if (outputPort.isBlank()) continue;
                            result.add(new GraphConnection(stableConnectionId(generator.id(), instanceKey,
                                    "switch-output:" + outputPort + ":" + node.id() + ":" + input.id()),
                                    anchor.id(), outputPort, ids.get(node.id()), input.id(), bindingAvailable, 1));
                        } else {
                            AudioPort sourcePort = outputPort(anchor, target.portId());
                            if (sourcePort == null) continue;
                            boolean enabled = bindingAvailable && selectedDirectOutput;
                            result.add(new GraphConnection(stableConnectionId(generator.id(), instanceKey,
                                    "direct-output:" + anchor.id() + ":" + sourcePort.id() + ":" + node.id() + ":" + input.id()),
                                    anchor.id(), sourcePort.id(), ids.get(node.id()), input.id(), enabled, 1));
                        }
                    }
                }
            } else {
                for (AudioPort output : node.outputs()) {
                    if (connectedOutputs.contains(node.id() + "\u0000" + output.id())) continue;
                    for (Target target : targets) result.add(new GraphConnection(
                            stableConnectionId(generator.id(), instanceKey, "target-out:" + node.id() + ":" + output.id()
                                    + ":" + target.nodeId() + ":" + target.portId()),
                            ids.get(node.id()), output.id(), target.nodeId(), target.portId(), bindingAvailable, 1));
                }
            }
        }
    }
    private static boolean ensureOutputSwitchPort(List<GraphNode> configuredNodes, List<GraphNode> effectiveNodes,
                                                  DynamicGeneratorNode generator, String instanceKey,
                                                  InstanceSnapshot observation) {
        for (GraphNode node : configuredNodes)
            if (generator.id().equals(node.id()) && node instanceof DynamicGeneratorNode updated) {
                generator = updated;
                break;
            }
        Map<String, Object> connectTo = generator.connectTo();
        String switchId = text(connectTo.get("node_id"));
        String inputPortId = text(connectTo.get("port_id"));
        if (switchId.isBlank() || inputPortId.isBlank()) return false;
        int switchIndex = -1;
        for (int i = 0; i < configuredNodes.size(); i++) {
            if (switchId.equals(configuredNodes.get(i).id())) { switchIndex = i; break; }
        }
        if (switchIndex < 0) return false;
        GraphNode target = configuredNodes.get(switchIndex);
        if (!(target instanceof SwitchNode switchNode)) return false;
        if (switchNode.inputs().stream().noneMatch(port -> port.id().equals(inputPortId)))
            throw new IllegalArgumentException("Output generator connect_to must name a SwitchNode input port");
        String fingerprint = outputFingerprint(observation, instanceKey);
        if (fingerprint.isBlank()) return false;
        List<String> outputPorts = new ArrayList<>(switchNode.outputs().stream().map(AudioPort::id).toList());
        boolean newOutputPort = !outputPorts.contains(portId);
        boolean changed = newOutputPort;
        if (newOutputPort) outputPorts.add(portId);
        List<String> activeLinks = new ArrayList<>(switchNode.activeLinks());
        String activeLink = inputPortId + "->" + portId;
        boolean autoSelectTrustedHdmi = Boolean.TRUE.equals(
                generator.parameters().get("auto_select_trusted_hdmi_on_first_discovery"));
        boolean selectInitial = newOutputPort && autoSelectTrustedHdmi && observation != null
                && observation.confidence() == Confidence.HIGH && identityReliable(observation)
                && Boolean.TRUE.equals(observation.runtimeBinding().get("selected"));
        if (selectInitial && !activeLinks.contains(activeLink)) activeLinks.add(activeLink);
        if (selectInitial) {
            Map<String, Object> parameters = new LinkedHashMap<>(generator.parameters());
            parameters.put("auto_select_trusted_hdmi_on_first_discovery", false);
            DynamicGeneratorNode updatedGenerator = new DynamicGeneratorNode(generator.id(), generator.name(),
                    generator.selector(), generator.channels(), parameters);
            for (int i = 0; i < configuredNodes.size(); i++) {
                if (generator.id().equals(configuredNodes.get(i).id())) {
                    configuredNodes.set(i, updatedGenerator);
                    break;
                }
            }
            for (int i = 0; i < effectiveNodes.size(); i++) {
                if (generator.id().equals(effectiveNodes.get(i).id())) {
                    effectiveNodes.set(i, updatedGenerator);
                    break;
                }
            }
            changed = true;
        }
        if (selectInitial) changed = true;
        if (changed) {
            List<String> channels = switchNode.inputs().getFirst().channels();
            SwitchNode updated = new SwitchNode(switchNode.id(), switchNode.name(), channels, outputPorts, activeLinks);
            configuredNodes.set(switchIndex, updated);
            for (int i = 0; i < effectiveNodes.size(); i++)
                if (switchId.equals(effectiveNodes.get(i).id())) { effectiveNodes.set(i, updated); break; }
        }
        return changed;
    }

    private static boolean instanceAvailable(InstanceSnapshot snapshot) {
        return !"applications".equals(snapshot.registry())
                || Boolean.TRUE.equals(snapshot.runtimeBinding().get("hasPcmBinding"));
    }

    private static boolean bindingAvailable(InstanceSnapshot snapshot) {
        return switch (snapshot.registry()) {
            case "applications" -> Boolean.TRUE.equals(snapshot.runtimeBinding().get("hasPcmBinding"))
                    && snapshot.confidence() == Confidence.HIGH
                    && (snapshot.selector().startsWith("application:") || snapshot.selector().startsWith("process:"));
            case "outputs" -> snapshot.confidence() == Confidence.HIGH && !text(snapshot.runtimeBinding().get("deviceId")).isBlank()
                    && !identityFingerprint(snapshot).isBlank();
            case "browser_tabs" -> !text(snapshot.runtimeBinding().get("captureStreamKey")).isBlank()
                    && ("audio-matrix-chrome-" + text(snapshot.runtimeBinding().get("captureStreamKey")))
                    .equals(text(snapshot.runtimeBinding().get("nodeName")));
            default -> false;
        };
    }

    private static boolean controlAvailable(DynamicGeneratorNode generator, InstanceSnapshot snapshot) {
        if ("browser_tabs".equals(generator.registry()))
            return bindingAvailable(snapshot) && !text(snapshot.runtimeBinding().get("applicationRuleId")).isBlank()
                    && "captured".equals(snapshot.runtimeBinding().get("volumeCapability"));
        return bindingAvailable(snapshot);
    }


    private static boolean identityReliable(InstanceSnapshot snapshot) {
        if (snapshot == null) return false;
        return DeviceIdentity.fromMap(object(snapshot.runtimeBinding().get("identity"))).reliable();
    }
    private static String identityFingerprint(InstanceSnapshot snapshot) {
        if (snapshot == null) return "";
        return text(object(snapshot.runtimeBinding().get("identity")).get("fingerprint"));
    }

    private static String outputFingerprint(InstanceSnapshot snapshot, String instanceKey) {
        String fingerprint = identityFingerprint(snapshot);
        if (!fingerprint.isBlank()) return fingerprint;
        if (!instanceKey.startsWith("device:")) return "";
        String suffix = instanceKey.substring("device:".length());
        int separator = suffix.indexOf(':');
        return separator < 0 ? suffix : suffix.substring(0, separator);
    }

    private static AudioPort outputPort(GraphNode node, String portId) {
        if (node == null) return null;
        return node.outputs().stream().filter(port -> port.id().equals(portId)).findFirst().orElse(null);
    }

    private static List<Target> targets(AudioGraph graph, DynamicGeneratorNode generator) {
        Map<String, Object> configured = generator.connectTo();
        String nodeId = text(configured.get("node_id"));
        String portId = text(configured.get("port_id"));
        if (!nodeId.isBlank() && !portId.isBlank()) return List.of(new Target(nodeId, portId));
        List<Target> legacy = graph.connections().stream().filter(edge -> edge.fromNode().equals(generator.id()))
                .map(edge -> new Target(edge.toNode(), edge.toPort())).distinct().toList();
        return legacy;
    }

    private static AudioGraph templateGraph(DynamicGeneratorNode generator) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("id", "template-" + generator.id());
        encoded.put("name", generator.name() + " template");
        encoded.put("nodes", generator.templateNodes());
        encoded.put("connections", generator.templateConnections());
        return GraphCodec.fromMap(encoded);
    }

    private static Map<String, Object> streamBinding(AudioNode stream) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("nodeId", stream.id());
        binding.put("name", safe(stream.name()));
        binding.put("description", safe(stream.description()));
        binding.put("volume", finiteLevel(stream.volume(), 1.0));
        binding.put("muted", stream.muted());
        return binding;
    }

    private static List<String> channels(AudioNode node) {
        if (node.identity() != null && !node.identity().channelPositions().isBlank()) {
            List<String> positions = java.util.Arrays.stream(node.identity().channelPositions().split(","))
                    .map(String::trim).filter(value -> !value.isBlank()).toList();
            if (!positions.isEmpty()) return positions;
        }
        if (node.channels() == 1) return List.of("MONO");
        if (node.channels() == 2) return List.of("FL", "FR");
        List<String> channels = new ArrayList<>(node.channels());
        for (int i = 0; i < node.channels(); i++) channels.add("CH" + (i + 1));
        return List.copyOf(channels);
    }

    private static List<String> channels(GraphNode node) {
        if (!node.outputs().isEmpty()) return node.outputs().getFirst().channels();
        if (!node.inputs().isEmpty()) return node.inputs().getFirst().channels();
        if (node instanceof DynamicGeneratorNode generator) return generator.channels();
        throw new IllegalArgumentException("Generated template node has no audio channels: " + node.id());
    }

    private static String childName(String display, String templateName, String role) {
        if ("source".equals(role) || "output".equals(role)) return display;
        if ("gain".equals(role)) return display + " · volume";
        return display + " · " + firstNonBlank(templateName, role);
    }

    private static String instanceName(InstanceSnapshot observation, String instanceKey, Map<String, Object> overrides) {
        Object override = overrides.get("display_name");
        if (overrides.containsKey("display_name") && override instanceof String value && !value.isBlank()) return value;
        return observation == null ? instanceKey : firstNonBlank(observation.displayName(), instanceKey);
    }

    private static String fallbackDeviceId(String instanceKey) {
        if (instanceKey.startsWith("device:")) return "device:" + instanceKey.substring("device:".length());
        return "device:unavailable-" + sha256(instanceKey);
    }

    private static String stableConnectionId(String generatorId, String instanceKey, String role) {
        return "generated-link-" + UUID.nameUUIDFromBytes((generatorId + "\u0000" + instanceKey + "\u0000" + role)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static Confidence confidenceFromKey(String key) {
        return key.startsWith("stream:") ? Confidence.LOW : Confidence.HIGH;
    }

    private static void validateOverrideValue(String field, Object value) {
        switch (field) {
            case "gain" -> {
                double gain = number(value, Double.NaN);
                if (!Double.isFinite(gain) || gain < 0 || gain > 1) throw new IllegalArgumentException("Instance gain must be between 0 and 1");
            }
            case "muted", "pinned", "expanded", "show_children", "show_when_offline" -> {
                if (!(value instanceof Boolean)) throw new IllegalArgumentException("Instance override " + field + " must be boolean");
            }
            case "display_name", "icon", "group_id" -> {
                if (!(value instanceof String)) throw new IllegalArgumentException("Instance override " + field + " must be a string");
            }
            case "order" -> {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()))
                    throw new IllegalArgumentException("Instance override order must be numeric");
            }
            default -> throw new IllegalArgumentException("Unsupported generated instance override: " + field);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> route(Map<String, Object> config, String routeId) {
        Object raw = config.get("route_library");
        if (raw instanceof List<?> routes) for (Object item : routes) {
            if (item instanceof Map<?, ?> map && routeId.equals(map.get("id"))) return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Route does not exist: " + routeId);
    }

    private static Map<String, Object> mutableNestedObject(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        Map<String, Object> result = mutableObject(value);
        parent.put(key, result);
        return result;
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static Map<String, Object> mutableObject(Object value) {
        return object(value);
    }

    private static Map<String, String> nodeIds(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return result;
    }

    private static Map<String, Object> immutableObject(Map<String, Object> value) {
        return java.util.Collections.unmodifiableMap(mutableObject(deep(value == null ? Map.of() : value)));
    }

    private static Object deep(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), deep(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(DynamicInstanceRegistry::deep).toList();
        return value;
    }

    private static boolean deepEquals(Object first, Object second) {
        return Objects.deepEquals(first, second);
    }

    private static double finiteLevel(double value, double fallback) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : fallback;
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static String requireIdentityPart(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private record Target(String nodeId, String portId) {}
}
