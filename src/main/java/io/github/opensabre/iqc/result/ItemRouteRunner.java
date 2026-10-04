package io.github.opensabre.iqc.result;

import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.scheme.SchemeDependencyResolver;
import io.github.opensabre.iqc.scheme.SchemeDefinition;
import org.springframework.beans.BeanUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

/** Bounded per-conversation stage orchestration; owns neither model adapters, persistence nor business scoring. */
final class ItemRouteRunner {
    private ItemRouteRunner() { }

    /** Adapter must honor context scope and evaluate only supplied inputs, including candidate quote slices. */
    interface Detector {
        InspectionResult evaluate(SchemeDependencyResolver.DetectionContext context, List<ConversationMessage> inputs,
                                  InspectionResult upstream);

        default InspectionResult evaluate(SchemeDependencyResolver.DetectionContext context, List<ConversationMessage> inputs,
                                          InspectionResult upstream, int runIndex) {
            return evaluate(context, inputs, upstream);
        }
    }

    /** Adapter decodes candidate citations; the runner independently rejects invented or empty source slices. */
    interface CandidateDecoder {
        List<ConversationMessage> decode(InspectionResult candidate);
    }

    record Stage(String contextKey, boolean executed, InspectionResult result) { }
    record Item(String itemCode, Stage applicability, Stage intermediate, Stage terminal, String adjudicatedStatus) {
        Item(String itemCode, Stage intermediate, Stage terminal) {
            this(itemCode, null, intermediate, terminal,
                    terminal == null || terminal.result() == null ? "NOT_EVALUATED" : terminal.result().getResultStatus());
        }
    }
    record Run(List<Item> items, List<Stage> stages) { }

    /** One invocation owns one conversation/run input; context results must never leak to another invocation. */
    static Run execute(SchemeDependencyResolver.RoutePlan plan, List<ConversationMessage> messages,
                       Detector detector, CandidateDecoder decoder) {
        return execute(plan, messages, 1, null, detector, decoder);
    }

    /** Repeats the whole route graph so each terminal vote uses stages from the same run. */
    static Run execute(SchemeDependencyResolver.RoutePlan plan, List<ConversationMessage> messages,
                       int runCount, BigDecimal confidenceThreshold, Detector detector, CandidateDecoder decoder) {
        if (runCount < 1 || runCount > 5 || confidenceThreshold != null
                && (confidenceThreshold.compareTo(BigDecimal.ZERO) < 0 || confidenceThreshold.compareTo(BigDecimal.ONE) > 0))
            throw IqcException.invalidArgument("逐项裁决轮数必须为 1 到 5，置信门槛必须在 0 到 1 之间");
        var contexts = validate(plan);
        var runs = new ArrayList<Run>(runCount);
        for (int runIndex = 0; runIndex < runCount; runIndex++)
            runs.add(executeOnce(plan, messages, contexts, detector, decoder, runIndex));
        if (runCount == 1) return runs.getFirst();

        var stageRounds = new LinkedHashMap<String, List<Stage>>();
        for (Run run : runs) for (Stage stage : run.stages())
            stageRounds.computeIfAbsent(stage.contextKey(), ignored -> new ArrayList<>()).add(stage);
        var aggregateStages = new LinkedHashMap<String, Stage>();
        for (var entry : stageRounds.entrySet())
            aggregateStages.put(entry.getKey(), aggregate(entry.getKey(), entry.getValue(), confidenceThreshold));
        var items = new ArrayList<Item>();
        for (var route : plan.items()) {
            var votes = adjudicate(route.itemCode(), runs, confidenceThreshold);
            items.add(new Item(route.itemCode(),
                route.applicabilityContextKey() == null ? null : copy(aggregateStages.get(route.applicabilityContextKey())),
                aggregateStages.get(route.stageContextKey()) == null ? null : copy(aggregateStages.get(route.stageContextKey())),
                aggregateItemTerminal(contexts.get(route.finalContextKey()), route.itemCode(), runs, votes,
                        confidenceThreshold), votes.status()));
        }
        return new Run(List.copyOf(items), List.copyOf(aggregateStages.values()));
    }

