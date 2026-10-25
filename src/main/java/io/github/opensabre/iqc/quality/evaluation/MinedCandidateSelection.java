package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.action.ActionPolicy;
import io.github.opensabre.iqc.finding.Finding;
import java.math.BigDecimal;
import java.util.*;

/** Deterministic uncertainty discovery from existing observations; never a correctness judgement. */
public final class MinedCandidateSelection {
    private MinedCandidateSelection(){ }
    public static final String STRATEGY="FUSED_UNCERTAINTY";
    public static final int VERSION=1;
    public static void validateConfiguration(JsonNode config){
        if(config==null||!config.isObject()||config.properties().stream().anyMatch(field->!Set.of("findingCodes","threshold","name","tags").contains(field.getKey())))
            throw new IllegalArgumentException("Invalid candidate mining configuration");
        var codes=config.path("findingCodes");var seen=new HashSet<String>();
        if(!codes.isArray()||codes.isEmpty()||codes.size()>50)throw new IllegalArgumentException("Mining requires 1-50 finding codes");
        for(var code:codes)if(!code.isTextual()||!code.textValue().matches("[A-Z][A-Z0-9_]{1,63}")||!seen.add(code.textValue()))
            throw new IllegalArgumentException("Mining finding codes must be distinct");
        if(!config.path("threshold").isTextual())throw new IllegalArgumentException("Mining threshold must be an exact decimal string");
        new io.github.opensabre.iqc.pipelineasset.PipelineRelease.NumericPredicate(io.github.opensabre.iqc.pipeline.PipelineDefinition.Operator.LESS_THAN,config.path("threshold").textValue());
        var threshold=new BigDecimal(config.path("threshold").textValue());
        if(threshold.signum()<0||threshold.compareTo(BigDecimal.ONE)>0)throw new IllegalArgumentException("Mining threshold outside 0..1");
        if(config.has("name")&&(!config.path("name").isTextual()||config.path("name").textValue().isBlank()||config.path("name").textValue().trim().length()>128))
            throw new IllegalArgumentException("Invalid mining candidate name");
        if(config.has("tags")){
            var tags=config.path("tags");if(!tags.isArray()||tags.size()>50)throw new IllegalArgumentException("Invalid mining tags");
            for(var tag:tags)if(!tag.isTextual()||tag.textValue().isBlank()||tag.textValue().length()>64)throw new IllegalArgumentException("Invalid mining tag");
        }
    }
    public static List<String> reasons(ActionPolicy.Context context,JsonNode config){
        validateConfiguration(config);Objects.requireNonNull(context);
        var reasons=new TreeSet<String>();var threshold=new BigDecimal(config.path("threshold").textValue());
        for(var code:config.path("findingCodes")){
            var fact=context.findings().get(code.textValue());
            if(fact==null){reasons.add("MISSING_FINDING");continue;}
            if(fact.state()!=Finding.State.KNOWN)reasons.add("FINDING_"+fact.state().name());
            else if(fact.finalConfidence()==null)reasons.add("UNMEASURED_CONFIDENCE");
            else if(fact.finalConfidence().compareTo(threshold)<0)reasons.add("LOW_CONFIDENCE");
            var known=fact.rawFindings().stream().filter(value->value.state()==Finding.State.KNOWN).toList();
            if(known.size()>1&&known.stream().anyMatch(value->value.valueType()!=known.getFirst().valueType()
                    ||!FindingEvaluationMetrics.equal(value.value(),known.getFirst().value())))reasons.add("OBSERVATION_DISAGREEMENT");
        }
        return List.copyOf(reasons);
    }
}
