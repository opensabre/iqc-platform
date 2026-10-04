package io.github.opensabre.iqc.fusion;

import java.util.Map;
import java.util.Set;

/** Frozen selection policy. Higher priority wins; confidence values must already be calibrated. */
public record FusionPolicy(String id, int version, Method method, Map<String, Integer> nodePriority,
                           Map<String, String> overrideNodeByCode, String confidenceCalibrationId,
                           Set<String> calibratedNodeIds) {
    public enum Method { PRIORITY, CONFIDENCE, VOTING, OVERRIDE }
    public FusionPolicy {
        if (id == null || id.isBlank() || version < 1 || method == null)
            throw new IllegalArgumentException("fusion policy requires id, positive version and method");
        nodePriority = Map.copyOf(nodePriority == null ? Map.of() : nodePriority);
        overrideNodeByCode = Map.copyOf(overrideNodeByCode == null ? Map.of() : overrideNodeByCode);
        calibratedNodeIds = Set.copyOf(calibratedNodeIds == null ? Set.of() : calibratedNodeIds);
        if (method == Method.CONFIDENCE && (confidenceCalibrationId == null || confidenceCalibrationId.isBlank()
                || calibratedNodeIds.isEmpty()))
            throw new IllegalArgumentException("confidence fusion requires calibration identity and calibrated nodes");
        if (nodePriority.keySet().stream().anyMatch(String::isBlank)
                || overrideNodeByCode.entrySet().stream().anyMatch(e -> e.getKey().isBlank() || e.getValue().isBlank())
                || calibratedNodeIds.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("fusion policy references cannot be blank");
    }
}