    private static Run executeOnce(SchemeDependencyResolver.RoutePlan plan, List<ConversationMessage> messages,
                                   Map<String, SchemeDependencyResolver.DetectionContext> contexts,
                                   Detector detector, CandidateDecoder decoder, int runIndex) {
        var cached = new LinkedHashMap<String, Stage>();
        var items = new ArrayList<Item>();
        // Joint-label consumers run independently of check applicability; exact matching direct
        // contexts are shared through the same cache when a check uses identical detector inputs.
        for (String key : plan.labelContextKeys()) {
            var context = contexts.get(key);
            cached.put(key, detect(context, messages, null, detector, runIndex));
        }
        for (var item : plan.items()) {
            Stage applicability = item.applicabilityContextKey() == null ? null : cached.computeIfAbsent(item.applicabilityContextKey(),
                    key -> detect(contexts.get(key), messages, null, detector, runIndex));
            String gateStatus = applicability == null ? "HIT" : applicability.result().getResultStatus();
            if (!"HIT".equals(gateStatus)) {
                items.add(new Item(item.itemCode(), copy(applicability), null, null,
                        applicability == null ? "NOT_EVALUATED" : applicabilityStatus(gateStatus)));
                continue;
            }
            Stage intermediate = item.stageContextKey() == null ? null : cached.computeIfAbsent(item.stageContextKey(),
                    key -> detect(contexts.get(key), messages, null, detector, runIndex));
            Stage terminal = cached.get(item.finalContextKey());
            if (terminal == null) {
                var context = contexts.get(item.finalContextKey());
                if (intermediate == null) terminal = detect(context, messages, null, detector, runIndex);
                else if ("NOT_HIT".equals(intermediate.result().getResultStatus()))
                    terminal = skipped(context, "NOT_HIT", "中间阶段明确未命中，本项后续阶段未调用");
                else if (!"HIT".equals(intermediate.result().getResultStatus()))
                    terminal = skipped(context, "ERROR".equals(intermediate.result().getResultStatus()) ? "ERROR" : "REVIEW_REQUIRED",
                            "中间阶段未得出确定候选，本项需要复核");
                else if (item.route() == SchemeDefinition.Route.LLM_THEN_RULE) {
                    var quotes = candidateInputs(intermediate.result(), messages, decoder);
                    terminal = quotes == null ? skipped(context, "REVIEW_REQUIRED", "候选引文缺失或无效，不得回退扫描完整输入")
                            : detect(context, quotes, intermediate.result(), detector, runIndex);
                } else terminal = detect(context, messages, intermediate.result(), detector, runIndex);
                cached.put(item.finalContextKey(), terminal);
            }
            // Consumers receive copies: converting a candidate or reviewing one item cannot mutate another fact.
            items.add(new Item(item.itemCode(), copy(applicability), copy(intermediate), copy(terminal),
                    terminal.result().getResultStatus()));
        }
        // A route context blocked for every eligible consumer remains explicitly unexecuted in the trace.
        for (var context : contexts.values()) cached.computeIfAbsent(context.contextKey(),
                ignored -> skipped(context, "NOT_EVALUATED", "适用条件未通过或未形成确定结论，本阶段未执行"));
        return new Run(List.copyOf(items), cached.values().stream().map(ItemRouteRunner::copy).toList());
    }

    private static Stage detect(SchemeDependencyResolver.DetectionContext context, List<ConversationMessage> inputs,
                                InspectionResult upstream, Detector detector, int runIndex) {
        try {
            var ownedInputs = inputs.stream().map(source -> {
                var input = new ConversationMessage(); BeanUtils.copyProperties(source, input); return input;
            }).toList();
            var result = detector.evaluate(context, ownedInputs, cloneResult(upstream), runIndex);
            if (result == null || result.getResultStatus() == null
                    || !List.of("HIT", "NOT_HIT", "ERROR", "REVIEW_REQUIRED", "NOT_EVALUATED").contains(result.getResultStatus()))
                return new Stage(context.contextKey(), true, outcome(context, "REVIEW_REQUIRED", "检测阶段未返回明确结果"));
            var owned = cloneResult(result); owned.setRuleId(context.ruleId()); owned.setDeduction(0);
            return new Stage(context.contextKey(), true, owned);
        } catch (RuntimeException exception) {
            // Keep other business checks running; raw adapter exception text can contain credentials or model payloads.
            return new Stage(context.contextKey(), true, outcome(context, "ERROR", "检测阶段执行失败"));
        }
    }

