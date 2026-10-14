package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.task.retry.AutomaticRetryPolicy;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Published with the existing pipeline version. No network, script, or live configuration lookup. */
public record ActionPolicy(String schemaVersion,List<Action> actions) {
    public static final String SCHEMA="iqc-action-policy-v1";
    public enum FailureHandling {RECORD_ONLY,ESCALATE_REVIEW}
    public enum Idempotency {PRODUCTION_SOURCE_V1}
    public enum Match {MATCH,NO_MATCH,UNKNOWN}
    public enum Kind {ALWAYS,ALL,ANY,NOT,FINDING_EQUALS,FINDING_STATE,ITEM_STATUS,RISK_AT_LEAST,FINAL_SCORE_BELOW,RESULT_STATUS,LABEL_EQUALS,BUSINESS_EQUALS,REVIEW_REQUIRED}
    public record Action(String actionId,ActionExecutionContract.Type type,Condition condition,
                         Idempotency idempotency,AutomaticRetryPolicy retry,FailureHandling failureHandling,
                         JsonNode configuration,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         Integer adapterVersion) {
        public Action(String actionId,ActionExecutionContract.Type type,Condition condition,
                      Idempotency idempotency,AutomaticRetryPolicy retry,FailureHandling failureHandling,JsonNode configuration) {
            this(actionId,type,condition,idempotency,retry,failureHandling,configuration,null);
        }
        public Action {
            code(actionId);Objects.requireNonNull(type);Objects.requireNonNull(condition);
            Objects.requireNonNull(idempotency);Objects.requireNonNull(failureHandling);
            // Earlier v1 snapshots have a fixed v1 interpretation, never "latest".
            if(adapterVersion!=null && adapterVersion<1)throw new IllegalArgumentException("Action adapter version must be positive");
            if(configuration==null || !configuration.isObject() || configuration.toString().length()>65536)
                throw new IllegalArgumentException("Action adapter configuration must be a bounded object");
            configuration=configuration.deepCopy();
        }
        @Override public JsonNode configuration(){return configuration.deepCopy();}
        public int effectiveAdapterVersion(){return adapterVersion==null?1:adapterVersion;}
    }
    public record Condition(Kind kind,String code,JsonNode value,List<Condition> children,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.pipelineasset.PipelineRelease.DecimalThresholdDeserializer.class)
            String decimalOperand) {
        public Condition(Kind kind,String code,JsonNode value,List<Condition> children) {
            this(kind,code,value,children,null);
        }
        public Condition {
            Objects.requireNonNull(kind);children=children==null?List.of():List.copyOf(children);
            value=value==null || value.isNull()?null:value.deepCopy();
            JsonNode effective=value;
            if(decimalOperand!=null) {
                if(value!=null || !Set.of(Kind.FINDING_EQUALS,Kind.LABEL_EQUALS,Kind.BUSINESS_EQUALS,Kind.FINAL_SCORE_BELOW).contains(kind))
                    throw new IllegalArgumentException("Exact decimal requires a numeric comparison and null value");
                new io.github.opensabre.iqc.pipelineasset.PipelineRelease.NumericPredicate(
                        io.github.opensabre.iqc.pipeline.PipelineDefinition.Operator.EQUALS,decimalOperand);
                effective=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.numberNode(new BigDecimal(decimalOperand));
            }
            boolean group=kind==Kind.ALL || kind==Kind.ANY || kind==Kind.NOT;
            if(group) {
                if(code!=null || value!=null || children.isEmpty() || children.size()>100
                        || kind==Kind.NOT && children.size()!=1)
                    throw new IllegalArgumentException("Invalid action condition group");
            } else {
                if(!children.isEmpty())throw new IllegalArgumentException("Action leaf cannot contain children");
                if(kind==Kind.ALWAYS) {
                    if(code!=null || value!=null)throw new IllegalArgumentException("ALWAYS cannot contain operands");
                } else {
                    if(effective==null || !effective.isValueNode() || effective.toString().length()>4096)
                        throw new IllegalArgumentException("Action leaf requires a bounded scalar");
                    if(kind==Kind.FINDING_EQUALS || kind==Kind.FINDING_STATE || kind==Kind.ITEM_STATUS)ActionPolicy.code(code);
                    else if(kind==Kind.LABEL_EQUALS || kind==Kind.BUSINESS_EQUALS)contextPointer(code,kind==Kind.LABEL_EQUALS?2:0);
                    else if(code!=null)throw new IllegalArgumentException("Unexpected action condition code");
                    switch(kind) {
                        case FINDING_STATE -> enumValue(Finding.State.class,value);
                        case ITEM_STATUS -> enumValue(InspectionScoring.ItemStatus.class,value);
                        case RISK_AT_LEAST -> enumValue(Finding.Severity.class,value);
                        case FINAL_SCORE_BELOW -> {
                            if(!effective.isNumber() || effective.decimalValue().precision()>38 || Math.abs((long)effective.decimalValue().scale())>18)
                                throw new IllegalArgumentException("Action score threshold invalid");
                        }
                        case REVIEW_REQUIRED -> {if(!value.isBoolean())throw new IllegalArgumentException("Review condition requires a boolean");}
                        case RESULT_STATUS -> {
                            if(!value.isTextual() || !Set.of("QUALIFIED","UNQUALIFIED","PENDING","NOT_APPLICABLE","ERROR").contains(value.textValue()))
                                throw new IllegalArgumentException("Action result status invalid");
                        }
                        default -> { }
                    }
                }
            }
            bounds(children,1,new int[]{1});
        }
        @Override public JsonNode value(){return value==null?null:value.deepCopy();}
        @com.fasterxml.jackson.annotation.JsonIgnore
        public JsonNode operand() {
            return decimalOperand==null?value():com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.numberNode(new BigDecimal(decimalOperand));
        }
    }
    public record Context(Map<String,FindingFusion.Result> findings,Map<String,InspectionScoring.ItemStatus> items,
                          InspectionScoring.ScoreStatus scoreStatus,BigDecimal finalScore,String riskLevel,String resultStatus,
                          Map<String,io.github.opensabre.iqc.label.FindingLabelProjection.Projection> labels,JsonNode businessContext,String reviewStatus) {
        public Context(Map<String,FindingFusion.Result> findings,Map<String,InspectionScoring.ItemStatus> items,
                InspectionScoring.ScoreStatus scoreStatus,BigDecimal finalScore,String riskLevel,String resultStatus) {
            this(findings,items,scoreStatus,finalScore,riskLevel,resultStatus,Map.of(),null);
        }
        public Context(Map<String,FindingFusion.Result> findings,Map<String,InspectionScoring.ItemStatus> items,
                InspectionScoring.ScoreStatus scoreStatus,BigDecimal finalScore,String riskLevel,String resultStatus,
                Map<String,io.github.opensabre.iqc.label.FindingLabelProjection.Projection> labels,JsonNode businessContext) {
            this(findings,items,scoreStatus,finalScore,riskLevel,resultStatus,labels,businessContext,null);
        }
        public Context {
            if(reviewStatus!=null&&!Set.of("REQUIRED","NOT_REQUIRED","UNKNOWN").contains(reviewStatus))throw new IllegalArgumentException("Invalid review status");
            findings=Map.copyOf(findings);items=Map.copyOf(items);labels=Map.copyOf(labels);
            if(businessContext!=null && !businessContext.isObject())throw new IllegalArgumentException("Business context must be an object");
            businessContext=businessContext==null?null:businessContext.deepCopy();
            labels.forEach((key,label)->{if(!key.equals(io.github.opensabre.iqc.label.FindingLabelPolicy.bindingAddress(new io.github.opensabre.iqc.label.FindingLabelPolicy.Binding(label.binding().labelId(),label.binding().labelVersion(),label.binding().valueCode(),label.binding().findingCode()))))throw new IllegalArgumentException("Risk label key mismatch");});
            findings.forEach((key,fact)->{if(!Objects.equals(key,fact.code()))throw new IllegalArgumentException("Action finding key mismatch");});
            Objects.requireNonNull(scoreStatus);
        }
        @Override public JsonNode businessContext(){return businessContext==null?null:businessContext.deepCopy();}
    }
    public ActionPolicy {
        if(!SCHEMA.equals(schemaVersion) || actions==null || actions.size()>100)
            throw new IllegalArgumentException("Invalid action policy");
        actions=List.copyOf(actions);var ids=new HashSet<String>();
        for(var action:actions)if(!ids.add(action.actionId()))throw new IllegalArgumentException("Duplicate action id");
    }
    /** Validate consumers against the same published finding/item declarations. */
    public void validateReferences(Set<String> findingCodes,Set<String> itemCodes) {
        for(var action:actions)validateReferences(action.condition(),findingCodes,itemCodes);
    }
    public static Match evaluate(Condition condition,Context context) {
        Objects.requireNonNull(condition);Objects.requireNonNull(context);
        return switch(condition.kind()) {
            case ALWAYS -> Match.MATCH;
            case ALL,ANY -> group(condition,context);
            case NOT -> switch(evaluate(condition.children().getFirst(),context)) {
                case MATCH -> Match.NO_MATCH;case NO_MATCH -> Match.MATCH;case UNKNOWN -> Match.UNKNOWN;
            };
            case FINDING_EQUALS -> {
                var fact=context.findings().get(condition.code());
                if(fact==null || fact.state()!=Finding.State.KNOWN || fact.value()==null)yield Match.UNKNOWN;
                var actual=fact.value();var expected=condition.operand();
                yield truth(actual.isNumber() && expected.isNumber()?actual.decimalValue().compareTo(expected.decimalValue())==0:actual.equals(expected));
            }
            case LABEL_EQUALS -> {
                var label=context.labels().get(condition.code());
                yield label==null || label.status()!=io.github.opensabre.iqc.label.FindingLabelProjection.Status.KNOWN
                        ?Match.UNKNOWN:scalarMatch(label.value(),condition.operand());
            }
            case BUSINESS_EQUALS -> scalarMatch(context.businessContext==null?null:context.businessContext.at(condition.code()),condition.operand());
            case FINDING_STATE -> {
                var fact=context.findings().get(condition.code());
                yield fact==null || fact.state()==null?Match.UNKNOWN:truth(fact.state().name().equals(condition.value().textValue()));
            }
            case ITEM_STATUS -> {
                var status=context.items().get(condition.code());
                yield status==null?Match.UNKNOWN:truth(status.name().equals(condition.value().textValue()));
            }
            case RISK_AT_LEAST -> {
                if(context.riskLevel()==null || "UNKNOWN".equals(context.riskLevel()))yield Match.UNKNOWN;
                yield truth(Finding.Severity.valueOf(context.riskLevel()).compareTo(Finding.Severity.valueOf(condition.value().textValue()))>=0);
            }
            case FINAL_SCORE_BELOW -> context.scoreStatus()!=InspectionScoring.ScoreStatus.FINAL || context.finalScore()==null
                    ?Match.UNKNOWN:truth(context.finalScore().compareTo(condition.operand().decimalValue())<0);
            case REVIEW_REQUIRED -> context.reviewStatus()==null || "UNKNOWN".equals(context.reviewStatus())?Match.UNKNOWN:
                    truth("REQUIRED".equals(context.reviewStatus())==condition.value().booleanValue());
            case RESULT_STATUS -> context.resultStatus()==null?Match.UNKNOWN:truth(context.resultStatus().equals(condition.value().textValue()));
        };
    }
    private static Match group(Condition condition,Context context) {
        boolean all=condition.kind()==Kind.ALL,unknown=false;
        for(var child:condition.children()) {
            var match=evaluate(child,context);
            if(all && match==Match.NO_MATCH)return Match.NO_MATCH;
            if(!all && match==Match.MATCH)return Match.MATCH;
            unknown|=match==Match.UNKNOWN;
        }
        return unknown?Match.UNKNOWN:all?Match.MATCH:Match.NO_MATCH;
    }
    private static Match scalarMatch(JsonNode actual,JsonNode expected) {
        if(actual==null || actual.isMissingNode() || actual.isNull() || !actual.isValueNode())return Match.UNKNOWN;
        return truth(actual.isNumber() && expected.isNumber()?actual.decimalValue().compareTo(expected.decimalValue())==0:actual.equals(expected));
    }
    private static void contextPointer(String pointer,int exactSegments) {
        if(pointer==null || pointer.length()>512 || !pointer.startsWith("/") || java.util.regex.Pattern.compile("~(?![01])").matcher(pointer).find() || pointer!=null && pointer.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid frozen context pointer");
        var parts=pointer.substring(1).split("/",-1);
        if(parts.length>16 || exactSegments>0 && parts.length!=exactSegments || Arrays.stream(parts).anyMatch(String::isEmpty))
            throw new IllegalArgumentException("Invalid frozen context pointer segments");
        com.fasterxml.jackson.core.JsonPointer.compile(pointer);
    }
    private static Match truth(boolean value){return value?Match.MATCH:Match.NO_MATCH;}
    private static void code(String value){if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,127}"))throw new IllegalArgumentException("Invalid action code");}
    private static <E extends Enum<E>> void enumValue(Class<E> type,JsonNode value) {
        if(!value.isTextual())throw new IllegalArgumentException("Action enum operand must be text");
        Enum.valueOf(type,value.textValue());
    }
    private static void bounds(List<Condition> children,int depth,int[] count) {
        if(depth>16 || count[0]>100)throw new IllegalArgumentException("Action condition exceeds limits");
        for(var child:children){if(++count[0]>100)throw new IllegalArgumentException("Action condition exceeds limits");bounds(child.children(),depth+1,count);}
    }
    /** Shared pre-decision input references; consumers cannot read derived decisions recursively. */
    public static void validateDecisionInputs(Condition condition,Set<String> findings,Set<String> items,Set<String> labels,boolean allowScore) {
        if(condition.kind()==Kind.REVIEW_REQUIRED || condition.kind()==Kind.RISK_AT_LEAST || condition.kind()==Kind.RESULT_STATUS || !allowScore && condition.kind()==Kind.FINAL_SCORE_BELOW)
            throw new IllegalArgumentException("Decision condition cannot read derived score/risk/result");
        validateInputLeaf(condition,findings,items,labels);
        condition.children().forEach(child->validateDecisionInputs(child,findings,items,labels,allowScore));
    }
    public static void validatePostDecisionInputs(Condition condition,Set<String> findings,Set<String> items,Set<String> labels) {
        if(condition.kind()==Kind.REVIEW_REQUIRED)throw new IllegalArgumentException("Review policy cannot read itself");
        validateInputLeaf(condition,findings,items,labels);
        condition.children().forEach(child->validatePostDecisionInputs(child,findings,items,labels));
    }
    private static void validateInputLeaf(Condition condition,Set<String> findings,Set<String> items,Set<String> labels) {
        if(findings!=null && (condition.kind()==Kind.FINDING_EQUALS || condition.kind()==Kind.FINDING_STATE) && !findings.contains(condition.code())
                || items!=null && condition.kind()==Kind.ITEM_STATUS && !items.contains(condition.code())
                || labels!=null && condition.kind()==Kind.LABEL_EQUALS && !labels.contains(condition.code()))
            throw new IllegalArgumentException("Decision condition references undeclared input");
    }
    private static void validateReferences(Condition condition,Set<String> findings,Set<String> items) {
        if(condition.kind()==Kind.LABEL_EQUALS || condition.kind()==Kind.BUSINESS_EQUALS)
            throw new IllegalArgumentException("Frozen context conditions are currently risk policy only");
        if((condition.kind()==Kind.FINDING_EQUALS || condition.kind()==Kind.FINDING_STATE) && !findings.contains(condition.code())
                || condition.kind()==Kind.ITEM_STATUS && !items.contains(condition.code()))
            throw new IllegalArgumentException("Action condition references undeclared finding or item");
        condition.children().forEach(child->validateReferences(child,findings,items));
    }
}
