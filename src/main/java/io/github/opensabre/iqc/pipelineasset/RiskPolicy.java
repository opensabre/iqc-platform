package io.github.opensabre.iqc.pipelineasset;

import io.github.opensabre.iqc.action.ActionPolicy;
import io.github.opensabre.iqc.finding.Finding;
import java.util.*;

/** Independent frozen risk classification reuses the original tri-state condition contract. */
public record RiskPolicy(String schemaVersion,Finding.Severity defaultLevel,List<Rule> rules) {
    public static final String SCHEMA="iqc-risk-policy-v1";
    public record Rule(String ruleId,Finding.Severity level,ActionPolicy.Condition condition) {
        public Rule {
            if(ruleId==null || !ruleId.matches("[A-Za-z0-9][A-Za-z0-9:_./@+-]{0,127}"))throw new IllegalArgumentException("Invalid risk rule identity");
            Objects.requireNonNull(level);Objects.requireNonNull(condition);
        }
    }
    public record RuleMatch(String ruleId,Finding.Severity level,ActionPolicy.Match match) { }
    public record Decision(String schemaVersion,String level,Finding.Severity defaultLevel,List<RuleMatch> rules) {
        public static final String SCHEMA="iqc-risk-decision-v1";
        public Decision {
            if(!SCHEMA.equals(schemaVersion) || level==null || !Set.of("NONE","LOW","MEDIUM","HIGH","CRITICAL","UNKNOWN").contains(level))
                throw new IllegalArgumentException("Invalid risk decision protocol");
            Objects.requireNonNull(defaultLevel);rules=List.copyOf(rules);
        }
    }
    public RiskPolicy {
        if(!SCHEMA.equals(schemaVersion))throw new IllegalArgumentException("Invalid risk policy schema");
        Objects.requireNonNull(defaultLevel);rules=List.copyOf(Objects.requireNonNull(rules));
        if(rules.isEmpty() || rules.size()>100)throw new IllegalArgumentException("Risk policy requires 1 to 100 rules");
        var ids=new HashSet<String>();int[] count={0};
        for(var rule:rules){if(!ids.add(rule.ruleId()))throw new IllegalArgumentException("Duplicate risk rule");validateCondition(rule.condition(),null,null,count);}
    }
    public void validateReferences(Set<String> findings,Set<String> items) {
        int[] count={0};for(var rule:rules)validateCondition(rule.condition(),findings,items,count);
    }
    public void validateReferences(Set<String> findings,Set<String> items,io.github.opensabre.iqc.label.FindingLabelPolicy labels) {
        validateReferences(findings,items);
        var addresses=new HashSet<String>();
        if(labels!=null)labels.bindings().forEach(binding->addresses.add(io.github.opensabre.iqc.label.FindingLabelPolicy.bindingAddress(binding)));
        for(var rule:rules)validateLabels(rule.condition(),addresses);
    }
    private static void validateLabels(ActionPolicy.Condition condition,Set<String> addresses) {
        if(condition.kind()==ActionPolicy.Kind.LABEL_EQUALS && !addresses.contains(condition.code()))
            throw new IllegalArgumentException("Risk condition references undeclared frozen label value");
        for(var child:condition.children())validateLabels(child,addresses);
    }
    private static void validateCondition(ActionPolicy.Condition condition,Set<String> findings,Set<String> items,int[] count) {
        if(++count[0]>1000)throw new IllegalArgumentException("Risk condition graph exceeds 1000 nodes");
        if(condition.kind()==ActionPolicy.Kind.RISK_AT_LEAST || condition.kind()==ActionPolicy.Kind.RESULT_STATUS)
            throw new IllegalArgumentException("Risk policy cannot read derived risk/result status");
        if(findings!=null && (condition.kind()==ActionPolicy.Kind.FINDING_EQUALS || condition.kind()==ActionPolicy.Kind.FINDING_STATE)
                && !findings.contains(condition.code()) || items!=null && condition.kind()==ActionPolicy.Kind.ITEM_STATUS && !items.contains(condition.code()))
            throw new IllegalArgumentException("Risk condition references undeclared finding/item");
        for(var child:condition.children())validateCondition(child,findings,items,count);
    }
    public Decision evaluate(ActionPolicy.Context context) {
        var confirmed=defaultLevel;var possible=defaultLevel;var matches=new ArrayList<RuleMatch>();
        for(var rule:rules) {
            var match=ActionPolicy.evaluate(rule.condition(),context);matches.add(new RuleMatch(rule.ruleId(),rule.level(),match));
            if(match==ActionPolicy.Match.MATCH && rule.level().compareTo(confirmed)>0)confirmed=rule.level();
            if(match!=ActionPolicy.Match.NO_MATCH && rule.level().compareTo(possible)>0)possible=rule.level();
        }
        return new Decision(Decision.SCHEMA,possible.compareTo(confirmed)>0?"UNKNOWN":confirmed.name(),defaultLevel,matches);
    }
}