    private static List<ConversationMessage> candidateInputs(InspectionResult candidate, List<ConversationMessage> messages,
                                                              CandidateDecoder decoder) {
        try {
            var decoded = decoder.decode(cloneResult(candidate));
            if (decoded == null || decoded.isEmpty()) return null;
            var sources = new LinkedHashMap<String, ConversationMessage>();
            messages.forEach(message -> sources.put(message.getId(), message));
            var inputs = new ArrayList<ConversationMessage>();
            for (var quote : decoded) {
                if (quote == null) return null;
                var source = sources.get(quote.getId());
                if (source == null || source.getContent() == null || quote.getContent() == null || quote.getContent().isBlank()
                        || !source.getContent().contains(quote.getContent())
                        || !java.util.Objects.equals(source.getConversationId(), quote.getConversationId())
                        || !java.util.Objects.equals(source.getSpeakerRole(), quote.getSpeakerRole())) return null;
                var input = new ConversationMessage(); BeanUtils.copyProperties(source, input);
                input.setContent(quote.getContent()); inputs.add(input);
            }
            return List.copyOf(inputs);
        } catch (RuntimeException exception) { return null; }
    }

    /** Reject malformed plans before calling any adapter; only direct or two-stage routes are allowed. */
    private static Map<String, SchemeDependencyResolver.DetectionContext> validate(SchemeDependencyResolver.RoutePlan plan) {
        if (plan == null || !List.of("iqc-item-execution-plan-v1", "iqc-item-execution-plan-v2").contains(plan.schemaVersion()) || plan.contexts() == null
                || plan.items() == null || plan.items().isEmpty())
            throw IqcException.invalidState("逐项执行计划无效");
        var contexts = new LinkedHashMap<String, SchemeDependencyResolver.DetectionContext>();
        for (var context : plan.contexts())
            if (context == null || context.contextKey() == null || context.contextKey().isBlank()
                    || context.ruleId() == null || context.ruleId().isBlank() || context.versionNo() < 1
                    || !"MESSAGE".equals(context.inputScope()) && !"CONVERSATION".equals(context.inputScope())
                    || contexts.putIfAbsent(context.contextKey(), context) != null)
                throw IqcException.invalidState("逐项检测上下文缺失或重复");
        var consumed = new java.util.HashSet<String>(); var codes = new java.util.HashSet<String>();
        boolean hasApplicability = false;
        var labelKeys = new java.util.HashSet<String>();
        for (String key : plan.labelContextKeys()) {
            var context = contexts.get(key);
            if (key == null || !labelKeys.add(key) || context == null || !"DIRECT".equals(context.phase())
                    || context.upstreamContextKey() != null)
                throw IqcException.invalidState("逐项执行计划包含无效标签识别上下文");
            consumed.add(key);
        }
        for (var item : plan.items()) {
            if (item == null || item.itemCode() == null || item.itemCode().isBlank() || !codes.add(item.itemCode()) || item.route() == null)
                throw IqcException.invalidState("逐项消费映射无效");
            var terminal = contexts.get(item.finalContextKey());
            var gate = item.applicabilityContextKey() == null ? null : contexts.get(item.applicabilityContextKey());
            hasApplicability |= item.applicabilityContextKey() != null;
            if (item.applicabilityContextKey() != null && (gate == null || !"APPLICABILITY".equals(gate.phase())
                    || gate.upstreamContextKey() != null))
                throw IqcException.invalidState("逐项执行计划包含无效适用条件上下文");
            boolean paired = item.route() == SchemeDefinition.Route.RULE_THEN_LLM || item.route() == SchemeDefinition.Route.LLM_THEN_RULE;
            var stage = contexts.get(item.stageContextKey());
            if (terminal == null || paired != (item.stageContextKey() != null)
                    || !java.util.Objects.equals(terminal.upstreamContextKey(), item.stageContextKey())
                    || !java.util.Objects.equals(terminal.phase(), !paired ? "DIRECT" : item.route() == SchemeDefinition.Route.RULE_THEN_LLM ? "REVIEW" : "VERIFY")
                    || paired && (stage == null || stage.upstreamContextKey() != null
                    || !java.util.Objects.equals(stage.phase(), item.route() == SchemeDefinition.Route.RULE_THEN_LLM ? "PREFILTER" : "CANDIDATE")))
                throw IqcException.invalidState("逐项执行计划包含缺失阶段或不支持的依赖");
            if (item.applicabilityContextKey() != null) consumed.add(item.applicabilityContextKey());
            consumed.add(item.finalContextKey()); if (paired) consumed.add(item.stageContextKey());
        }
        if (hasApplicability != "iqc-item-execution-plan-v2".equals(plan.schemaVersion()))
            throw IqcException.invalidState("逐项执行计划协议与适用条件配置不一致");
        if (consumed.size() != contexts.size()) throw IqcException.invalidState("逐项执行计划包含未引用阶段");
        return contexts;
    }

