package io.github.opensabre.iqc.pipelinebridge;

import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.scheme.SchemeDefinition;
import java.util.*;

/** Pure policy projection shared by execution and recalculation; never invokes detectors or actions. */
public final class PipelineDecisionEvaluator {
    private PipelineDecisionEvaluator() { }

    public static List<InspectionScoring.Decision> decisions(List<PipelineRelease.ItemMapping> mappings,
                                                            List<FindingFusion.Result> facts) {
        Objects.requireNonNull(mappings); Objects.requireNonNull(facts);
        var byCode = new HashMap<String, FindingFusion.Result>();
        for (var fact : facts) {
            if (byCode.putIfAbsent(fact.code(), fact) != null)
                throw new IllegalArgumentException("Duplicate fused finding code");
        }
        var codes = new HashSet<String>();
        var decisions = new ArrayList<InspectionScoring.Decision>();
        for (var mapping : mappings) {
            if (!codes.add(mapping.itemCode())) throw new IllegalArgumentException("Duplicate business mapping");
            var fact = byCode.get(mapping.findingCode());
            InspectionScoring.ItemStatus status = status(fact, mapping.hitMeaning());
            if (mapping.appliesWhenFindingCode() != null) {
                var gate = byCode.get(mapping.appliesWhenFindingCode());
                var gateStatus = status(gate, SchemeDefinition.HitMeaning.COMPLIANCE);
                status = switch (gateStatus) {
                    case PASS -> status;
                    case FAIL -> InspectionScoring.ItemStatus.NOT_APPLICABLE;
                    default -> gateStatus;
                };
            }
            decisions.add(new InspectionScoring.Decision(mapping.itemCode(), status));
        }
        return List.copyOf(decisions);
    }

    public static InspectionScoring.Result evaluate(InspectionScoring.Policy policy,
            List<PipelineRelease.ItemMapping> mappings, List<FindingFusion.Result> facts) {
        return InspectionScoring.evaluate(policy, decisions(mappings, facts));
    }

    private static InspectionScoring.ItemStatus status(FindingFusion.Result fact, SchemeDefinition.HitMeaning meaning) {
        if (fact == null) return InspectionScoring.ItemStatus.NOT_EVALUATED;
        return switch (fact.state()) {
            case KNOWN -> {
                if (fact.value() == null || !fact.value().isBoolean())
                    throw new IllegalArgumentException("Boolean policy requires a boolean known finding");
                boolean fail = meaning == SchemeDefinition.HitMeaning.VIOLATION
                        ? fact.value().booleanValue() : !fact.value().booleanValue();
                yield fail ? InspectionScoring.ItemStatus.FAIL : InspectionScoring.ItemStatus.PASS;
            }
            case ERROR -> InspectionScoring.ItemStatus.ERROR;
            case UNKNOWN, CONFLICT -> InspectionScoring.ItemStatus.REVIEW_REQUIRED;
            case NOT_EVALUATED -> InspectionScoring.ItemStatus.NOT_EVALUATED;
        };
    }
}
