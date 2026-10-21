package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class DynamicRegistryIdentity {
    private DynamicRegistryIdentity() {}

    static String stableNodeId(String generatorId, String instanceKey, String role) {
        return "generated-" + UUID.nameUUIDFromBytes((generatorId + "\u0000" + instanceKey + "\u0000" + role)
                .getBytes(StandardCharsets.UTF_8));
    }
    static String stableCompositeId(String generatorId, String instanceKey) {
        return "subgraph-" + UUID.nameUUIDFromBytes((generatorId + "\u0000" + instanceKey + "\u0000composite")
                .getBytes(StandardCharsets.UTF_8));
    }
    /** The port-id prefix of the MultiSwitch output branches the dynamic registry materialises. */
    static final String GENERATED_OUTPUT_PREFIX = "generated-output-";
    static String stableOutputPortId(String generatorId, String deviceIdentityFingerprint) {
        return GENERATED_OUTPUT_PREFIX + UUID.nameUUIDFromBytes((generatorId + "\u0000" + deviceIdentityFingerprint
                + "\u0000switch-port").getBytes(StandardCharsets.UTF_8));
    }
    /** True for a port id of a runtime-generated MultiSwitch output branch. */
    static boolean isGeneratedOutputPort(String portId) {
        return portId != null && portId.startsWith(GENERATED_OUTPUT_PREFIX);
    }
    static String stableMixerInputPortId(String generatorId, String instanceKey) {
        return "generated-mixer-input-" + UUID.nameUUIDFromBytes((generatorId + "\u0000" + instanceKey
                + "\u0000mixer-input").getBytes(StandardCharsets.UTF_8));
    }
    static String stableConnectionId(String generatorId, String instanceKey, String role) {
        return "generated-link-" + UUID.nameUUIDFromBytes((generatorId + "\u0000" + instanceKey + "\u0000" + role)
                .getBytes(StandardCharsets.UTF_8));
    }
    static Map<String, Object> seedInstanceRecord(String generatorId, String stableKey, List<String> roles) {
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
    static Map<String, Object> seedOutputInstanceRecord(String generatorId, DeviceIdentity identity) {
        if (identity == null || !identity.reliable())
            throw new IllegalArgumentException("Only a reliable DeviceIdentity can seed an output instance");
        return seedInstanceRecord(generatorId, "device:" + identity.fingerprint(), List.of("gain", "output"));
    }
    static boolean identityReliable(DynamicInstanceRegistry.InstanceSnapshot snapshot) {
        return snapshot != null && DeviceIdentity.fromMap(
                DynamicRegistryValues.object(snapshot.runtimeBinding().get("identity"))).reliable();
    }
    static String identityFingerprint(DynamicInstanceRegistry.InstanceSnapshot snapshot) {
        return snapshot == null ? "" : DynamicRegistryValues.text(DynamicRegistryValues
                .object(snapshot.runtimeBinding().get("identity")).get("fingerprint"));
    }
    static String outputFingerprint(DynamicInstanceRegistry.InstanceSnapshot snapshot, String instanceKey) {
        String fingerprint = identityFingerprint(snapshot);
        if (!fingerprint.isBlank()) return fingerprint;
        if (!instanceKey.startsWith("device:")) return "";
        String suffix = instanceKey.substring("device:".length());
        int separator = suffix.indexOf(':');
        return separator < 0 ? suffix : suffix.substring(0, separator);
    }
    static String fallbackDeviceId(String instanceKey) {
        if (instanceKey.startsWith("device:")) return "device:" + instanceKey.substring("device:".length());
        return "device:unavailable-" + DynamicRegistryValues.sha256(instanceKey);
    }
    static DynamicInstanceRegistry.Confidence confidenceFromKey(String key) {
        return key.startsWith("stream:") ? DynamicInstanceRegistry.Confidence.LOW : DynamicInstanceRegistry.Confidence.HIGH;
    }
    static String requireIdentityPart(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
