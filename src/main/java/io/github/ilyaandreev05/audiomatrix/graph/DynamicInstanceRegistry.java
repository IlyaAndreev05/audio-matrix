package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


/** Public compatibility facade for dynamic instance snapshots, reconciliation, and overrides. */
public final class DynamicInstanceRegistry {
    /** The bus role of the session system input instance. */
    public static final String SYSTEM_INPUT_BUS_ROLE = DynamicRegistrySystemInput.BUS_ROLE;

    private DynamicInstanceRegistry() {}

    public enum Confidence { HIGH, LOW }

    public record InstanceSnapshot(String registry, String stableKey, String displayName, String selector,
                                   List<String> channels, Map<String, Object> runtimeBinding,
                                   Confidence confidence) {
        public InstanceSnapshot {
            if (!Set.of("applications", "outputs", "browser_tabs").contains(registry))
                throw new IllegalArgumentException("Unsupported instance registry: " + registry);
            if (stableKey == null || stableKey.isBlank())
                throw new IllegalArgumentException("Instance stable key must not be blank");
            displayName = displayName == null || displayName.isBlank() ? stableKey : displayName;
            selector = selector == null ? "" : selector;
            channels = channels == null ? List.of() : List.copyOf(channels);
            runtimeBinding = DynamicRegistryValues.immutableObject(runtimeBinding);
            confidence = Objects.requireNonNull(confidence, "confidence");
        }
    }

    public record InstanceAddress(String generatorId, String instanceKey) {}

    public record RuntimeInstance(String generatorId, String instanceKey, Map<String, String> nodeIds,
                                 String compositeId, boolean available, boolean bindingAvailable,
                                 boolean controlAvailable, Confidence confidence,
                                 Map<String, Object> runtimeBinding, Map<String, Object> overrides) {
        public RuntimeInstance {
            nodeIds = Map.copyOf(nodeIds);
            runtimeBinding = DynamicRegistryValues.immutableObject(runtimeBinding);
            overrides = DynamicRegistryValues.immutableObject(overrides);
        }
        public boolean gainControlAvailable() { return bindingAvailable && controlAvailable; }
        public boolean muteControlAvailable() {
            return runtimeBinding.containsKey("sessionToken")
                    ? available && !DynamicRegistryValues.text(runtimeBinding.get("sessionToken")).isBlank()
                    : bindingAvailable && controlAvailable;
        }
    }

    public record GainState(double gain, boolean muted) {
        public GainState {
            if (!Double.isFinite(gain) || gain < 0 || gain > 1)
                throw new IllegalArgumentException("Generated gain must be between 0 and 1");
        }
    }

    public record ApplicationRuleResolution(String ruleId, boolean inserted) {
        public ApplicationRuleResolution {
            if (ruleId == null || ruleId.isBlank())
                throw new IllegalArgumentException("Application rule ID must not be blank");
        }
    }

    @FunctionalInterface
    public interface ApplicationGainResolver {
        GainState resolve(String selector, InstanceSnapshot instance);
    }

    @FunctionalInterface
    public interface EndpointGainResolver {
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

    public static InstanceSnapshot application(AudioNode stream) {
        return DynamicRegistrySnapshotFactory.application(stream);
    }
    public static InstanceSnapshot application(AudioNode stream, String canonicalAdapterId, String displayName) {
        return DynamicRegistrySnapshotFactory.application(stream, canonicalAdapterId, displayName);
    }

    public static Optional<ApplicationRuleResolution> ensureApplicationRule(
            Map<String, Object> config, InstanceSnapshot observation) {
        return DynamicRegistryApplicationRules.ensure(config, observation);
    }
    /** The session system input, served by one instance of the applications generator. */
    public static InstanceSnapshot systemInput() {
        return DynamicRegistrySystemInput.observation();
    }
    public static InstanceSnapshot browserParentApplication(String browser, String applicationId,
            String processName, String displayName) {
        return DynamicRegistrySnapshotFactory.browserParentApplication(browser, applicationId, processName, displayName);
    }
    public static InstanceSnapshot output(AudioNode output) {
        return DynamicRegistrySnapshotFactory.output(output, false, null, "");
    }
    public static InstanceSnapshot output(AudioNode output, boolean selected) {
        return DynamicRegistrySnapshotFactory.output(output, selected, null, "");
    }
    public static InstanceSnapshot output(AudioNode output, boolean selected, GainState savedEndpointLevel) {
        return DynamicRegistrySnapshotFactory.output(output, selected, savedEndpointLevel, "");
    }
    public static InstanceSnapshot output(AudioNode output, boolean selected, GainState savedEndpointLevel, String role) {
        return DynamicRegistrySnapshotFactory.output(output, selected, savedEndpointLevel, role);
    }
    public static InstanceSnapshot browserTab(String browser, String instanceId, String profileId, String sessionToken,
            int tabId, String title, boolean audible, boolean muted, String volumeCapability, String captureStreamKey) {
        return browserTab(browser, instanceId, profileId, sessionToken, tabId, title, audible, muted, volumeCapability,
                captureStreamKey, "");
    }
    public static InstanceSnapshot browserTab(String browser, String instanceId, String profileId, String sessionToken,
            int tabId, String title, boolean audible, boolean muted, String volumeCapability, String captureStreamKey,
            String applicationRuleId) {
        return browserTab(browser, instanceId, profileId, sessionToken, tabId, title, audible, muted, volumeCapability,
                captureStreamKey, applicationRuleId, "", "");
    }
    public static InstanceSnapshot browserTab(String browser, String instanceId, String profileId, String sessionToken,
            int tabId, String title, boolean audible, boolean muted, String volumeCapability, String captureStreamKey,
            String applicationRuleId, String parentApplicationId, String parentProcessName) {
        return DynamicRegistrySnapshotFactory.browserTab(browser, instanceId, profileId, sessionToken, tabId, title,
                audible, muted, volumeCapability, captureStreamKey, applicationRuleId, parentApplicationId,
                parentProcessName);
    }