    private static String applicabilityStatus(String gateStatus) {
        return switch (gateStatus) {
            case "NOT_HIT" -> "NOT_APPLICABLE";
            case "ERROR" -> "ERROR";
            case "REVIEW_REQUIRED" -> "REVIEW_REQUIRED";
            case "NOT_EVALUATED" -> "NOT_EVALUATED";
            default -> "REVIEW_REQUIRED";
        };
    }

    private record ItemVotes(int runCount, int hitCount, int missCount, int notApplicableCount,
                             int errorCount, int uncertainCount, double confidence, String status) { }

    /** Votes include applicability as a distinct final outcome; uncertain rounds never count as a PASS or N/A. */
    private static ItemVotes adjudicate(String itemCode, List<Run> runs, BigDecimal confidenceThreshold) {
        var statuses = new ArrayList<String>();
        for (var run : runs) {
            var item = run.items().stream().filter(value -> itemCode.equals(value.itemCode())).findFirst().orElse(null);
            statuses.add(item == null ? "NOT_EVALUATED" : item.adjudicatedStatus());
        }
        int hits = (int) statuses.stream().filter("HIT"::equals).count();
        int misses = (int) statuses.stream().filter("NOT_HIT"::equals).count();
        int notApplicable = (int) statuses.stream().filter("NOT_APPLICABLE"::equals).count();
        int errors = (int) statuses.stream().filter("ERROR"::equals).count();
        int uncertain = statuses.size() - hits - misses - notApplicable - errors;
        int highest = Math.max(hits, Math.max(misses, notApplicable));
        long winners = List.of(hits, misses, notApplicable).stream().filter(count -> count == highest && count > 0).count();
        double confidence = runs.isEmpty() ? 0 : highest / (double) runs.size();
        String status;
        if (errors > 0) status = "ERROR";
        else if (uncertain > 0 || winners != 1 || highest * 2 <= runs.size()
                || confidenceThreshold != null && BigDecimal.valueOf(confidence).compareTo(confidenceThreshold) < 0)
            status = "REVIEW_REQUIRED";
        else if (hits == highest) status = "HIT";
        else if (misses == highest) status = "NOT_HIT";
        else status = "NOT_APPLICABLE";
        return new ItemVotes(runs.size(), hits, misses, notApplicable, errors, uncertain, confidence, status);
    }

