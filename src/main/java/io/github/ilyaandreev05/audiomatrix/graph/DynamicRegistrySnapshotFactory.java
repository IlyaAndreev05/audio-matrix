package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.audio.AudioMatrixOwnedStream;
import io.github.ilyaandreev05.audiomatrix.audio.AudioNode;
import io.github.ilyaandreev05.audiomatrix.audio.DeviceIdentity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DynamicRegistrySnapshotFactory {
    private DynamicRegistrySnapshotFactory() {}

    static DynamicInstanceRegistry.InstanceSnapshot application(AudioNode stream) {
        return application(stream, "", "");
    }
    static DynamicInstanceRegistry.InstanceSnapshot application(AudioNode stream, String canonicalAdapterId,
                                                                 String displayName) {
        Objects.requireNonNull(stream, "stream");
        String appId = DynamicRegistryValues.safe(stream.applicationId());
        String process = DynamicRegistryValues.safe(stream.processName());
        String canonical = DynamicRegistryValues.safe(canonicalAdapterId);
        String stableKey;
        String selector;
        DynamicInstanceRegistry.Confidence confidence;
        if (!canonical.isBlank()) {
            stableKey = "browser-parent:" + canonical;
            selector = !appId.isBlank() ? "application:" + appId
                    : !process.isBlank() ? "process:" + process
                    : "metadata:" + DynamicRegistryValues.sha256(stream.name());
            confidence = appId.isBlank() && process.isBlank()
                    ? DynamicInstanceRegistry.Confidence.LOW : DynamicInstanceRegistry.Confidence.HIGH;
        } else if (!appId.isBlank()) {
            stableKey = "app:" + appId;
            selector = "application:" + appId;
            confidence = DynamicInstanceRegistry.Confidence.HIGH;
        } else if (!process.isBlank()) {
            stableKey = "process:" + process;
            selector = "process:" + process;
            confidence = DynamicInstanceRegistry.Confidence.HIGH;
        } else {
            String metadata = String.join("\u0000", DynamicRegistryValues.safe(stream.applicationName()),
                    DynamicRegistryValues.safe(stream.name()), DynamicRegistryValues.safe(stream.description()),
                    DynamicRegistryValues.safe(stream.mediaClass()), String.valueOf(stream.channels()));
            String digest = DynamicRegistryValues.sha256(metadata);
            stableKey = "stream:" + digest;
            selector = "metadata:" + digest;
            confidence = DynamicInstanceRegistry.Confidence.LOW;
        }
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("streams", List.of(streamBinding(stream)));
        binding.put("applicationId", appId);
        binding.put("processName", process);
        binding.put("applicationName", DynamicRegistryValues.safe(stream.applicationName()));
        binding.put("canonicalApplicationId", canonical);
        binding.put("audioMatrixOwned", AudioMatrixOwnedStream.matches(stream));
        binding.put("hasPcmBinding", stream.id() >= 0 && DynamicRegistryValues.safe(stream.mediaClass()).startsWith("Stream/Output/Audio"));
        return new DynamicInstanceRegistry.InstanceSnapshot("applications", stableKey,
                DynamicRegistryValues.firstNonBlank(displayName, stream.applicationName(), stream.description(),
                        stream.name(), stableKey), selector, DynamicRegistryValues.channels(stream), binding, confidence);
    }

    static DynamicInstanceRegistry.InstanceSnapshot browserParentApplication(String browser, String applicationId,
                                                                               String processName, String displayName) {
        String adapter = DynamicRegistryIdentity.requireIdentityPart(browser, "browser");
        String appId = DynamicRegistryValues.safe(applicationId);
        String process = DynamicRegistryValues.safe(processName);
        String selector = !appId.isBlank() ? "application:" + appId
                : !process.isBlank() ? "process:" + process : "application:unassigned";
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("streams", List.of());
        binding.put("instanceKey", "browser-parent:" + adapter);
        binding.put("applicationId", appId);
        binding.put("processName", process);
        binding.put("applicationName", DynamicRegistryValues.safe(displayName));
        binding.put("canonicalApplicationId", adapter);
        binding.put("browserAdapter", adapter);
        binding.put("audioMatrixOwned", false);
        binding.put("hasPcmBinding", false);
        return new DynamicInstanceRegistry.InstanceSnapshot("applications", "browser-parent:" + adapter,
                DynamicRegistryValues.firstNonBlank(displayName, adapter), selector, List.of("FL", "FR"), binding,
                DynamicInstanceRegistry.Confidence.HIGH);
    }

    static DynamicInstanceRegistry.InstanceSnapshot output(AudioNode output, boolean selected,
                                                             DynamicInstanceRegistry.GainState savedEndpointLevel,
                                                             String role) {
        Objects.requireNonNull(output, "output");
        DeviceIdentity identity = output.identity();
        if (identity == null) throw new IllegalArgumentException("Output snapshot has no DeviceIdentity");
        boolean reliable = identity.reliable();
        String stableKey = "device:" + identity.fingerprint();
        if (!reliable) {
            String metadata = String.join("\u0000", DynamicRegistryValues.safe(output.name()),
                    DynamicRegistryValues.safe(output.description()), DynamicRegistryValues.safe(output.mediaClass()));
            stableKey += ":" + DynamicRegistryValues.sha256(metadata);
        }
        String deviceId = DynamicRegistryValues.safe(output.name());
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("nodeId", output.id());
        binding.put("deviceId", deviceId);
        binding.put("identity", identity.toMap());
        binding.put("volume", DynamicRegistryValues.finiteLevel(output.volume(), 1.0));
        binding.put("muted", output.muted());
        if (savedEndpointLevel != null) {
            binding.put("savedEndpointGain", savedEndpointLevel.gain());
            binding.put("savedEndpointMuted", savedEndpointLevel.muted());
        }
        boolean hasReliableRole = reliable && role != null && !role.isBlank();
        if (hasReliableRole) binding.put("role", role);
        binding.put("selected", selected && reliable);
        String selector = hasReliableRole ? "device:role:" + role : "device:" + identity.fingerprint();
        return new DynamicInstanceRegistry.InstanceSnapshot("outputs", stableKey,
                DynamicRegistryValues.firstNonBlank(output.description(), output.name(), stableKey), selector,
                DynamicRegistryValues.channels(output), binding,
                reliable ? DynamicInstanceRegistry.Confidence.HIGH : DynamicInstanceRegistry.Confidence.LOW);
    }

    static DynamicInstanceRegistry.InstanceSnapshot browserTab(String browser, String instanceId, String profileId,
            String sessionToken, int tabId, String title, boolean audible, boolean muted, String volumeCapability,
            String captureStreamKey, String applicationRuleId, String parentApplicationId, String parentProcessName) {
        String adapter = DynamicRegistryIdentity.requireIdentityPart(browser, "browser");
        String instance = DynamicRegistryIdentity.requireIdentityPart(instanceId, "browser instanceId");
        String profile = DynamicRegistryIdentity.requireIdentityPart(profileId, "browser profileId");
        String token = DynamicRegistryIdentity.requireIdentityPart(sessionToken, "browser sessionToken");
        String stableKey = "tab:" + adapter + ":" + instance + ":" + profile + ":" + token;
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("browser", adapter);
        binding.put("instanceId", instance);
        binding.put("profileId", profile);
        binding.put("tabId", tabId);
        binding.put("sessionToken", token);
        binding.put("audible", audible);
        binding.put("muted", muted);
        binding.put("volumeCapability", DynamicRegistryValues.safe(volumeCapability));
        binding.put("captureStreamKey", DynamicRegistryValues.safe(captureStreamKey));
        binding.put("captureApplicationId", "io.github.ilyaandree05.audiomatrix.chrome-tab-capture");
        binding.put("nodeName", DynamicRegistryValues.safe(captureStreamKey).isBlank()
                ? "" : "audio-matrix-chrome-" + captureStreamKey);
        binding.put("applicationRuleId", DynamicRegistryValues.safe(applicationRuleId));
        binding.put("parentApplicationId", DynamicRegistryValues.safe(parentApplicationId));
        binding.put("parentProcessName", DynamicRegistryValues.safe(parentProcessName));
        String selector = DynamicRegistryValues.safe(captureStreamKey).isBlank()
                ? "browser-tab:" + stableKey : "capture:" + captureStreamKey;
        return new DynamicInstanceRegistry.InstanceSnapshot("browser_tabs", stableKey,
                DynamicRegistryValues.firstNonBlank(title, stableKey), selector, List.of("FL", "FR"), binding,
                DynamicInstanceRegistry.Confidence.HIGH);
    }

    private static Map<String, Object> streamBinding(AudioNode stream) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("nodeId", stream.id());
        binding.put("name", DynamicRegistryValues.safe(stream.name()));
        binding.put("description", DynamicRegistryValues.safe(stream.description()));
        binding.put("volume", DynamicRegistryValues.finiteLevel(stream.volume(), 1.0));
        binding.put("muted", stream.muted());
        return binding;
    }
}
