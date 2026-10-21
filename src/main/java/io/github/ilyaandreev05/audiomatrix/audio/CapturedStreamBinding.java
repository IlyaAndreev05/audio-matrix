package io.github.ilyaandreev05.audiomatrix.audio;

import java.util.Objects;

/** Runtime-only identity and controls for one exact Chrome native-capture playback stream. */
public record CapturedStreamBinding(String nodeName, String sourceNodeId, String applicationRuleId,
                                    String parentApplicationId, String parentProcessName,
                                    String instanceKey, double tabGain, boolean tabMuted) {
    public static final String NODE_PREFIX = "audio-matrix-chrome-";

    public CapturedStreamBinding {
        nodeName = Objects.requireNonNull(nodeName, "nodeName");
        sourceNodeId = Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        applicationRuleId = Objects.requireNonNull(applicationRuleId, "applicationRuleId");
        parentApplicationId = Objects.requireNonNull(parentApplicationId, "parentApplicationId");
        parentProcessName = Objects.requireNonNull(parentProcessName, "parentProcessName");
        instanceKey = Objects.requireNonNull(instanceKey, "instanceKey");
        if (!nodeName.startsWith(NODE_PREFIX) || nodeName.length() == NODE_PREFIX.length())
            throw new IllegalArgumentException("Captured stream node name is not an exact Chrome capture name");
        if (sourceNodeId.isBlank() || applicationRuleId.isBlank() || instanceKey.isBlank())
            throw new IllegalArgumentException("Captured stream binding identity is incomplete");
        if (!Double.isFinite(tabGain) || tabGain < 0.0 || tabGain > 1.0)
            throw new IllegalArgumentException("Captured tab gain must be between 0 and 1");
    }
}
