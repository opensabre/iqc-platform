package io.github.opensabre.iqc.fusion;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.finding.Finding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Pure cross-node fusion; never changes raw observations or creates scores/labels. */
public final class FindingFusion {
    private FindingFusion() { }
    public record Result(String code, Finding.ValueType valueType, Finding.State state, JsonNode value,
                         Finding.Severity severity, List<Finding> rawFindings, List<String> selectedIds,
                         List<String> rejectedIds, List<Finding.Evidence> evidence, String explanation,
                         String policyId, int policyVersion,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         java.math.BigDecimal finalConfidence) {
        public Result(String code, Finding.ValueType valueType, Finding.State state, JsonNode value,
                      Finding.Severity severity, List<Finding> rawFindings, List<String> selectedIds,
                      List<String> rejectedIds, List<Finding.Evidence> evidence, String explanation,
                      String policyId, int policyVersion) {
            this(code,valueType,state,value,severity,rawFindings,selectedIds,rejectedIds,evidence,
                    explanation,policyId,policyVersion,null);
        }
        public Result {
            rawFindings = List.copyOf(rawFindings); selectedIds = List.copyOf(selectedIds);
            rejectedIds = List.copyOf(rejectedIds); evidence = List.copyOf(evidence);
            value = value == null || value.isNull() ? null : value.deepCopy();
            if(finalConfidence!=null&&(state!=Finding.State.KNOWN||finalConfidence.signum()<0
                    ||finalConfidence.compareTo(java.math.BigDecimal.ONE)>0))
                throw new IllegalArgumentException("final confidence requires a known result and probability within 0..1");
        }
        @Override public JsonNode value() { return value == null ? null : value.deepCopy(); }
    }
    public static List<Result> fuse(List<Finding> findings, FusionPolicy policy) {
        Objects.requireNonNull(policy, "policy"); Objects.requireNonNull(findings, "findings");
        if (findings.stream().map(Finding::id).distinct().count() != findings.size())
            throw new IllegalArgumentException("duplicate finding id");
        var byCode = new LinkedHashMap<String, List<Finding>>();
        findings.forEach(f -> byCode.computeIfAbsent(f.code(), key -> new ArrayList<>()).add(f));
        var results = new ArrayList<Result>();
        byCode.forEach((code, raw) -> results.add(fuseCode(code, raw, policy)));
        return List.copyOf(results);
    }
    /** Reproduce legacy traces without adding a field absent from their frozen result shape.
     * Callers must still compare the returned complete results against the recorded results. */
    public static List<Result> reproduce(List<Finding> findings,FusionPolicy policy,List<Result> recorded){
        var legacyCodes=new java.util.HashSet<String>();
        for(var result:recorded)if(result.finalConfidence()==null)legacyCodes.add(result.code());
        return fuse(findings,policy).stream().map(result->legacyCodes.contains(result.code())
                ?new Result(result.code(),result.valueType(),result.state(),result.value(),result.severity(),
                    result.rawFindings(),result.selectedIds(),result.rejectedIds(),result.evidence(),
                    result.explanation(),result.policyId(),result.policyVersion()):result).toList();
    }
    private static Result fuseCode(String code, List<Finding> raw, FusionPolicy policy) {
        if (raw.stream().map(Finding::valueType).distinct().count() != 1
                || raw.stream().map(Finding::category).distinct().count() != 1)
            throw new IllegalArgumentException("incompatible finding definitions for code " + code);
        List<Finding> selected;
        String explanation;
        switch (policy.method()) {
            case PRIORITY -> {
                var eligible = raw.stream().filter(f -> f.state() != Finding.State.NOT_EVALUATED).toList();
                if (eligible.isEmpty()) return result(code, raw, List.of(), Finding.State.NOT_EVALUATED, null, policy,
                        "No source executed; skipped observations are not negative values");
                int highest = eligible.stream().mapToInt(f -> policy.nodePriority().getOrDefault(f.source().nodeId(), 0)).max().orElse(0);
                selected = eligible.stream().filter(f -> policy.nodePriority().getOrDefault(f.source().nodeId(), 0) == highest).toList();
                explanation = "Highest source priority " + highest + "; incomplete winners remain incomplete";
            }
            case CONFIDENCE -> {
                if (raw.stream().anyMatch(f -> !policy.calibratedNodeIds().contains(f.source().nodeId())))
                    throw new IllegalArgumentException("source missing confidence calibration for code " + code);
                if (raw.stream().anyMatch(f -> f.confidence() == null)) {
                    selected = raw;
                    Finding.State unresolved = resolve(raw);
                    if (unresolved == Finding.State.KNOWN) unresolved = Finding.State.UNKNOWN;
                    return result(code, raw, selected, unresolved, null, policy,
                            "Missing calibrated confidence; ranking cannot be resolved");
                }
                var highest = raw.stream().map(Finding::confidence).max(Comparator.naturalOrder()).orElseThrow();
                selected = raw.stream().filter(f -> f.confidence().compareTo(highest) == 0).toList();
                explanation = "Highest calibrated confidence; calibration " + policy.confidenceCalibrationId();
            }
            case OVERRIDE -> {
                String node = policy.overrideNodeByCode().get(code);
                if (node == null) throw new IllegalArgumentException("override source missing for code " + code);
                selected = raw.stream().filter(f -> node.equals(f.source().nodeId())).toList();
                explanation = "Explicit authoritative source " + node;
                if (selected.isEmpty()) return result(code, raw, selected, Finding.State.NOT_EVALUATED, null, policy,
                        explanation + " produced no observation");
            }
            case CUSTOM -> {
                var binding=policy.custom();var provider=FusionExtensionRegistry.system().require(binding);
                var outcome=Objects.requireNonNull(provider.evaluate(List.copyOf(raw),binding.parameters()),"Custom fusion outcome required");
                var sourceIds=new java.util.HashSet<>(outcome.selectedIds());
                if(sourceIds.isEmpty()||sourceIds.size()!=outcome.selectedIds().size()
                        ||!raw.stream().map(Finding::id).toList().containsAll(sourceIds))
                    throw new IllegalArgumentException("Custom fusion must select distinct original observation IDs");
                selected=raw.stream().filter(finding->sourceIds.contains(finding.id())).toList();
                if(outcome.state()==Finding.State.KNOWN&&selected.stream().anyMatch(finding->finding.state()!=Finding.State.KNOWN)
                        ||outcome.state()==Finding.State.NOT_EVALUATED&&selected.stream().anyMatch(finding->finding.state()!=Finding.State.NOT_EVALUATED)
                        ||outcome.state()==Finding.State.ERROR&&selected.stream().noneMatch(finding->finding.state()==Finding.State.ERROR))
                    throw new IllegalArgumentException("Custom fusion state contradicts selected source states");
                if(outcome.state()==Finding.State.KNOWN&&raw.getFirst().valueType()==Finding.ValueType.ENUM
                        &&selected.stream().noneMatch(finding->sameValue(finding.value(),outcome.value())))
                    throw new IllegalArgumentException("Custom fusion cannot introduce an unproven enum directory value");
                // Reuse the original typed value validator; never return this validation-only observation.
                var anchor=selected.getFirst();new Finding("custom-value-validation",code,anchor.category(),anchor.valueType(),
                        outcome.value(),outcome.state(),null,Finding.Severity.NONE,"Custom value validation",List.of(),anchor.source());
                return result(code,raw,selected,outcome.state(),outcome.value(),policy,
                        "CUSTOM "+binding.strategyId()+"/v"+binding.version()+" ["+binding.implementationHash()+"] "+outcome.explanation());
            }
            case WEIGHTED -> {
                if(raw.stream().map(f->f.source().nodeId()).distinct().count()!=raw.size())
                    throw new IllegalArgumentException("weighted fusion requires one observation per source node; aggregate repeat runs first");
                if(raw.stream().anyMatch(f->!policy.nodeWeights().containsKey(f.source().nodeId())))
                    throw new IllegalArgumentException("weighted fusion requires an explicit weight for every observed source");
                var total=raw.stream().map(f->weight(f,policy)).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
                selected=List.of();var accepted=java.math.BigDecimal.ZERO;
                for(var candidate:raw){
                    if(candidate.state()!=Finding.State.KNOWN)continue;
                    var supporters=raw.stream().filter(f->f.state()==Finding.State.KNOWN&&sameValue(f.value(),candidate.value())).toList();
                    var support=supporters.stream().map(f->weight(f,policy)).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
                    if(support.multiply(java.math.BigDecimal.valueOf(2)).compareTo(total)>0){selected=supporters;accepted=support;break;}
                }
                if(selected.isEmpty()){
                    var known=raw.stream().filter(f->f.state()==Finding.State.KNOWN).toList();
                    boolean conflict=known.stream().anyMatch(f->!sameValue(f.value(),known.getFirst().value()));
                    return result(code,raw,raw,conflict?Finding.State.CONFLICT:incompleteState(raw),null,policy,
                            "No strict weighted majority across observed source weight "+total.toPlainString()+"; incomplete observations abstain");
                }
                explanation="Strict weighted majority "+accepted.toPlainString()+" / "+total.toPlainString()+"; source weights are not calibrated confidence";
            }
            case VOTING -> {
                if (raw.stream().map(f -> f.source().nodeId()).distinct().count() != raw.size())
                    throw new IllegalArgumentException("voting requires one observation per source node; aggregate repeat runs first");
                selected = List.of();
                for (var candidate : raw) {
                    if (candidate.state() != Finding.State.KNOWN) continue;
                    var votes = raw.stream().filter(f -> f.state() == Finding.State.KNOWN && sameValue(f.value(), candidate.value())).toList();
                    if (votes.size() > raw.size() / 2) { selected = votes; break; }
                }
                if (selected.isEmpty()) {
                    var known = raw.stream().filter(f -> f.state() == Finding.State.KNOWN).toList();
                    boolean conflict = known.stream().anyMatch(f -> !sameValue(f.value(), known.getFirst().value()));
                    Finding.State state = conflict ? Finding.State.CONFLICT : incompleteState(raw);
                    return result(code, raw, raw, state, null, policy, "No strict majority across all source nodes; incomplete observations abstain");
                }
                explanation = "Strict majority across all source nodes; repeated observations do not multiply votes";
            }
            default -> throw new IllegalArgumentException("unsupported fusion method");
        }
        Finding.State state = resolve(selected);
        JsonNode value = state == Finding.State.KNOWN ? selected.getFirst().value() : null;
        return result(code, raw, selected, state, value, policy, explanation);
    }
    private static java.math.BigDecimal weight(Finding finding,FusionPolicy policy){
        return new java.math.BigDecimal(policy.nodeWeights().get(finding.source().nodeId()));
    }
    static Finding.State resolve(List<Finding> selected) {
        var known = selected.stream().filter(f -> f.state() == Finding.State.KNOWN).toList();
        if (selected.stream().anyMatch(f -> f.state() == Finding.State.CONFLICT)
                || known.stream().anyMatch(f -> !sameValue(f.value(), known.getFirst().value()))) return Finding.State.CONFLICT;
        if (selected.stream().allMatch(f -> f.state() == Finding.State.KNOWN)) return Finding.State.KNOWN;
        return incompleteState(selected);
    }
    private static Finding.State incompleteState(List<Finding> selected) {
        if (selected.stream().anyMatch(f -> f.state() == Finding.State.CONFLICT)) return Finding.State.CONFLICT;
        if (selected.stream().anyMatch(f -> f.state() == Finding.State.ERROR)) return Finding.State.ERROR;
        if (!selected.isEmpty() && selected.stream().allMatch(f -> f.state() == Finding.State.NOT_EVALUATED)) return Finding.State.NOT_EVALUATED;
        return Finding.State.UNKNOWN;
    }
    private static boolean sameValue(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) return left.decimalValue().compareTo(right.decimalValue()) == 0;
        return left.equals(right);
    }
    private static Result result(String code, List<Finding> raw, List<Finding> selected, Finding.State state,
                                 JsonNode value, FusionPolicy policy, String explanation) {
        var ids = selected.stream().map(Finding::id).toList();
        // Evidence from rejected candidates remains visible for human conflict inspection.
        var evidence = new LinkedHashMap<String, Finding.Evidence>();
        for (var finding : raw) for (var item : finding.evidence()) {
            var prior = evidence.putIfAbsent(item.id(), item);
            if (prior != null && !prior.equals(item)) throw new IllegalArgumentException("conflicting evidence identity " + item.id());
        }
        var severity = selected.stream().map(Finding::severity).max(Comparator.naturalOrder()).orElse(Finding.Severity.NONE);
        return new Result(code, raw.getFirst().valueType(), state, value, severity, raw, ids,
                raw.stream().map(Finding::id).filter(id -> !ids.contains(id)).toList(), List.copyOf(evidence.values()),
                explanation, policy.id(), policy.version(), calibratedConfidence(state,selected,policy));
    }
    private static java.math.BigDecimal calibratedConfidence(Finding.State state,List<Finding> selected,FusionPolicy policy){
        if(state!=Finding.State.KNOWN||policy.method()!=FusionPolicy.Method.CONFIDENCE||selected.isEmpty())return null;
        if(selected.stream().anyMatch(f->f.state()!=Finding.State.KNOWN||f.confidence()==null
                ||!policy.calibratedNodeIds().contains(f.source().nodeId())))return null;
        return selected.stream().map(Finding::confidence).min(Comparator.naturalOrder()).orElse(null);
    }
}
