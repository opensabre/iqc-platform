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
            InspectionScoring.ItemStatus status = status(fact, mapping.hitMeaning(),mapping.numericPredicate());
            if (mapping.appliesWhenFindingCode() != null) {
                var gate = byCode.get(mapping.appliesWhenFindingCode());
                var gateStatus = status(gate, SchemeDefinition.HitMeaning.COMPLIANCE,mapping.appliesWhenNumericPredicate());
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
    public static InspectionScoring.Result evaluate(PipelineRelease release,List<FindingFusion.Result> facts,
            com.fasterxml.jackson.databind.JsonNode businessContext) {
        var decisions=decisions(release.items(),facts);
        return InspectionScoring.evaluate(release.scoring(),decisions,release.scoring().advanced()==null?null:inputContext(release,facts,decisions,businessContext));
    }
    public static io.github.opensabre.iqc.action.ActionPolicy.Context inputContext(PipelineRelease release,List<FindingFusion.Result> facts,
            List<InspectionScoring.Decision> decisions,com.fasterxml.jackson.databind.JsonNode businessContext) {
        var byCode=new LinkedHashMap<String,FindingFusion.Result>();facts.forEach(fact->{if(byCode.putIfAbsent(fact.code(),fact)!=null)throw new IllegalArgumentException("Duplicate decision finding code");});
        var statuses=new LinkedHashMap<String,InspectionScoring.ItemStatus>();decisions.forEach(value->statuses.put(value.itemCode(),value.status()));
        var labels=new LinkedHashMap<String,io.github.opensabre.iqc.label.FindingLabelProjection.Projection>();
        if(release.labelPolicy()!=null)for(var binding:release.labelPolicy().bindings()) {
            var projection=io.github.opensabre.iqc.label.FindingLabelProjection.project(release.labelPolicy().projectionBinding(binding),byCode.get(binding.findingCode()));
            labels.put(io.github.opensabre.iqc.label.FindingLabelPolicy.bindingAddress(binding),projection);
        }
        return new io.github.opensabre.iqc.action.ActionPolicy.Context(byCode,statuses,InspectionScoring.ScoreStatus.PENDING,null,null,null,labels,businessContext);
    }
    public static io.github.opensabre.iqc.pipelineasset.RiskPolicy.Decision riskDecision(PipelineRelease release,
            List<FindingFusion.Result> facts,InspectionScoring.Result score) {
        return riskDecision(release,facts,score,null);
    }
    public static io.github.opensabre.iqc.pipelineasset.RiskPolicy.Decision riskDecision(PipelineRelease release,
            List<FindingFusion.Result> facts,InspectionScoring.Result score,com.fasterxml.jackson.databind.JsonNode businessContext) {
        if(release.riskPolicy()==null)return null;
        var inputs=inputContext(release,facts,decisions(release.items(),facts),businessContext);
        return release.riskPolicy().evaluate(new io.github.opensabre.iqc.action.ActionPolicy.Context(inputs.findings(),inputs.items(),score.scoreStatus(),score.finalScore(),null,null,inputs.labels(),businessContext));
    }
    public static String resultStatus(io.github.opensabre.iqc.pipeline.PipelineRuntime.Run run,
            List<io.github.opensabre.iqc.scheme.SchemeResultEvaluator.ItemResult> items,InspectionScoring.Result score) {
        boolean failed=run.status()==io.github.opensabre.iqc.pipeline.PipelineRuntime.Status.FAILED||run.status()==io.github.opensabre.iqc.pipeline.PipelineRuntime.Status.CANCELLED||items.stream().anyMatch(item->item.status()==InspectionScoring.ItemStatus.ERROR);
        return failed?"ERROR":score.conclusion().name();
    }
    public static io.github.opensabre.iqc.pipelineasset.ReviewPolicy.Decision reviewDecision(PipelineRelease release,
            List<FindingFusion.Result> facts,InspectionScoring.Result score,String risk,String resultStatus,String inputHash,
            com.fasterxml.jackson.databind.JsonNode businessContext,com.fasterxml.jackson.databind.ObjectMapper json) {
        return reviewDecision(release,facts,score,risk,resultStatus,inputHash,businessContext,null,json);
    }
    public static io.github.opensabre.iqc.pipelineasset.ReviewPolicy.Decision reviewDecision(PipelineRelease release,
            List<FindingFusion.Result> facts,InspectionScoring.Result score,String risk,String resultStatus,String inputHash,
            com.fasterxml.jackson.databind.JsonNode businessContext,String samplingInputHash,com.fasterxml.jackson.databind.ObjectMapper json) {
        if(release.reviewPolicy()==null)return null;
        var inputs=inputContext(release,facts,decisions(release.items(),facts),businessContext);
        var context=new io.github.opensabre.iqc.action.ActionPolicy.Context(inputs.findings(),inputs.items(),score.scoreStatus(),score.finalScore(),risk,resultStatus,inputs.labels(),businessContext);
        return release.reviewPolicy().evaluate(context,release.fusion(),inputHash,samplingInputHash,json);
    }
    public static String riskLevel(PipelineRelease release,List<FindingFusion.Result> facts,InspectionScoring.Result score) {
        return riskLevel(release,facts,score,null);
    }
    public static String riskLevel(PipelineRelease release,List<FindingFusion.Result> facts,InspectionScoring.Result score,
            com.fasterxml.jackson.databind.JsonNode businessContext) {
        var decision=riskDecision(release,facts,score,businessContext);
        return decision==null?riskLevel(release.items(),facts,score):decision.level();
    }
    /** Compatibility projection for releases without an independent risk policy. */
    public static String riskLevel(List<PipelineRelease.ItemMapping> mappings,List<FindingFusion.Result> facts,InspectionScoring.Result score) {
        if(score.scoreStatus()==InspectionScoring.ScoreStatus.PENDING) return "UNKNOWN";
        var byCode=new HashMap<String,FindingFusion.Result>(); facts.forEach(fact->byCode.put(fact.code(),fact));
        var statuses=new HashMap<String,InspectionScoring.ItemStatus>(); decisions(mappings,facts).forEach(d->statuses.put(d.itemCode(),d.status()));
        Finding.Severity highest=Finding.Severity.NONE;
        for(var mapping:mappings) {
            var fact=byCode.get(mapping.findingCode());
            if(statuses.get(mapping.itemCode())==InspectionScoring.ItemStatus.FAIL && fact!=null && fact.severity().compareTo(highest)>0) highest=fact.severity();
        }
        return highest.name();
    }

    private static InspectionScoring.ItemStatus status(FindingFusion.Result fact, SchemeDefinition.HitMeaning meaning,PipelineRelease.NumericPredicate numeric) {
        if (fact == null) return InspectionScoring.ItemStatus.NOT_EVALUATED;
        return switch (fact.state()) {
            case KNOWN -> {
                boolean matched;
                if(numeric!=null) {
                    if(fact.valueType()!=Finding.ValueType.NUMBER)throw new IllegalArgumentException("Numeric policy requires NUMBER finding");
                    matched=numeric.matches(fact.value());
                } else {
                    if(fact.valueType()!=Finding.ValueType.BOOLEAN || fact.value()==null || !fact.value().isBoolean())
                        throw new IllegalArgumentException("Boolean policy requires a boolean known finding");
                    matched=fact.value().booleanValue();
                }
                boolean fail=meaning==SchemeDefinition.HitMeaning.VIOLATION?matched:!matched;
                yield fail ? InspectionScoring.ItemStatus.FAIL : InspectionScoring.ItemStatus.PASS;
            }
            case ERROR -> InspectionScoring.ItemStatus.ERROR;
            case UNKNOWN, CONFLICT -> InspectionScoring.ItemStatus.REVIEW_REQUIRED;
            case NOT_EVALUATED -> InspectionScoring.ItemStatus.NOT_EVALUATED;
        };
    }
}