    /** Keeps item-level majority evidence intact even when shared context votes include gated-out rounds. */
    private static Stage aggregateItemTerminal(SchemeDependencyResolver.DetectionContext context, String itemCode,
                                               List<Run> runs, ItemVotes votes, BigDecimal confidenceThreshold) {
        String contextKey = context.contextKey();
        if ("NOT_APPLICABLE".equals(votes.status()))
            return skipped(context, "NOT_EVALUATED", "适用条件在多数轮次未命中，本项不适用");
        List<Item> roundItems = runs.stream().map(run -> run.items().stream()
                .filter(item -> itemCode.equals(item.itemCode())).findFirst().orElse(null)).toList();
        boolean everyRoundReachedRoute = roundItems.stream().allMatch(item -> item != null && item.terminal() != null);
        if (everyRoundReachedRoute)
            return aggregate(contextKey, roundItems.stream().map(Item::terminal).toList(), confidenceThreshold);
        if ("ERROR".equals(votes.status()) || "REVIEW_REQUIRED".equals(votes.status()))
            return new Stage(contextKey, false, voteOutcome(context, votes));
        List<Stage> winning = roundItems.stream().filter(java.util.Objects::nonNull)
                .filter(item -> votes.status().equals(item.adjudicatedStatus()))
                .map(Item::terminal).filter(java.util.Objects::nonNull).toList();
        if (winning.isEmpty()) return new Stage(contextKey, false, voteOutcome(context, votes));
        Stage result = aggregate(contextKey, winning, null);
        var finding = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        finding.put("schemaVersion", "iqc-route-item-vote-v1").put("runCount", votes.runCount())
                .put("hitCount", votes.hitCount()).put("missCount", votes.missCount())
                .put("notApplicableCount", votes.notApplicableCount()).put("confidence", votes.confidence())
                .put("status", votes.status());
        try {
            var selected = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.result().getFindingJson());
            if (selected != null && !selected.isNull()) finding.set("selectedFinding", selected);
        } catch (Exception ignored) { finding.put("selectedFindingInvalid", true); }
        result.result().setFindingJson(finding.toString());
        return result;
    }

    private static InspectionResult voteOutcome(SchemeDependencyResolver.DetectionContext context, ItemVotes votes) {
        var result = new InspectionResult(); result.setRuleId(context.ruleId()); result.setResultStatus(votes.status());
        result.setDeduction(0); result.setEvidenceJson("[]");
        result.setReason(switch (votes.status()) {
            case "ERROR" -> "至少一轮检测或适用条件执行失败，需重试后确认";
            case "REVIEW_REQUIRED" -> "多轮裁决未达到确定结论";
            default -> "多轮逐项投票结果: " + votes.status();
        });
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var finding = mapper.createObjectNode().put("schemaVersion", "iqc-route-item-vote-v1")
                .put("runCount", votes.runCount()).put("hitCount", votes.hitCount())
                .put("missCount", votes.missCount()).put("notApplicableCount", votes.notApplicableCount())
                .put("confidence", votes.confidence()).put("status", votes.status());
        result.setFindingJson(finding.toString());
        return result;
    }

    private static Stage skipped(SchemeDependencyResolver.DetectionContext context, String status, String reason) {
        return new Stage(context.contextKey(), false, outcome(context, status, reason));
    }
    private static InspectionResult outcome(SchemeDependencyResolver.DetectionContext context, String status, String reason) {
        var result = new InspectionResult(); result.setRuleId(context.ruleId()); result.setResultStatus(status);
        result.setReason(reason); result.setDeduction(0); result.setEvidenceJson("[]"); result.setFindingJson("[]");
        return result;
    }
    private static InspectionResult cloneResult(InspectionResult original) {
        if (original == null) return null;
        var copy = new InspectionResult(); BeanUtils.copyProperties(original, copy); return copy;
    }
    private static Stage copy(Stage stage) {
        return stage == null ? null : new Stage(stage.contextKey(), stage.executed(), cloneResult(stage.result()));
    }

    private static Stage aggregate(String contextKey, List<Stage> rounds, BigDecimal confidenceThreshold) {
        long hits = rounds.stream().filter(stage -> "HIT".equals(stage.result().getResultStatus())).count();
        long misses = rounds.stream().filter(stage -> "NOT_HIT".equals(stage.result().getResultStatus())).count();
        long votes = hits + misses;
        double confidence = votes == 0 ? 0 : Math.max(hits, misses) / (double) rounds.size();
        String status;
        if (rounds.stream().anyMatch(stage -> "ERROR".equals(stage.result().getResultStatus()))) status = "ERROR";
        else if (rounds.stream().allMatch(stage -> "NOT_EVALUATED".equals(stage.result().getResultStatus()))) status = "NOT_EVALUATED";
        else if (votes != rounds.size() || hits == misses
                || confidenceThreshold != null && BigDecimal.valueOf(confidence).compareTo(confidenceThreshold) < 0)
            status = "REVIEW_REQUIRED";
        else status = hits > misses ? "HIT" : "NOT_HIT";

        Stage selected = rounds.stream().filter(stage -> status.equals(stage.result().getResultStatus())).findFirst()
                .orElseGet(() -> rounds.stream().filter(stage -> "ERROR".equals(stage.result().getResultStatus())).findFirst()
                        .orElse(rounds.getFirst()));
        InspectionResult result = cloneResult(selected.result());
        result.setResultStatus(status);
        result.setDeduction(0);
        result.setReason(switch (status) {
            case "HIT" -> "多轮裁决命中 " + hits + "/" + rounds.size();
            case "NOT_HIT" -> "多轮裁决未命中 " + misses + "/" + rounds.size();
            case "ERROR" -> "至少一轮检测执行失败，需重试后确认";
            default -> "多轮裁决未达到确定结论";
        });
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var finding = mapper.createObjectNode().put("schemaVersion", "iqc-route-round-v1")
                .put("runCount", rounds.size()).put("hitCount", hits).put("missCount", misses)
                .put("confidence", confidence).put("status", status);
        var details = finding.putArray("runs");
        for (int index = 0; index < rounds.size(); index++) {
            var stage = rounds.get(index);
            details.addObject().put("runIndex", index + 1).put("executed", stage.executed())
                    .put("status", stage.result().getResultStatus())
                    .put("reason", stage.result().getReason() == null ? "" : stage.result().getReason());
        }
        var labelObservations = mergeLabelObservations(rounds, status, mapper);
        if (labelObservations != null) {
            result.setFindingJson(labelObservations.toString());
            var evidence = mapper.createArrayNode(); var uniqueEvidence = new java.util.LinkedHashSet<String>();
            for (var stage : rounds) try {
                var citations = mapper.readTree(stage.result().getEvidenceJson() == null ? "[]" : stage.result().getEvidenceJson());
                if (citations != null && citations.isArray()) for (var citation : citations)
                    if (uniqueEvidence.add(citation.toString())) evidence.add(citation.deepCopy());
            } catch (Exception invalidEvidence) { throw IqcException.invalidState("联合标签路线证据格式无效"); }
            result.setEvidenceJson(evidence.toString());
        } else if ("HIT".equals(status)) {
            var evidence = mapper.createArrayNode(); var uniqueEvidence = new java.util.LinkedHashSet<String>();
            for (var stage : rounds) if ("HIT".equals(stage.result().getResultStatus())) {
                try {
                    var citations = mapper.readTree(stage.result().getEvidenceJson() == null ? "[]" : stage.result().getEvidenceJson());
                    if (citations == null || !citations.isArray()) throw new IllegalArgumentException("evidence must be an array");
                    for (var citation : citations) if (uniqueEvidence.add(citation.toString())) evidence.add(citation.deepCopy());
                } catch (Exception invalidEvidence) { throw IqcException.invalidState("多轮命中证据格式无效"); }
            }
            result.setEvidenceJson(evidence.toString());
        }
        try {
            var selectedFinding = mapper.readTree(selected.result().getFindingJson() == null ? "null" : selected.result().getFindingJson());
            if (selectedFinding != null && !selectedFinding.isNull()) finding.set("selectedFinding", selectedFinding);
        } catch (Exception ignored) { finding.put("selectedFindingInvalid", true); }
        if (labelObservations == null) result.setFindingJson(finding.toString());
        return new Stage(contextKey, rounds.stream().anyMatch(Stage::executed), result);
    }

    /** Keeps every round's validated fact envelope; uncertain rounds force label state to ERROR. */
    private static com.fasterxml.jackson.databind.node.ObjectNode mergeLabelObservations(List<Stage> rounds, String status,
                                                                                          com.fasterxml.jackson.databind.ObjectMapper mapper) {
        var payload = mapper.createObjectNode().put("schemaVersion", "iqc-rule-observations-v2")
                .put("runCount", rounds.size()).put("status", status);
        var observations = payload.putArray("observations");
        boolean hasLabelEnvelope = false;
        for (int runIndex = 0; runIndex < rounds.size(); runIndex++) {
            var stage = rounds.get(runIndex);
            try {
                var finding = mapper.readTree(stage.result().getFindingJson() == null ? "null" : stage.result().getFindingJson());
                if (finding != null && "iqc-rule-observations-v2".equals(finding.path("schemaVersion").asText())
                        && finding.path("observations").isArray()) {
                    hasLabelEnvelope = true;
                    for (var observation : finding.path("observations")) {
                        var copy = observations.addObject(); copy.setAll((com.fasterxml.jackson.databind.node.ObjectNode) observation.deepCopy());
                        copy.put("runIndex", runIndex + 1);
                    }
                } else if (finding != null && "iqc-label-facts-v2".equals(finding.path("schemaVersion").asText())) {
                    hasLabelEnvelope = true;
                    var observation = observations.addObject().put("status", stage.result().getResultStatus())
                            .put("runIndex", runIndex + 1);
                    if (stage.result().getMessageId() != null) observation.put("messageId", stage.result().getMessageId());
                    observation.set("finding", finding.deepCopy());
                }
            } catch (Exception ignored) {
                // A missing fact envelope is represented below as an incomplete observation, never as UNKNOWN.
            }
        }
        if (!hasLabelEnvelope) return null;
        if (!List.of("HIT", "NOT_HIT").contains(status))
            observations.addObject().put("status", status).put("findingError", "ROUTE_ROUNDS_INCOMPLETE");
        return payload;
    }
}
