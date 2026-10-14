package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.github.opensabre.iqc.action.ActionPolicy;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FusionPolicy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Pure frozen review eligibility; actual creation remains the original CREATE_REVIEW action. */
public record ReviewPolicy(String schemaVersion,String seed,UnknownHandling unknownHandling,List<Rule> rules) {
    public static final String SCHEMA="iqc-review-policy-v1";
    public static final String EXACT_SCHEMA="iqc-review-policy-v2";
    public enum UnknownHandling {REQUIRE_REVIEW,KEEP_UNKNOWN}
    public enum Status {REQUIRED,NOT_REQUIRED,UNKNOWN}
    public record ConfidenceGate(String findingCode,
            @JsonDeserialize(using=PipelineRelease.DecimalThresholdDeserializer.class) String minimumInclusive,
            @JsonDeserialize(using=PipelineRelease.DecimalThresholdDeserializer.class) String maximumExclusive) {
        public ConfidenceGate {
            identity(findingCode);if(minimumInclusive==null && maximumExclusive==null)throw new IllegalArgumentException("Confidence interval is empty");
            if(minimumInclusive!=null)probability(minimumInclusive);if(maximumExclusive!=null)probability(maximumExclusive);
            if(minimumInclusive!=null && maximumExclusive!=null && decimal(minimumInclusive).compareTo(decimal(maximumExclusive))>=0)
                throw new IllegalArgumentException("Confidence interval bounds invalid");
        }
    }
    public record Rule(String ruleId,ActionPolicy.Condition condition,ConfidenceGate confidence,
            @JsonDeserialize(using=PipelineRelease.DecimalThresholdDeserializer.class) String probability,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) boolean fallback) {
        public Rule(String ruleId,ActionPolicy.Condition condition,ConfidenceGate confidence,String probability){this(ruleId,condition,confidence,probability,false);}
        public Rule {identity(ruleId);Objects.requireNonNull(condition);ReviewPolicy.probability(probability);if(decimal(probability).scale()>4)throw new IllegalArgumentException("Sampling probability supports 4 decimal places");ActionPolicy.validatePostDecisionInputs(condition,null,null,null);}
    }
    public record RuleMatch(String ruleId,ActionPolicy.Match conditionMatch,ActionPolicy.Match confidenceMatch,
            BigDecimal calibratedConfidence,Integer bucket,Boolean selected,boolean suppressed,ActionPolicy.Match effectiveMatch) { }
    public record Decision(String schemaVersion,String inputHash,Status status,List<RuleMatch> rules,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String samplingInputHash) {
        public Decision(String schemaVersion,String inputHash,Status status,List<RuleMatch> rules){this(schemaVersion,inputHash,status,rules,null);}
        public Decision {
            if(!Set.of("iqc-review-decision-v1","iqc-review-decision-v2").contains(schemaVersion)||inputHash==null||!inputHash.matches("[a-f0-9]{64}")
                    ||samplingInputHash!=null&&(!samplingInputHash.matches("[a-f0-9]{64}")||!"iqc-review-decision-v2".equals(schemaVersion)))throw new IllegalArgumentException("Invalid review decision identity");
            Objects.requireNonNull(status);rules=List.copyOf(rules);
        }
    }
    public ReviewPolicy {
        if(!(SCHEMA.equals(schemaVersion)||EXACT_SCHEMA.equals(schemaVersion))||seed==null||seed.isBlank()||seed.length()>128||seed.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid review policy schema/seed");
        Objects.requireNonNull(unknownHandling);rules=List.copyOf(rules);if(rules.isEmpty()||rules.size()>100)throw new IllegalArgumentException("Review policy requires 1 to 100 rules");
        var ids=new HashSet<String>();int count=0;for(var rule:rules){if(!ids.add(rule.ruleId()))throw new IllegalArgumentException("Duplicate review rule");count+=count(rule.condition());}if(count>1000)throw new IllegalArgumentException("Review conditions exceed 1000 nodes");if(rules.stream().filter(Rule::fallback).count()>1)throw new IllegalArgumentException("Review policy supports one ordinary fallback rule");
    }
    public void validateReferences(Set<String> findings,Set<String> items,io.github.opensabre.iqc.label.FindingLabelPolicy labels,ActionPolicy actions) {
        var addresses=new HashSet<String>();if(labels!=null)labels.bindings().forEach(binding->addresses.add(io.github.opensabre.iqc.label.FindingLabelPolicy.bindingAddress(binding)));
        for(var rule:rules){ActionPolicy.validatePostDecisionInputs(rule.condition(),findings,items,addresses);if(rule.confidence()!=null&&!findings.contains(rule.confidence().findingCode()))throw new IllegalArgumentException("Review confidence references undeclared finding");}
        validateRouting(actions);
    }
    public static void validateRouting(ActionPolicy actions) {
        long routes=actions==null?0:actions.actions().stream().filter(action->action.type()==io.github.opensabre.iqc.action.ActionExecutionContract.Type.CREATE_REVIEW&&action.condition().kind()==ActionPolicy.Kind.REVIEW_REQUIRED&&action.condition().value().isBoolean()&&action.condition().value().booleanValue()).count();
        if(routes!=1)throw new IllegalArgumentException("Review policy requires one direct CREATE_REVIEW route");
    }
    public static boolean readsReview(ActionPolicy.Condition condition) {
        return condition.kind()==ActionPolicy.Kind.REVIEW_REQUIRED||condition.children().stream().anyMatch(ReviewPolicy::readsReview);
    }
    public Decision evaluate(ActionPolicy.Context context,FusionPolicy fusion,String inputHash,ObjectMapper json) {
        return evaluate(context,fusion,inputHash,null,json);
    }
    public Decision evaluate(ActionPolicy.Context context,FusionPolicy fusion,String inputHash,String samplingInputHash,ObjectMapper json) {
        boolean exact=EXACT_SCHEMA.equals(schemaVersion);
        if(samplingInputHash!=null&&!samplingInputHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid exact sampling identity");
        String identity=exact?samplingInputHash:inputHash;
        if(inputHash==null||!inputHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Review requires frozen input identity");
        boolean required=false,unknown=false;var outcomes=new ArrayList<RuleMatch>();
        for(var rule:rules){var condition=ActionPolicy.evaluate(rule.condition(),context);BigDecimal value=confidence(rule.confidence(),context,fusion);
            var confidence=rule.confidence()==null?ActionPolicy.Match.MATCH:value==null?ActionPolicy.Match.UNKNOWN:
                    (rule.confidence().minimumInclusive()==null||value.compareTo(decimal(rule.confidence().minimumInclusive()))>=0)
                    &&(rule.confidence().maximumExclusive()==null||value.compareTo(decimal(rule.confidence().maximumExclusive()))<0)?ActionPolicy.Match.MATCH:ActionPolicy.Match.NO_MATCH;
            String salt=PipelineAssetService.hash(json.valueToTree(List.of(schemaVersion,seed,rule.ruleId())).toString());
            Integer bucket=null;Boolean selected=null;int threshold=decimal(rule.probability()).multiply(BigDecimal.valueOf(10000)).intValueExact();
            if(identity!=null){String key=io.github.opensabre.iqc.selector.ConversationSelectionService.sampleKey(salt,identity,json);bucket=new BigInteger(key,16).mod(BigInteger.valueOf(10000)).intValue();selected=bucket<threshold;}
            else if(threshold==0)selected=false;else if(threshold==10000)selected=true;
            var match=Boolean.FALSE.equals(selected)||condition==ActionPolicy.Match.NO_MATCH||confidence==ActionPolicy.Match.NO_MATCH?ActionPolicy.Match.NO_MATCH:
                    selected==null||condition==ActionPolicy.Match.UNKNOWN||confidence==ActionPolicy.Match.UNKNOWN?ActionPolicy.Match.UNKNOWN:ActionPolicy.Match.MATCH;
            required|=match==ActionPolicy.Match.MATCH;unknown|=match==ActionPolicy.Match.UNKNOWN;
            outcomes.add(new RuleMatch(rule.ruleId(),condition,confidence,value,bucket,selected,false,match));
        }
        boolean specialized=false;
        for(int i=0;i<rules.size();i++)if(!rules.get(i).fallback()&&decimal(rules.get(i).probability()).signum()>0
                &&outcomes.get(i).conditionMatch()!=ActionPolicy.Match.NO_MATCH&&outcomes.get(i).confidenceMatch()!=ActionPolicy.Match.NO_MATCH)specialized=true;
        required=false;unknown=false;
        for(int i=0;i<rules.size();i++){
            var outcome=outcomes.get(i);
            if(rules.get(i).fallback()&&specialized){outcome=new RuleMatch(outcome.ruleId(),outcome.conditionMatch(),outcome.confidenceMatch(),outcome.calibratedConfidence(),outcome.bucket(),outcome.selected(),true,ActionPolicy.Match.NO_MATCH);outcomes.set(i,outcome);}
            required|=outcome.effectiveMatch()==ActionPolicy.Match.MATCH;unknown|=outcome.effectiveMatch()==ActionPolicy.Match.UNKNOWN;
        }
        var status=required||unknown&&unknownHandling==UnknownHandling.REQUIRE_REVIEW?Status.REQUIRED:unknown?Status.UNKNOWN:Status.NOT_REQUIRED;
        return new Decision(exact?"iqc-review-decision-v2":"iqc-review-decision-v1",inputHash,status,outcomes,exact?samplingInputHash:null);
    }
    private static BigDecimal confidence(ConfidenceGate gate,ActionPolicy.Context context,FusionPolicy fusion) {
        if(gate==null)return null;var fact=context.findings().get(gate.findingCode());
        if(fact==null||fact.state()!=Finding.State.KNOWN||fusion.method()!=FusionPolicy.Method.CONFIDENCE||fusion.confidenceCalibrationId()==null||fusion.confidenceCalibrationId().isBlank()||fact.selectedIds().isEmpty())return null;
        BigDecimal minimum=null;int count=0;
        for(var raw:fact.rawFindings())if(fact.selectedIds().contains(raw.id())){if(raw.state()!=Finding.State.KNOWN||raw.confidence()==null||!fusion.calibratedNodeIds().contains(raw.source().nodeId()))return null;count++;minimum=minimum==null?raw.confidence():minimum.min(raw.confidence());}
        return count==fact.selectedIds().size()?minimum:null;
    }
    private static BigDecimal decimal(String value){new PipelineRelease.NumericPredicate(io.github.opensabre.iqc.pipeline.PipelineDefinition.Operator.EQUALS,value);return new BigDecimal(value);}
    private static void probability(String value){var number=decimal(value);if(number.signum()<0||number.compareTo(BigDecimal.ONE)>0)throw new IllegalArgumentException("Probability must be within 0..1");}
    private static void identity(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,127}"))throw new IllegalArgumentException("Invalid review reference");}
    private static int count(ActionPolicy.Condition condition){int count=1;for(var child:condition.children())count+=count(child);return count;}
}
