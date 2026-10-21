package io.github.ilyaandreev05.audiomatrix.graph;

import io.github.ilyaandreev05.audiomatrix.config.UnifiedGraphConfig;

import java.util.Set;

/** The canonical node ids of the isolated haptic chain. */
final class GraphHapticChain {
    private static final Set<String> CANONICAL_IDS = Set.of(UnifiedGraphConfig.LIMITER_ID,
            UnifiedGraphConfig.EVENT_SOURCE_ID, UnifiedGraphConfig.EVENT_GAIN_ID,
            GraphCommonVibro.VIBRO_MIXER_ID, GraphCommonVibro.VIBRO_GAIN_ID);

    private GraphHapticChain() {}

    /** A copied route keeps the canonical haptic ids, so it stays a valid isolated haptic route. */
    static boolean keepsCanonicalId(String nodeId) {
        return CANONICAL_IDS.contains(nodeId);
    }
}
