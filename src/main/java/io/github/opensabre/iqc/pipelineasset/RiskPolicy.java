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
        for(var rule:rules)ActionPolicy.validateDecisionInputs(rule.condition(),findings,items,null,true);
    }
    public void validateReferences(Set<String> findings,Set<String> items,io.github.opensabre.iqc.label.FindingLabelPolicy labels) {
        var addresses=new HashSet<String>();
        if(labels!=null)labels.bindings().forEach(binding->addresses.add(io.github.opensabre.iqc.label.FindingLabelPolicy.bindingAddress(binding)));
        for(var rule:rules)ActionPolicy.validateDecisionInputs(rule.condition(),findings,items,addresses,true);
    }
    private static void validateCondition(ActionPolicy.Condition condition,Set<String> findings,Set<String> items,int[] count) {
        if(++count[0]>1000)throw new IllegalArgumentException("Risk condition graph exceeds 1000 nodes");
        if(condition.kind()==ActionPolicy.Kind.REVIEW_REQUIRED || condition.kind()==ActionPolicy.Kind.RISK_AT_LEAST || condition.kind()==ActionPolicy.Kind.RESULT_STATUS)
            throw new IllegalArgumentException("Risk policy cannot read derived risk/result status");
        for(var child:condition.children())validateCondition(child,findings,items,count);
    }
    /** New configuration/materialization gate only. Historical evaluate remains unchanged. */
    public void validateHighRiskEvidence(Set<String> evidenceRequiredCodes) {
        Objects.requireNonNull(evidenceRequiredCodes);
        if(defaultLevel.compareTo(Finding.Severity.HIGH)>=0)
            throw new IllegalArgumentException("High risk cannot be an unconditional default");
        for(var rule:rules)if(rule.level().compareTo(Finding.Severity.HIGH)>=0
                && !guaranteesEvidence(rule.condition(),evidenceRequiredCodes))
            throw new IllegalArgumentException("High-risk rule requires a known evidence-backed finding on every matching path");
    }
    private static boolean guaranteesEvidence(ActionPolicy.Condition condition,Set<String> codes) {
        return switch(condition.kind()) {
            case FINDING_EQUALS -> codes.contains(condition.code());
            case FINDING_STATE -> codes.contains(condition.code()) && "KNOWN".equals(condition.value().asText());
            case NOT -> condition.children().getFirst().kind()==ActionPolicy.Kind.FINDING_EQUALS
                    && codes.contains(condition.children().getFirst().code());
            case ALL -> condition.children().stream().anyMatch(child->guaranteesEvidence(child,codes));
            case ANY -> !condition.children().isEmpty() && condition.children().stream().allMatch(child->guaranteesEvidence(child,codes));
            default -> false;
        };
    }
    public static boolean hasLocatableEvidence(Collection<io.github.opensabre.iqc.fusion.FindingFusion.Result> facts) {
        return facts!=null && facts.stream().anyMatch(fact->fact.state()==Finding.State.KNOWN&&!fact.evidence().isEmpty());
    }
    /** Machine projections cannot use only a rejected alternative's evidence as their selected claim's proof. */
    public static boolean hasSelectedEvidence(Collection<io.github.opensabre.iqc.fusion.FindingFusion.Result> facts) {
        return facts!=null && facts.stream().anyMatch(fact->fact.state()==Finding.State.KNOWN
                && fact.rawFindings().stream().anyMatch(raw->fact.selectedIds().contains(raw.id())&&raw.state()==Finding.State.KNOWN
                    && raw.evidence().stream().anyMatch(fact.evidence()::contains)));
    }
    public static void requireSelectedEvidence(String risk,Collection<io.github.opensabre.iqc.fusion.FindingFusion.Result> facts) {
        if(Set.of("HIGH","CRITICAL").contains(risk==null?"":risk)&&!hasSelectedEvidence(facts))
            throw new IllegalArgumentException("High-risk machine projection requires selected source evidence");
    }
    public static void requireLocatableEvidence(String risk,Collection<io.github.opensabre.iqc.fusion.FindingFusion.Result> facts) {
        if(Set.of("HIGH","CRITICAL").contains(risk==null?"":risk)&&!hasLocatableEvidence(facts))
            throw new IllegalArgumentException("High-risk projection requires locatable validated evidence");
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