    public static String stableNodeId(String generatorId, String instanceKey, String role) {
        return DynamicRegistryIdentity.stableNodeId(generatorId, instanceKey, role);
    }
    public static String stableCompositeId(String generatorId, String instanceKey) {
        return DynamicRegistryIdentity.stableCompositeId(generatorId, instanceKey);
    }
    public static String stableOutputPortId(String generatorId, String deviceIdentityFingerprint) {
        return DynamicRegistryIdentity.stableOutputPortId(generatorId, deviceIdentityFingerprint);
    }
    /** True when the port id names a runtime-generated MultiSwitch output branch. */
    public static boolean isGeneratedOutputPort(String portId) {
        return DynamicRegistryIdentity.isGeneratedOutputPort(portId);
    }
    public static String stableMixerInputPortId(String generatorId, String instanceKey) {
        return DynamicRegistryIdentity.stableMixerInputPortId(generatorId, instanceKey);
    }
    public static RuntimeInstance requireInstance(ReconcileResult result, String generatorId, String instanceKey,
            boolean requireAvailable) {
        return DynamicRegistryQueries.requireInstance(result, generatorId, instanceKey, requireAvailable);
    }
    public static DynamicGeneratorNode generator(ReconcileResult result, String generatorId) {
        return DynamicRegistryQueries.generator(result, generatorId);
    }
    public static GainNode generatedGain(ReconcileResult result, String generatorId, String instanceKey) {
        return DynamicRegistryQueries.generatedGain(result, generatorId, instanceKey);
    }
    public static String generatedGainNodeId(ReconcileResult result, String generatorId, String instanceKey) {
        return generatedGain(result, generatorId, instanceKey).id();
    }
    public static String generatedRegistry(ReconcileResult result, String generatorId) {
        return generator(result, generatorId).registry();
    }
    public static String generatedNodeId(ReconcileResult result, String generatorId, String instanceKey, String role) {
        return DynamicRegistryQueries.generatedNodeId(result, generatorId, instanceKey, role);
    }
    public static Map<String, Object> seedInstanceRecord(String generatorId, String stableKey, List<String> roles) {
        return DynamicRegistryIdentity.seedInstanceRecord(generatorId, stableKey, roles);
    }
    public static Map<String, Object> seedOutputInstanceRecord(String generatorId, DeviceIdentity identity) {
        return DynamicRegistryIdentity.seedOutputInstanceRecord(generatorId, identity);
    }
    public static ReconcileResult reconcile(AudioGraph configuredGraph,
            Map<String, List<InstanceSnapshot>> snapshotsByGeneratorId) {
        return reconcile(configuredGraph, snapshotsByGeneratorId, Map.of(), null, null);
    }
    public static ReconcileResult reconcile(AudioGraph configuredGraph,
            Map<String, List<InstanceSnapshot>> snapshotsByGeneratorId, ApplicationGainResolver resolver) {
        return reconcile(configuredGraph, snapshotsByGeneratorId, Map.of(), resolver, null);
    }
    public static ReconcileResult reconcile(AudioGraph configuredGraph,
            Map<String, List<InstanceSnapshot>> snapshotsByGeneratorId, Map<String, String> parentCompositeIds,
            ApplicationGainResolver resolver) {
        return reconcile(configuredGraph, snapshotsByGeneratorId, parentCompositeIds, resolver, null);
    }
    public static ReconcileResult reconcile(AudioGraph configuredGraph,
            Map<String, List<InstanceSnapshot>> snapshotsByGeneratorId, Map<String, String> parentCompositeIds,
            ApplicationGainResolver resolver, EndpointGainResolver endpointGainResolver) {
        return DynamicRegistryReconciler.reconcile(configuredGraph, snapshotsByGeneratorId, parentCompositeIds,
                resolver, endpointGainResolver);
    }
    public static boolean setInstanceOverride(Map<String, Object> config, String routeId, String generatorId,
            String instanceKey, String field, Object value) {
        return DynamicRegistryOverrides.setInstanceOverride(config, routeId, generatorId, instanceKey, field, value);
    }
    public static Optional<Object> instanceOverride(Map<String, Object> config, String routeId, String generatorId,
            String instanceKey, String field) {
        return DynamicRegistryOverrides.instanceOverride(config, routeId, generatorId, instanceKey, field);
    }
    public static GainState instanceGainBaseline(Map<String, Object> config, String routeId, String generatorId) {
        return DynamicRegistryOverrides.instanceGainBaseline(config, routeId, generatorId);
    }
    public static boolean instanceMutedBaseline(Map<String, Object> config, String routeId, String generatorId) {
        return DynamicRegistryOverrides.instanceMutedBaseline(config, routeId, generatorId);
    }
    public static boolean rehomeInstanceGroupOverrides(Map<String, Object> config, String oldGroupId,
            String newGroupId) {
        return DynamicRegistryOverrides.rehomeInstanceGroupOverrides(config, oldGroupId, newGroupId);
    }
}
