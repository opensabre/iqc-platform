package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.result.llm.LlmTextSanitizer;
import io.github.opensabre.iqc.scheme.SchemeDependencyResolver;
import io.github.opensabre.iqc.task.model.InspectionTask;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Compatibility trace: records actual route observations, never manufactures business Finding codes. */
final class RouteExecutionTrace {
    static final String SCHEMA = "iqc-route-execution-trace-v1";
    private RouteExecutionTrace() { }

    static ObjectNode build(InspectionTask task, String executionId, String conversationId,
                            JsonNode snapshot, List<ConversationMessage> inputs,
                            SchemeDependencyResolver.RoutePlan plan, ItemRouteRunner.Run run,
                            ObjectMapper mapper) {
        var root = mapper.createObjectNode().put("schemaVersion", SCHEMA)
                .put("taskId", task.getId()).put("executionId", executionId).put("conversationId", conversationId)
                .put("inputHash", inputHash(inputs)).put("planSchemaVersion", plan.schemaVersion())
                .put("ruleSnapshotHash", hash(snapshot.toString()))
                .put("agentSnapshotHash", task.getAgentSnapshotJson() == null ? null : hash(task.getAgentSnapshotJson()));
        root.put("findingSchemaVersion", io.github.opensabre.iqc.finding.Finding.SCHEMA);
        var nodes = root.putArray("nodes");
        for (var round : run.rounds()) for (var stage : round.stages()) {
            var context = plan.contexts().stream().filter(value -> value.contextKey().equals(stage.contextKey()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("执行轨迹缺少冻结节点上下文"));
            String engine = "UNKNOWN";
            JsonNode frozenRule = null;
            for (var rule : snapshot.path("rules")) if (context.ruleId().equals(rule.path("id").asText())) {
                engine = rule.path("ruleType").asText("UNKNOWN"); frozenRule = rule; break;
            }
            var result = stage.result();
            var node = nodes.addObject().put("nodeId", context.contextKey()).put("runIndex", round.runIndex())
                    .put("ruleId", context.ruleId()).put("ruleVersion", context.versionNo()).put("engineType", engine)
                    .put("phase", context.phase()).put("inputScope", context.inputScope())
                    .put("upstreamNodeId", context.upstreamContextKey()).put("executed", stage.executed())
                    .put("executionStatus", !stage.executed() ? "SKIPPED" : "ERROR".equals(result.getResultStatus()) ? "FAILED" : "SUCCESS")
                    .put("resultStatus", result.getResultStatus()).put("factState", factState(stage))
                    .put("reason", LlmTextSanitizer.sanitize(result.getReason()))
                    .put("inputHash", stage.inputHash()).put("durationNanos", stage.durationNanos())
                    .put("startedAt", stage.startedAt() == null ? null : stage.startedAt().toString());
            attach(node, "rawFinding", result.getFindingJson(), mapper);
            attach(node, "evidence", result.getEvidenceJson(), mapper);
            if (frozenRule == null) throw new IllegalStateException("执行轨迹缺少冻结规则版本");
            var source = new io.github.opensabre.iqc.finding.Finding.Source(context.contextKey(), engine,
                    "rule:" + context.ruleId() + ":" + context.versionNo(), round.runIndex(), stage.inputHash());
            JsonNode citations = null;
            try { citations = result.getEvidenceJson() == null ? null : mapper.readTree(result.getEvidenceJson()); }
            catch (com.fasterxml.jackson.core.JsonProcessingException exception) { citations = mapper.createObjectNode(); }
            String identity = executionId + ":" + conversationId + ":" + context.contextKey() + ":" + round.runIndex();
            var fact = io.github.opensabre.iqc.finding.LegacyFindingAdapter.adapt(hash(identity), frozenRule,
                    result.getResultStatus(), stage.executed(), result.getReason(), citations, inputs, source);
            node.set("findings", mapper.valueToTree(List.of(fact)));
        }
        return root;
    }

    private static String factState(ItemRouteRunner.Stage stage) {
        // A skipped downstream NOT_HIT is a route inference; it is not a negative detector observation.
        if (!stage.executed()) return "NOT_EVALUATED";
        return switch (Objects.requireNonNullElse(stage.result().getResultStatus(), "")) {
            case "HIT" -> "POSITIVE";
            case "NOT_HIT" -> "NEGATIVE";
            case "ERROR" -> "ERROR";
            case "NOT_EVALUATED" -> "NOT_EVALUATED";
            default -> "UNKNOWN";
        };
    }

    private static void attach(ObjectNode node, String name, String payload, ObjectMapper mapper) {
        if (payload == null || payload.isBlank()) { node.putNull(name); return; }
        try {
            var parsed = mapper.readTree(payload);
            node.set(name, redact(parsed));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            node.putNull(name);
            node.put(name + "ParseError", true);
        }
    }

    private static JsonNode redact(JsonNode value) {
        if (value == null) return null;
        if (value.isTextual()) return com.fasterxml.jackson.databind.node.TextNode.valueOf(
                LlmTextSanitizer.sanitize(value.textValue()));
        if (value.isArray()) {
            var copy = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            value.forEach(element -> copy.add(redact(element)));
            return copy;
        }
        if (value.isObject()) {
            var copy = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            value.properties().forEach(field -> copy.set(field.getKey(),
                    List.of("messageId", "ruleId", "conversationId", "labelId", "valueCode", "code", "id").contains(field.getKey())
                            ? field.getValue().deepCopy() : redact(field.getValue())));
            return copy;
        }
        return value.deepCopy();
    }

    static String inputHash(List<ConversationMessage> inputs) {
        var digest = digest();
        for (var input : inputs) {
            update(digest, input.getId()); update(digest, input.getConversationId());
            update(digest, String.valueOf(input.getSequenceNo())); update(digest, input.getSpeakerRole());
            update(digest, String.valueOf(input.getRelativeTime())); update(digest, input.getContent());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        if (value == null) { digest.update(new byte[]{-1, -1, -1, -1}); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
    private static String hash(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
