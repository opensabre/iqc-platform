package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.task.retry.AutomaticRetryPolicy;
import java.math.BigDecimal;
import java.util.HashSet;
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
    public enum Kind {ALWAYS,ALL,ANY,NOT,FINDING_EQUALS,FINDING_STATE,ITEM_STATUS,RISK_AT_LEAST,FINAL_SCORE_BELOW,RESULT_STATUS}
    public record Action(String actionId,ActionExecutionContract.Type type,Condition condition,
                         Idempotency idempotency,AutomaticRetryPolicy retry,FailureHandling failureHandling,
                         JsonNode configuration) {
        public Action {
            code(actionId);Objects.requireNonNull(type);Objects.requireNonNull(condition);
            Objects.requireNonNull(idempotency);Objects.requireNonNull(failureHandling);
            if(configuration==null || !configuration.isObject() || configuration.toString().length()>65536)
                throw new IllegalArgumentException("Action adapter configuration must be a bounded object");
            configuration=configuration.deepCopy();
        }
        @Override public JsonNode configuration(){return configuration.deepCopy();}
    }
    public record Condition(Kind kind,String code,JsonNode value,List<Condition> children) {
        public Condition {
            Objects.requireNonNull(kind);children=children==null?List.of():List.copyOf(children);
            value=value==null || value.isNull()?null:value.deepCopy();
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
                    if(value==null || !value.isValueNode() || value.toString().length()>4096)
                        throw new IllegalArgumentException("Action leaf requires a bounded scalar");
                    if(kind==Kind.FINDING_EQUALS || kind==Kind.FINDING_STATE || kind==Kind.ITEM_STATUS)ActionPolicy.code(code);
                    else if(code!=null)throw new IllegalArgumentException("Unexpected action condition code");
                    switch(kind) {
                        case FINDING_STATE -> enumValue(Finding.State.class,value);
                        case ITEM_STATUS -> enumValue(InspectionScoring.ItemStatus.class,value);
                        case RISK_AT_LEAST -> enumValue(Finding.Severity.class,value);
                        case FINAL_SCORE_BELOW -> {
                            if(!value.isNumber() || value.decimalValue().precision()>38 || Math.abs((long)value.decimalValue().scale())>18)
                                throw new IllegalArgumentException("Action score threshold invalid");
                        }
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
    }
    public record Context(Map<String,FindingFusion.Result> findings,Map<String,InspectionScoring.ItemStatus> items,
                          InspectionScoring.ScoreStatus scoreStatus,BigDecimal finalScore,String riskLevel,String resultStatus) {
        public Context {
            findings=Map.copyOf(findings);items=Map.copyOf(items);
            findings.forEach((key,fact)->{if(!Objects.equals(key,fact.code()))throw new IllegalArgumentException("Action finding key mismatch");});
            Objects.requireNonNull(scoreStatus);
        }
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
                var actual=fact.value();var expected=condition.value();
                yield truth(actual.isNumber() && expected.isNumber()?actual.decimalValue().compareTo(expected.decimalValue())==0:actual.equals(expected));
            }
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
                    ?Match.UNKNOWN:truth(context.finalScore().compareTo(condition.value().decimalValue())<0);
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
    private static void validateReferences(Condition condition,Set<String> findings,Set<String> items) {
        if((condition.kind()==Kind.FINDING_EQUALS || condition.kind()==Kind.FINDING_STATE) && !findings.contains(condition.code())
                || condition.kind()==Kind.ITEM_STATUS && !items.contains(condition.code()))
            throw new IllegalArgumentException("Action condition references undeclared finding or item");
        condition.children().forEach(child->validateReferences(child,findings,items));
    }
}
