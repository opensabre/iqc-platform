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
                         String policyId, int policyVersion) {
        public Result {
            rawFindings = List.copyOf(rawFindings); selectedIds = List.copyOf(selectedIds);
            rejectedIds = List.copyOf(rejectedIds); evidence = List.copyOf(evidence);
            value = value == null || value.isNull() ? null : value.deepCopy();
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
    private static Finding.State resolve(List<Finding> selected) {
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
                explanation, policy.id(), policy.version());
    }
}
