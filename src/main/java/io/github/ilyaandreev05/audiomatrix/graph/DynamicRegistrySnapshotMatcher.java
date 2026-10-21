package io.github.ilyaandreev05.audiomatrix.graph;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DynamicRegistrySnapshotMatcher {
    private DynamicRegistrySnapshotMatcher() {}

    static Map<String, DynamicInstanceRegistry.InstanceSnapshot> mergeSnapshots(DynamicGeneratorNode generator,
            List<DynamicInstanceRegistry.InstanceSnapshot> snapshots) {
        Map<String, DynamicInstanceRegistry.InstanceSnapshot> result = new LinkedHashMap<>();
        if (snapshots == null) return result;
        if ("applications".equals(generator.registry()) && EventSoundStudioSource.SELECTOR.equals(generator.selector()))
            return result;
        for (DynamicInstanceRegistry.InstanceSnapshot snapshot : snapshots) {
            if (snapshot == null || !generator.registry().equals(snapshot.registry())
                    || !matchesGenerator(generator, snapshot) || excluded(generator, snapshot)) continue;
            DynamicInstanceRegistry.InstanceSnapshot previous = result.get(snapshot.stableKey());
            result.put(snapshot.stableKey(), previous == null ? snapshot : merge(previous, snapshot));
        }
        return result;
    }

    static boolean matchesOutputSelector(String selector, DynamicInstanceRegistry.InstanceSnapshot snapshot) {
        if (snapshot == null || snapshot.confidence() != DynamicInstanceRegistry.Confidence.HIGH
                || !DynamicRegistryIdentity.identityReliable(snapshot)) return false;
        if (selector != null && selector.startsWith("device:role:")) {
            String role = DynamicRegistryValues.text(snapshot.runtimeBinding().get("role"));
            return !role.isBlank() && selector.equals("device:role:" + role);
        }
        if (selector != null && ("device:*".equals(selector) || "device:all".equals(selector))) return true;
        return selector != null && selector.startsWith("device:")
                && selector.substring("device:".length()).equals(DynamicRegistryIdentity.identityFingerprint(snapshot));
    }

    private static boolean matchesGenerator(DynamicGeneratorNode generator,
                                            DynamicInstanceRegistry.InstanceSnapshot snapshot) {
        String selector = generator.selector();
        if ("applications".equals(generator.registry())) {
            if ("application:unassigned".equals(selector) || "application:*".equals(selector)) return true;
            if (selector.startsWith("application:"))
                return selector.substring("application:".length()).equals(
                        DynamicRegistryValues.text(snapshot.runtimeBinding().get("applicationId")));
            if (selector.startsWith("process:"))
                return selector.substring("process:".length()).equals(
                        DynamicRegistryValues.text(snapshot.runtimeBinding().get("processName")));
            return false;
        }
        if ("outputs".equals(generator.registry())) return matchesOutputSelector(selector, snapshot);
        if ("browser_tabs".equals(generator.registry())) {
            if ("browser:*".equals(selector) || "browser_tabs:*".equals(selector)) return true;
            if (selector.startsWith("browser:"))
                return selector.substring("browser:".length()).equals(
                        DynamicRegistryValues.text(snapshot.runtimeBinding().get("browser")));
        }
        return false;
    }

    private static boolean excluded(DynamicGeneratorNode generator,
                                    DynamicInstanceRegistry.InstanceSnapshot snapshot) {
        if ("applications".equals(snapshot.registry())) {
            Map<String, Object> binding = snapshot.runtimeBinding();
            String appId = DynamicRegistryValues.text(binding.get("applicationId"));
            if ("io.github.ilyaandree05.eventsoundstudio".equals(appId)
                    || "application:io.github.ilyaandree05.eventsoundstudio".equals(snapshot.selector())
                    || appId.equals("io.github.ilyaandree05.audiomatrix")
                    || appId.startsWith("io.github.ilyaandree05.audiomatrix.")
                    || Boolean.TRUE.equals(binding.get("audioMatrixOwned"))) return true;
        }
        for (String selector : generator.excludedSelectors()) {
            if (selector.equals(snapshot.stableKey()) || selector.equals(snapshot.selector())) return true;
            if ("outputs".equals(snapshot.registry())
                    && ("device:*".equals(selector) || "device:all".equals(selector))) return true;
            if (selector.equals("application:" + DynamicRegistryValues.text(snapshot.runtimeBinding().get("applicationId")))
                    || selector.equals("process:" + DynamicRegistryValues.text(snapshot.runtimeBinding().get("processName"))))
                return true;
        }
        return false;
    }

    private static DynamicInstanceRegistry.InstanceSnapshot merge(DynamicInstanceRegistry.InstanceSnapshot first,
            DynamicInstanceRegistry.InstanceSnapshot next) {
        if (!"applications".equals(first.registry())) return next;
        boolean firstPcm = Boolean.TRUE.equals(first.runtimeBinding().get("hasPcmBinding"));
        boolean nextPcm = Boolean.TRUE.equals(next.runtimeBinding().get("hasPcmBinding"));
        DynamicInstanceRegistry.InstanceSnapshot primary = !firstPcm && nextPcm ? next : first;
        Map<String, Object> binding = DynamicRegistryValues.mutableObject(primary.runtimeBinding());
        Map<String, Object> streamsByNode = new LinkedHashMap<>();
        int unnamed = 0;
        for (DynamicInstanceRegistry.InstanceSnapshot item : List.of(first, next)) {
            if (!(item.runtimeBinding().get("streams") instanceof List<?> streams)) continue;
            for (Object stream : streams) {
                Map<String, Object> streamBinding = DynamicRegistryValues.object(stream);
                String nodeId = DynamicRegistryValues.text(streamBinding.get("nodeId"));
                String key = nodeId.isBlank() ? "unnamed:" + unnamed++ : nodeId;
                streamsByNode.putIfAbsent(key, streamBinding);
            }
        }
        binding.put("streams", new ArrayList<>(streamsByNode.values()));
        binding.put("hasPcmBinding", firstPcm || nextPcm);
        binding.put("audioMatrixOwned", Boolean.TRUE.equals(first.runtimeBinding().get("audioMatrixOwned"))
                || Boolean.TRUE.equals(next.runtimeBinding().get("audioMatrixOwned")));
        DynamicInstanceRegistry.Confidence confidence = first.confidence().ordinal() < next.confidence().ordinal()
                ? first.confidence() : next.confidence();
        return new DynamicInstanceRegistry.InstanceSnapshot(primary.registry(), primary.stableKey(),
                primary.displayName(), primary.selector(), primary.channels(), binding, confidence);
    }
}
