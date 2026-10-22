package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.label.FindingLabelPolicy;
import io.github.opensabre.iqc.label.FindingLabelProjection;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.util.List;
import java.util.Map;

/** Measures frozen label projections without guessing legacy Gold names or executing detectors. */
final class LabelEvaluationMetrics {
    private LabelEvaluationMetrics() { }
    record Counts(int gold, int comparable, int correct) {
        boolean matchesAll() { return gold == comparable && comparable == correct; }
    }
    /** Gold uses canonical binding addresses; unresolved outputs retain their Gold denominator. */
    static Counts evaluate(Map<String, JsonNode> gold, FindingLabelPolicy policy, List<FindingFusion.Result> fusion) {
        int comparable = 0, correct = 0;
        if (policy != null && policy.directory() != null) {
            for (var binding : policy.bindings()) {
                var expected = gold.get(FindingLabelPolicy.bindingAddress(binding));
                if (expected == null) continue;
                var source = fusion.stream().filter(result -> binding.findingCode().equals(result.code())).findFirst().orElse(null);
                var projected = FindingLabelProjection.project(policy.projectionBinding(binding), source);
                if (projected.status() != FindingLabelProjection.Status.KNOWN) continue;
                comparable++;
                if (SelectorAssetService.hash(expected.toString()).equals(SelectorAssetService.hash(projected.value().toString()))) correct++;
            }
        }
        return new Counts(gold.size(), comparable, correct);
    }
}
