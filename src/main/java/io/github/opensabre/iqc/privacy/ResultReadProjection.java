package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.result.BatchResultQueryService;
import io.github.opensabre.iqc.result.BatchResultQueryService.ConversationResultDetail;
import io.github.opensabre.iqc.result.HierarchicalResultService.ResultHierarchy;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.result.model.InspectionEvidence;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.result.model.RuleInspectionResult;
import io.github.opensabre.iqc.label.LabelResultQueryService.LabelResultView;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.util.*;
import org.springframework.beans.BeanUtils;

/** Read-only response projection for result APIs; never mutates persisted scoring or evidence records. */
@org.springframework.stereotype.Component
public final class ResultReadProjection {
    private static final Set<String> STRUCTURAL_TEXT = Set.of("id", "reference", "type", "schemaVersion", "status",
            "state", "code", "category", "ruleId", "messageId", "conversationId", "executionId", "taskId",
            "inputHash", "sourceId", "valueType", "engineType", "engineVersion", "nodeId", "phase", "inputScope",
            "upstreamNodeId", "executionStatus", "resultStatus", "factState", "severity", "speakerRole", "evidenceId",
            "ruleResultId", "conversationResultId", "runId", "attemptId", "messageIds", "quoteId", "labelId",
            "agentId", "businessItemId", "metricId", "reviewId", "sampleId", "valueCode");
    private static final int PREVIEW_MESSAGE_COUNT = 3;
    private static final int PREVIEW_TEXT_LENGTH = 200;
    private final ObjectMapper json;
    private final GoldSampleReadProjection gold;

    public ResultReadProjection(ObjectMapper json) {
        // Response-local exact tree parsing, following the existing Selector snapshot convention.
        this.json = Objects.requireNonNull(json).copy()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
        this.gold = new GoldSampleReadProjection(this.json);
    }

    public io.github.opensabre.iqc.shared.IqcPage<BatchResultQueryService.BusinessConversationRow> businessPage(
            io.github.opensabre.iqc.shared.IqcPage<BatchResultQueryService.BusinessConversationRow> source,
            SensitiveReadAccessService.Access access) {
        Objects.requireNonNull(source); Objects.requireNonNull(access);
        boolean revealPii = access.fullConversation() && access.pii();
        var rows = source.records().stream().map(row -> new BatchResultQueryService.BusinessConversationRow(
                row.id(), row.taskId(), text(row.taskName(), revealPii), row.conversationId(), fileName(row.sourceFileName(), revealPii),
                row.executionId(), row.scoreStatus(), row.finalScore(), row.itemCount(), row.failureCount(), row.errorCount(), row.riskLevel())).toList();
        return new io.github.opensabre.iqc.shared.IqcPage<>(rows, source.current(), source.size(), source.total());
    }

    public BatchResultQueryService.BatchResultSummary batchSummary(BatchResultQueryService.BatchResultSummary source,
            SensitiveReadAccessService.Access access) {
        Objects.requireNonNull(access);
        if (source == null) return null;
        boolean revealPii = access.fullConversation() && access.pii();
        return new BatchResultQueryService.BatchResultSummary(source.taskId(), source.status(), source.conversationCount(),
                source.totalMessages(), source.processedMessages(), source.failedMessages(), source.averageScore(), source.hitCount(),
                source.highRiskCount(), source.conversations().stream().map(row -> summary(row, revealPii)).toList());
    }

    /** Copy feedback before masking; every evidence key is untrusted user payload. */
    public io.github.opensabre.iqc.quality.model.ResultFeedback feedback(
            io.github.opensabre.iqc.quality.model.ResultFeedback source, SensitiveReadAccessService.Access access) {
        var copy = new io.github.opensabre.iqc.quality.model.ResultFeedback();
        BeanUtils.copyProperties(source, copy);
        boolean reveal = access.fullConversation() && access.pii();
        copy.setComment(text(source.getComment(), reveal));
        copy.setEvidenceJson(jsonText(source.getEvidenceJson(), reveal, true));
        return copy;
    }

    public Map<String, Object> conversationDetail(ConversationResultDetail source, SensitiveReadAccessService.Access access) {
        Objects.requireNonNull(source); Objects.requireNonNull(access);
        boolean full = access.fullConversation();
        boolean revealPii = full && access.pii();
        var messages = new ArrayList<ConversationMessage>(source.messages().size());
        for (int index = 0; index < source.messages().size(); index++) {
            ConversationMessage row = source.messages().get(index);
            var copy = new ConversationMessage(); BeanUtils.copyProperties(row, copy);
            if (full) copy.setContent(text(row.getContent(), revealPii));
            else if (index < PREVIEW_MESSAGE_COUNT) copy.setContent(limit(text(row.getContent(), access.pii()), PREVIEW_TEXT_LENGTH));
            else copy.setContent(null);
            copy.setRawLine(null);
            messages.add(copy);
        }
        var results = source.results().stream().map(row -> projectResult(row, revealPii)).toList();
        Conversation conversation = ConversationReadProjection.summary(source.conversation(), revealPii);
        InspectionTask task = task(source.task(), access);
        var result = new LinkedHashMap<String, Object>();
        result.put("conversation", conversation); result.put("task", task);
        result.put("taskPresentation", taskPresentation(source.task())); result.put("summary", summary(source.summary(), revealPii));
        result.put("messages", List.copyOf(messages)); result.put("results", results);
        var withheld = new ArrayList<>(List.of("rawLine", "agentSnapshotJson", "ruleSnapshotJson", "labelScopeSnapshotJson", "triggerRequestJson"));
        if (!full && source.messages().size() > PREVIEW_MESSAGE_COUNT) withheld.add("messageContentAfterPreview");
        result.put("privacy", privacy(access, messages.size(), source.messages().size(), !full, withheld));
        return result;
    }

    public Map<String, Object> hierarchy(ResultHierarchy source, SensitiveReadAccessService.Access access) {
        Objects.requireNonNull(source); Objects.requireNonNull(access);
        boolean revealPii = access.fullConversation() && access.pii();
        ConversationInspectionResult conversation = new ConversationInspectionResult();
        BeanUtils.copyProperties(source.conversation(), conversation);
        conversation.setReason(text(conversation.getReason(), revealPii));
        conversation.setScoringResultJson(jsonText(conversation.getScoringResultJson(), revealPii));
        conversation.setBusinessItemResultsJson(jsonText(conversation.getBusinessItemResultsJson(), revealPii));
        conversation.setExecutionTraceJson(jsonText(conversation.getExecutionTraceJson(), revealPii));
        List<RuleInspectionResult> rules = source.rules().stream().map(row -> {
            var copy = new RuleInspectionResult(); BeanUtils.copyProperties(row, copy);
            copy.setReason(text(copy.getReason(), revealPii)); copy.setFindingJson(jsonText(copy.getFindingJson(), revealPii));
            return copy;
        }).toList();
        Map<String, List<InspectionEvidence>> evidence = new LinkedHashMap<>();
        source.evidenceByRuleResult().forEach((key, rows) -> evidence.put(key, rows.stream().map(row -> {
            var copy = new InspectionEvidence(); BeanUtils.copyProperties(row, copy);
            copy.setMatchedText(text(copy.getMatchedText(), revealPii));
            copy.setInternalDefinition(text(copy.getInternalDefinition(), revealPii));
            return copy;
        }).toList()));
        var result = new LinkedHashMap<String, Object>();
        result.put("conversation", conversation); result.put("rules", rules); result.put("evidenceByRuleResult", evidence);
        result.put("privacy", privacy(access, 0, 0, false,
                List.of("agentSnapshotJson", "ruleSnapshotJson", "labelScopeSnapshotJson")));
        return result;
    }

    public InspectionResult result(InspectionResult source, SensitiveReadAccessService.Access access) {
        var copy = projectResult(source, access.fullConversation() && access.pii());
        copy.setPrivacy(Map.of("policyVersion", "iqc-result-read-v1", "piiMasked", !(access.fullConversation() && access.pii()),
                "canReadFull", access.fullConversation(), "canReadPii", access.pii(),
                "withheldFields", List.of("detectorMetricsJson")));
        return copy;
    }

    public List<Map<String, Object>> labelResults(List<LabelResultView> source, SensitiveReadAccessService.Access access) {
        boolean revealPii = access.fullConversation() && access.pii();
        return source.stream().map(row -> {
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("id", row.id()); copy.put("conversationId", row.conversationId()); copy.put("sourceFileName", fileName(row.sourceFileName(), revealPii));
            copy.put("labelId", row.labelId()); copy.put("labelName", text(row.labelName(), revealPii));
            copy.put("labelCode", text(row.labelCode(), revealPii)); copy.put("labelPath", text(row.labelPath(), revealPii));
            copy.put("labelVersionNo", row.labelVersionNo()); copy.put("valueCode", row.valueCode());
            copy.put("valueJson", jsonText(row.valueJson(), revealPii, true)); copy.put("confidence", row.confidence());
            copy.put("generationSource", row.generationSource()); copy.put("sourceRuleResultId", row.sourceRuleResultId());
            copy.put("evidenceJson", jsonText(row.evidenceJson(), revealPii)); copy.put("status", row.status());
            copy.put("reviewOverlay", review(row.reviewOverlay(), revealPii)); copy.put("findingSource", row.findingSource());
            copy.put("privacy", Map.of("policyVersion", "iqc-result-read-v1", "piiMasked", !revealPii,
                    "canReadFull", access.fullConversation(), "canReadPii", access.pii()));
            return copy;
        }).toList();
    }

    private Object review(io.github.opensabre.iqc.label.LabelResultReviewService.ReviewOverlay source, boolean revealPii) {
        if (source == null) return null;
        var copy = new LinkedHashMap<String, Object>();
        copy.put("latestRevision", source.latestRevision()); copy.put("latestStatus", source.latestStatus());
        copy.put("effectiveReviewId", source.effectiveReviewId()); copy.put("effectiveRevision", source.effectiveRevision());
        copy.put("effectiveStatus", source.effectiveStatus());
        copy.put("effectiveValue", source.effectiveValue() == null ? null : project(source.effectiveValue(), revealPii, true));
        copy.put("evidenceMessageIds", source.evidenceMessageIds()); return copy;
    }

    private InspectionResult projectResult(InspectionResult source, boolean revealPii) {
        var copy = new InspectionResult(); BeanUtils.copyProperties(source, copy);
        copy.setSourceFileName(fileName(copy.getSourceFileName(), revealPii));
        copy.setReason(text(copy.getReason(), revealPii)); copy.setEvidence(text(copy.getEvidence(), revealPii));
        copy.setFindingJson(jsonText(copy.getFindingJson(), revealPii));
        copy.setEvidenceJson(jsonText(copy.getEvidenceJson(), revealPii));
        copy.setSuggestionJson(jsonText(copy.getSuggestionJson(), revealPii));
        copy.setRuleBreakdownJson(jsonText(copy.getRuleBreakdownJson(), revealPii));
        copy.setDetectorMetricsJson(null);
        return copy;
    }

    public Map<String, Object> detail(Map<String, Object> source, SensitiveReadAccessService.Access access) {
        var result = new LinkedHashMap<String, Object>();
        Object original = source.get("result"), message = source.get("message"), task = source.get("task"), execution = source.get("execution");
        result.put("result", original instanceof InspectionResult row ? result(row, access) : null);
        if (message instanceof ConversationMessage row) {
            var copy = new ConversationMessage(); BeanUtils.copyProperties(row, copy);
            String content = text(copy.getContent(), access.pii());
            copy.setContent(access.fullConversation() ? content : limit(content, PREVIEW_TEXT_LENGTH));
            copy.setRawLine(null); message = copy;
        }
        result.put("message", message);
        result.put("task", task instanceof InspectionTask row ? task(row, access) : task);
        result.put("taskPresentation", taskPresentation(task instanceof InspectionTask row ? row : null));
        if (execution instanceof io.github.opensabre.iqc.task.model.TaskExecution row) {
            var copy = new io.github.opensabre.iqc.task.model.TaskExecution(); BeanUtils.copyProperties(row, copy);
            copy.setErrorMessage(text(copy.getErrorMessage(), false)); execution = copy;
        }
        result.put("execution", execution);
        result.put("privacy", privacy(access, message == null ? 0 : 1, message == null ? 0 : 1,
                message != null && !access.fullConversation(),
                List.of("rawLine", "agentSnapshotJson", "ruleSnapshotJson", "labelScopeSnapshotJson", "triggerRequestJson")));
        return result;
    }

    /** Public display facts only; no prompt, rule text, identifiers or arbitrary snapshot fields. */
    public record TaskPresentation(String schemaVersion, String kind, Boolean labelOnly) { }
    private TaskPresentation taskPresentation(InspectionTask task) {
        String kind = "UNKNOWN"; Boolean labelOnly = null;
        if (task != null && task.getRuleSnapshotJson() != null) {
            try {
                JsonNode snapshot = json.readTree(task.getRuleSnapshotJson());
                if (snapshot != null && snapshot.isArray()) { kind = "LEGACY"; labelOnly = false; }
                else if (snapshot != null && snapshot.isObject()) {
                    boolean scheme = snapshot.has("schemeSnapshot"), pipeline = snapshot.has("pipelineSnapshot");
                    if (pipeline && !scheme && snapshot.path("pipelineSnapshot").isObject()) {
                        kind = "PIPELINE"; labelOnly = false;
                    } else if (scheme && !pipeline && snapshot.path("schemeSnapshot").isObject()) {
                        kind = "SCHEME";
                        JsonNode definition = snapshot.at("/schemeSnapshot/release/definition");
                        if (definition.path("items").isArray() && !definition.path("items").isEmpty())
                            labelOnly = false; // Historical scoring schemes may omit their optional labels field.
                        else if (definition.path("items").isArray() && definition.path("labels").isArray()
                                && !definition.path("labels").isEmpty()) labelOnly = true;
                    } else if (!scheme && !pipeline && snapshot.path("rules").isArray()) {
                        kind = "LEGACY"; labelOnly = false;
                    }
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                // Malformed history stays unknown; never fall back to exposing its snapshot.
            }
        }
        return new TaskPresentation("iqc-task-presentation-v1", kind, labelOnly);
    }

    private InspectionTask task(InspectionTask source, SensitiveReadAccessService.Access access) {
        if (source == null) return null;
        var copy = new InspectionTask(); BeanUtils.copyProperties(source, copy);
        copy.setAgentSnapshotJson(access.prompt() ? jsonText(copy.getAgentSnapshotJson(), access.fullConversation() && access.pii()) : null);
        copy.setRuleSnapshotJson(access.prompt() ? jsonText(copy.getRuleSnapshotJson(), access.fullConversation() && access.pii()) : null);
        copy.setLabelScopeSnapshotJson(null); copy.setTriggerRequestJson(null); copy.setAutoExpandPrompt(null);
        copy.setRetryError(text(copy.getRetryError(), false)); copy.setTriggerError(text(copy.getTriggerError(), false));
        return copy;
    }

    private BatchResultQueryService.ConversationResultSummary summary(
            BatchResultQueryService.ConversationResultSummary source, boolean revealPii) {
        if (source == null) return null;
        return new BatchResultQueryService.ConversationResultSummary(source.conversationId(),
                fileName(source.sourceFileName(), revealPii), source.messageCount(), source.resultCount(), source.averageScore(),
                source.hitCount(), source.highRiskCount(), source.errorCount(), source.scoreStatus());
    }

    private Map<String, Object> privacy(SensitiveReadAccessService.Access access, int visible, int total,
                                        boolean contentLimited, List<String> withheld) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("policyVersion", "iqc-result-read-v1"); metadata.put("piiMasked", !(access.fullConversation() && access.pii()));
        metadata.put("contentLimited", contentLimited); metadata.put("visibleMessageCount", visible); metadata.put("totalMessageCount", total);
        metadata.put("messageContentCount", contentLimited ? Math.min(PREVIEW_MESSAGE_COUNT, total) : total);
        int unmasked = access.pii() ? (contentLimited ? Math.min(PREVIEW_MESSAGE_COUNT, total) : total) : 0;
        metadata.put("messageContentPiiMasked", unmasked == 0);
        metadata.put("piiUnmaskedMessageCount", unmasked);
        metadata.put("piiUnmaskedFields", unmasked == 0 ? List.of() : List.of("messages[].content"));
        metadata.put("canReadFull", access.fullConversation()); metadata.put("canReadPii", access.pii());
        metadata.put("withheldFields", List.copyOf(withheld)); return metadata;
    }

    private String text(String value, boolean revealPii) { return value == null || revealPii ? value : SensitiveTextProjection.mask(value).text(); }
    private String fileName(String value, boolean revealPii) {
        return revealPii ? value : ConversationReadProjection.maskedSourceFileName(value);
    }
    private String jsonText(String value, boolean revealPii) { return jsonText(value,revealPii,false); }
    private String jsonText(String value, boolean revealPii, boolean payload) {
        if (value == null || value.isBlank() || revealPii) return value;
        try { return project(json.readTree(value), false, payload).toString(); }
        catch (com.fasterxml.jackson.core.JsonProcessingException malformed) { return "{\"privacyProjection\":\"WITHHELD_INVALID_JSON\"}"; }
    }
    private static final Set<String> PAYLOAD_FIELDS = Set.of("value", "effectiveValue", "variables",
            "payload", "input", "output", "arguments", "parameters", "configuration", "businessContext");
    private JsonNode project(JsonNode source, boolean revealPii, boolean payload) {
        if (source == null || source.isNull()) return source;
        if (revealPii) return source.deepCopy();
        if (source.isTextual()) return json.getNodeFactory().textNode(SensitiveTextProjection.mask(source.textValue()).text());
        if (source.isArray()) { ArrayNode copy = json.createArrayNode(); source.forEach(value -> copy.add(project(value, false, payload))); return copy; }
        if (source.isObject()) {
            ObjectNode copy = json.createObjectNode();
            source.properties().forEach(entry -> {
                String key=entry.getKey(); JsonNode value=entry.getValue();
                boolean structural=STRUCTURAL_TEXT.contains(key);
                boolean protectedValue=payload || PAYLOAD_FIELDS.contains(key) || key.endsWith("Hash") && value.isContainerNode()
                        || structural && value.isContainerNode()
                        && !("messageIds".equals(key) && value.isArray() && textualArray(value));
                boolean safeHash=key.endsWith("Hash") && value.isTextual() && value.textValue().matches("[a-fA-F0-9]{64}");
                // A structural field never exempts an arbitrary object or a business value subtree.
                if (!protectedValue && (structural && (value.isTextual() || "messageIds".equals(key) && value.isArray() && textualArray(value)) || safeHash))
                    copy.set(key,value.deepCopy());
                else copy.set(key,project(value,false,protectedValue));
            });
            return copy;
        }
        return source.deepCopy();
    }
    private static boolean textualArray(JsonNode values) {
        for (JsonNode value:values) if (!value.isTextual()) return false;
        return true;
    }
    private String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        int end = max; if (Character.isHighSurrogate(value.charAt(end - 1)) && Character.isLowSurrogate(value.charAt(end))) end--;
        return value.substring(0, end);
    }
    public io.github.opensabre.iqc.quality.model.QualitySample sample(io.github.opensabre.iqc.quality.model.QualitySample source,
            SensitiveReadAccessService.Access access) { return gold.sample(source, access, false); }
    public List<io.github.opensabre.iqc.quality.model.QualitySample> samples(List<io.github.opensabre.iqc.quality.model.QualitySample> source,
            SensitiveReadAccessService.Access access) { return source.stream().map(row -> gold.sample(row, access, false)).toList(); }
    public io.github.opensabre.iqc.quality.model.QualitySample goldSample(io.github.opensabre.iqc.quality.model.QualitySample source,
            SensitiveReadAccessService.Access access) { return gold.sample(source,access,true); }
    public io.github.opensabre.iqc.quality.model.QualitySample sampleMetadata(io.github.opensabre.iqc.quality.model.QualitySample source,
            SensitiveReadAccessService.Access access) { return gold.sampleMetadata(source,access); }
    public io.github.opensabre.iqc.quality.model.QualitySampleVersion sampleVersionMetadata(io.github.opensabre.iqc.quality.model.QualitySampleVersion source,
            SensitiveReadAccessService.Access access) { return gold.versionMetadata(source,access); }
    public io.github.opensabre.iqc.quality.model.QualitySampleVersion sampleVersion(io.github.opensabre.iqc.quality.model.QualitySampleVersion source,
            SensitiveReadAccessService.Access access) { return gold.version(source, access); }
    public io.github.opensabre.iqc.shared.IqcPage<io.github.opensabre.iqc.quality.model.QualitySampleVersion> sampleHistory(
            io.github.opensabre.iqc.shared.IqcPage<io.github.opensabre.iqc.quality.model.QualitySampleVersion> source,
            SensitiveReadAccessService.Access access) {
        return new io.github.opensabre.iqc.shared.IqcPage<>(source.records().stream().map(row -> gold.versionMetadata(row, access)).toList(),
                source.current(), source.size(), source.total());
    }
    /** HTTP copy only: history and write receipts never include embedded gold. */
    public io.github.opensabre.iqc.quality.model.EvaluationDatasetVersion datasetVersion(
            io.github.opensabre.iqc.quality.model.EvaluationDatasetVersion source,
            SensitiveReadAccessService.Access access, boolean dedicatedRead) {
        Objects.requireNonNull(access); if (source == null) return null;
        var copy = new io.github.opensabre.iqc.quality.model.EvaluationDatasetVersion();
        BeanUtils.copyProperties(source, copy);
        boolean canonical = dedicatedRead && access.fullConversation() && access.pii();
        var metadata = new LinkedHashMap<String,Object>();
        metadata.put("policyVersion", "iqc-dataset-read-v1");
        metadata.put("canonicalPayloadVisible", canonical);
        metadata.put("canReadFull", access.fullConversation()); metadata.put("canReadPii", access.pii());
        metadata.put("withheldFields", canonical ? List.of() : List.of("snapshotJson"));
        if (!canonical) {
            copy.setSnapshotJson(null);
            metadata.put("previewState", dedicatedRead ? "NOT_INCLUDED" : "SUMMARY_ONLY");
            if (dedicatedRead && source.getSnapshotJson() != null) {
                try {
                    if (source.getSnapshotJson().length() > 64 * 1024 * 1024) throw new IllegalArgumentException();
                    var frozen = json.readValue(source.getSnapshotJson(), io.github.opensabre.iqc.quality.evaluation.DatasetSnapshot.class);
                    if (!Objects.equals(source.getDatasetId(), frozen.datasetId())) throw new IllegalArgumentException();
                    var previews = new ArrayList<Map<String,Object>>();
                    for (var member : frozen.members()) {
                        var version = new io.github.opensabre.iqc.quality.model.QualitySampleVersion();
                        version.setSampleId(member.sampleId()); version.setSnapshotJson(json.writeValueAsString(member.gold()));
                        var shown = gold.version(version, access);
                        var item = new LinkedHashMap<String,Object>();
                        item.put("sampleId", member.sampleId()); item.put("versionNo", member.versionNo());
                        item.put("contentHash", member.contentHash()); item.put("privacy", shown.getPrivacy());
                        previews.add(item);
                    }
                    metadata.put("members", previews); metadata.put("previewState", "AVAILABLE");
                } catch (java.io.IOException | RuntimeException invalid) {
                    metadata.put("previewState", "INVALID_DATASET");
                }
            }
        }
        copy.setPrivacy(metadata); return copy;
    }

    /** Raw machine output may contain reproduced input or prompts; summaries never substitute for it. */
    public io.github.opensabre.iqc.quality.model.EvaluationSampleResult evaluationResult(
            io.github.opensabre.iqc.quality.model.EvaluationSampleResult source,
            SensitiveReadAccessService.Access access, boolean dedicatedRead) {
        Objects.requireNonNull(access); if(source==null)return null;
        var copy=new io.github.opensabre.iqc.quality.model.EvaluationSampleResult();
        BeanUtils.copyProperties(source,copy);
        boolean canonical=dedicatedRead && access.fullConversation() && access.pii() && access.prompt();
        var metadata=new LinkedHashMap<String,Object>();
        metadata.put("policyVersion","iqc-evaluation-read-v1"); metadata.put("canonicalPayloadVisible",canonical);
        metadata.put("canReadFull",access.fullConversation()); metadata.put("canReadPii",access.pii());
        metadata.put("canReadPrompt",access.prompt());
        metadata.put("withheldFields",canonical?List.of():List.of("actualJson"));
        if(!canonical){
            copy.setActualJson(null); metadata.put("previewState",dedicatedRead?"NOT_INCLUDED":"SUMMARY_ONLY");
            if(dedicatedRead && source.getActualJson()!=null){
                try{
                    if(source.getActualJson().length()>64*1024*1024)throw new IllegalArgumentException();
                    var body=json.readTree(source.getActualJson()); var run=body.path("run");
                    var status=run.path("status").asText();
                    if(!Set.of("SUCCESS","PARTIAL_SUCCESS","FAILED","CANCELLED").contains(status))throw new IllegalArgumentException();
                    if(!run.path("trace").isArray() || !run.path("findings").isArray())throw new IllegalArgumentException();
                    var preview=new LinkedHashMap<String,Object>();
                    preview.put("runStatus",status); preview.put("traceCount",run.path("trace").size());
                    preview.put("findingCount",run.path("findings").size());
                    if("POLICY_SATISFIED".equals(run.path("completionReason").asText()))preview.put("completionReason","POLICY_SATISFIED");
                    metadata.put("preview",preview);metadata.put("previewState","AVAILABLE");
                }catch(java.io.IOException | RuntimeException invalid){metadata.put("previewState","INVALID_OUTPUT");}
            }
        }
        copy.setPrivacy(metadata);return copy;
    }

    /** A command grant acknowledges a created task without granting its frozen inputs. */
    public InspectionTask taskReceipt(InspectionTask source, SensitiveReadAccessService.Access access) {
        Objects.requireNonNull(access);if(source==null)return null;
        var copy=new InspectionTask();BeanUtils.copyProperties(source,copy);
        copy.setName(text(copy.getName(),access.fullConversation() && access.pii()));
        copy.setAgentSnapshotJson(null);copy.setRuleSnapshotJson(null);copy.setLabelScopeSnapshotJson(null);
        copy.setAutoExpandPrompt(null);copy.setTriggerRequestJson(null);copy.setRetryPolicyJson(null);
        copy.setSelectionFilterJson(null);copy.setConversationIdsJson(null);copy.setRuleIdsJson(null);
        copy.setRetryError(null);copy.setTriggerError(null);
        copy.setTaskPresentation(taskPublicMetadata(source));
        return copy;
    }

    public io.github.opensabre.iqc.quality.evaluation.BenchmarkService.Pair benchmarkPair(
            io.github.opensabre.iqc.quality.evaluation.BenchmarkService.Pair source,
            SensitiveReadAccessService.Access access) {
        Objects.requireNonNull(access);if(source==null)return null;
        return new io.github.opensabre.iqc.quality.evaluation.BenchmarkService.Pair(source.schemaVersion(),source.experimentId(),
                text(source.name(),access.fullConversation() && access.pii()),source.datasetId(),source.datasetVersion(),
                source.datasetHash(),source.selection(),source.comparison(),taskReceipt(source.baseline(),access),taskReceipt(source.candidate(),access));
    }
    public io.github.opensabre.iqc.quality.evaluation.BenchmarkService.Report benchmarkReport(
            io.github.opensabre.iqc.quality.evaluation.BenchmarkService.Report source,
            SensitiveReadAccessService.Access access) {
        Objects.requireNonNull(access);if(source==null)return null;
        return new io.github.opensabre.iqc.quality.evaluation.BenchmarkService.Report(source.schemaVersion(),
                benchmarkPair(source.experiment(),access),source.baseline(),source.candidate(),source.complete(),source.deltas());
    }

    public io.github.opensabre.iqc.quality.model.EvaluationDataset dataset(
            io.github.opensabre.iqc.quality.model.EvaluationDataset source,
            SensitiveReadAccessService.Access access, boolean detailRead) {
        Objects.requireNonNull(access);if(source==null)return null;
        var copy=new io.github.opensabre.iqc.quality.model.EvaluationDataset();BeanUtils.copyProperties(source,copy);
        boolean canonical=detailRead && access.fullConversation() && access.pii();
        copy.setName(text(copy.getName(),access.fullConversation() && access.pii()));
        var metadata=new LinkedHashMap<String,Object>();
        metadata.put("policyVersion","iqc-dataset-draft-read-v1");metadata.put("canonicalPayloadVisible",canonical);
        metadata.put("canReadFull",access.fullConversation());metadata.put("canReadPii",access.pii());
        metadata.put("withheldFields",canonical?List.of():List.of("draftConfigJson"));
        if(!canonical){
            copy.setDraftConfigJson(null);metadata.put("previewState",detailRead?"NOT_INCLUDED":"SUMMARY_ONLY");
            if(detailRead && source.getDraftConfigJson()!=null){
                try{
                    if(source.getDraftConfigJson().length()>1024*1024)throw new IllegalArgumentException();
                    var draft=json.readValue(source.getDraftConfigJson(),io.github.opensabre.iqc.quality.evaluation.DatasetDraft.class);
                    metadata.put("members",draft.members());metadata.put("previewState","AVAILABLE");
                }catch(java.io.IOException | RuntimeException invalid){metadata.put("previewState","INVALID_DRAFT");}
            }
        }
        copy.setPrivacy(metadata);return copy;
    }

    private Map<String,Object> taskPublicMetadata(InspectionTask source) {
        var basic=taskPresentation(source);var result=new LinkedHashMap<String,Object>();
        result.put("schemaVersion",basic.schemaVersion());result.put("kind",basic.kind());
        result.put("labelOnly",basic.labelOnly());
        result.put("hasLabels",null);
        Integer conversationCount=null;
        if(source.getConversationIdsJson()!=null){
            try{
                var ids=json.readTree(source.getConversationIdsJson());
                if(ids.isArray()&&java.util.stream.StreamSupport.stream(ids.spliterator(),false).allMatch(JsonNode::isTextual))
                    conversationCount=ids.size();
            }catch(java.io.IOException | RuntimeException invalid){ /* Unknown count remains explicit. */ }
        }else if(source.getConversationId()!=null)conversationCount=1;
        result.put("conversationCount",conversationCount);
        if("SCHEME".equals(basic.kind())) {
            try {
                var snapshot=json.readTree(source.getRuleSnapshotJson()).path("schemeSnapshot");
                var labels=snapshot.at("/release/definition/labels");
                if(labels.isArray())result.put("hasLabels",!labels.isEmpty());
                else if(source.getLabelScopeSnapshotJson()!=null)result.put("hasLabels",true);
                var revision=snapshot.path("draftRevision");
                if(revision.isIntegralNumber()&&revision.canConvertToInt()&&revision.intValue()>0)
                    result.put("schemeDraftRevision",revision.intValue());
                var variant=snapshot.path("selectedVariantCode");
                if(variant.isTextual()&&variant.textValue().matches("[A-Za-z0-9_-]{1,64}"))
                    result.put("selectedVariantCode",variant.textValue());
            }catch(java.io.IOException | RuntimeException invalid){
                // Unknown display metadata cannot substitute for frozen publication evidence.
            }
        }
        if("PIPELINE_EVALUATION".equals(source.getTaskType()) && source.getRuleSnapshotJson()!=null){
            try{
                var marker=json.readTree(source.getRuleSnapshotJson()).path("benchmarkSnapshot");
                var peer=marker.path("peerTaskId");var side=marker.path("side").asText();
                if("iqc-benchmark-pair-v1".equals(marker.path("schemaVersion").asText())
                        && peer.isTextual() && peer.textValue().matches("[A-Za-z0-9_-]{1,64}")
                        && source.getId()!=null && source.getId().matches("[A-Za-z0-9_-]{1,64}")
                        && !source.getId().equals(peer.textValue()) && Set.of("BASELINE","CANDIDATE").contains(side))
                    result.put("benchmarkBaselineTaskId","BASELINE".equals(side)?source.getId():peer.textValue());
            }catch(java.io.IOException | RuntimeException invalid){ /* Navigation stays unavailable for malformed history. */ }
        }
        if("PIPELINE".equals(source.getTaskType())&&source.getRuleSnapshotJson()!=null){
            try{
                var root=json.readTree(source.getRuleSnapshotJson());
                if(root.has("rolloutSnapshot")){
                    var evidence=io.github.opensabre.iqc.pipelineasset.PipelineRolloutTaskEvidence.read(root.path("rolloutSnapshot"),json);
                    evidence.verifyFrozen(source.getId(),root.path("pipelineSnapshot"),json);
                    var revision=evidence.revision();var decision=evidence.decision();
                    result.put("rollout",Map.of("schemaVersion","iqc-rollout-presentation-v1","policyRevision",revision.revisionNo(),
                            "side",decision.side().name(),"selectedVersionNo",decision.selectedVersionNo(),"selectedReleaseHash",decision.selectedReleaseHash()));
                }
            }catch(java.io.IOException|RuntimeException invalid){ /* Invalid or absent history never becomes a public routing claim. */ }
        }
        return result;
    }

    /** Frozen task snapshots may embed full gold inputs and prompts. */
    public InspectionTask taskRead(InspectionTask source,SensitiveReadAccessService.Access access,boolean detailRead){
        Objects.requireNonNull(access);if(source==null)return null;
        var copy=taskReceipt(source,access);
        boolean snapshots=detailRead && access.fullConversation() && access.pii() && access.prompt();
        if(snapshots){
            copy.setAgentSnapshotJson(source.getAgentSnapshotJson());copy.setRuleSnapshotJson(source.getRuleSnapshotJson());
            copy.setLabelScopeSnapshotJson(source.getLabelScopeSnapshotJson());copy.setAutoExpandPrompt(source.getAutoExpandPrompt());
        }
        var metadata=new LinkedHashMap<String,Object>();metadata.put("policyVersion","iqc-task-read-v1");
        metadata.put("snapshotsVisible",snapshots);metadata.put("canReadFull",access.fullConversation());
        metadata.put("canReadPii",access.pii());metadata.put("canReadPrompt",access.prompt());
        var withheld=new ArrayList<>(List.of("selectionFilterJson","conversationIdsJson","ruleIdsJson","triggerRequestJson","retryPolicyJson","retryError","triggerError"));
        if(!snapshots)withheld.addAll(List.of("agentSnapshotJson","ruleSnapshotJson","labelScopeSnapshotJson","autoExpandPrompt"));
        metadata.put("withheldFields",withheld);copy.setPrivacy(metadata);return copy;
    }

    public io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.CallPage gatewayCalls(
            io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.CallPage source,SensitiveReadAccessService.Access access){
        Objects.requireNonNull(source);Objects.requireNonNull(access);
        boolean reveal=access.fullConversation() && access.pii() && access.prompt();
        var rows=source.records().stream().map(row -> {
            var original=row.reconciliation();
            var reconciliation=original==null?null:new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.ReconciliationView(
                    original.callId(),original.modelKey(),original.tokens(),original.cost(),original.currency(),original.atUtc(),
                    original.owner(),original.userId(),reveal?original.evidence():null);
            return new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.CallView(
                    row.callId(),row.modelKey(),row.logicalRecordId(),row.ruleId(),
                    row.owner(),row.status(),row.reservedAtUtc(),row.settledAtUtc(),
                    row.inputTokens(),row.outputTokens(),row.totalTokens(),text(row.responseModelId(),reveal),
                    row.usageStatus(),row.errorType(),row.capacityReleasedAtUtc(),row.capacityReleasedBy(),
                    row.capacityReleaseReason(),reveal ? row.capacityReleaseEvidence() : null,row.taskId(),row.executionId(),
                    row.nodeId(),row.runIndex(),row.conversationId(),row.modelProfileId(),
                    row.modelProfileVersion(),row.pricingHash(),row.pricingCurrency(),row.calculatedCost(),
                    row.costStatus(),text(row.requestedModelName(),reveal),row.cacheReadInputTokens(),row.cacheWriteInputTokens(),
                    row.maxOutputTokens(),row.outputTokenParameter(),row.outputLimitStatus(),row.toolRoundIndex(),
                    row.contextWindowTokens(),text(row.deploymentRef(),reveal),row.toolExecutionStartedAtUtc(),row.budgetReservedTokens(),
                    row.budgetReservedCost(),row.budgetCurrency(),row.budgetPolicyVersion(),row.budgetStatus(),
                    reveal ? row.budgetEvidenceRef() : null,reconciliation);
        }).toList();
        var metadata=new LinkedHashMap<String,Object>();metadata.put("policyVersion","iqc-model-call-read-v1");
        metadata.put("evidenceVisible",reveal);metadata.put("canReadFull",access.fullConversation());
        metadata.put("canReadPii",access.pii());metadata.put("canReadPrompt",access.prompt());
        metadata.put("withheldFields",reveal?List.of():List.of("capacityReleaseEvidence","budgetEvidenceRef","reconciliation.evidence"));
        return new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.CallPage(rows,source.current(),source.size(),source.total(),source.costSummary(),metadata);
    }

    private Map<String,Object> schedulePrivacy(SensitiveReadAccessService.Access access,boolean canonical,List<String> withheld){
        var result=new LinkedHashMap<String,Object>();result.put("policyVersion","iqc-schedule-read-v1");
        result.put("canonicalPayloadVisible",canonical);result.put("canReadFull",access.fullConversation());
        result.put("canReadPii",access.pii());result.put("canReadPrompt",access.prompt());
        result.put("withheldFields",canonical?List.of():withheld);return result;
    }

    public io.github.opensabre.iqc.schedule.model.PeriodicSchedule periodicSchedule(io.github.opensabre.iqc.schedule.model.PeriodicSchedule source,
            SensitiveReadAccessService.Access access,boolean detailRead){
        Objects.requireNonNull(access);if(source==null)return null;
        var copy=new io.github.opensabre.iqc.schedule.model.PeriodicSchedule();BeanUtils.copyProperties(source,copy);
        boolean canonical=detailRead && access.fullConversation() && access.pii() && access.prompt();
        copy.setName(text(copy.getName(),access.fullConversation() && access.pii()));
        if(!canonical){copy.setDraftConfigJson(null);copy.setLastError(null);}
        copy.setPrivacy(schedulePrivacy(access,canonical,List.of("draftConfigJson","lastError")));
        return copy;
    }

    public io.github.opensabre.iqc.schedule.model.EventSubscription eventSubscription(io.github.opensabre.iqc.schedule.model.EventSubscription source,
            SensitiveReadAccessService.Access access,boolean detailRead){
        Objects.requireNonNull(access);if(source==null)return null;
        var copy=new io.github.opensabre.iqc.schedule.model.EventSubscription();BeanUtils.copyProperties(source,copy);
        boolean canonical=detailRead && access.fullConversation() && access.pii() && access.prompt();
        copy.setName(text(copy.getName(),access.fullConversation() && access.pii()));
        if(!canonical){copy.setDraftConfigJson(null);copy.setLastError(null);}
        copy.setPrivacy(schedulePrivacy(access,canonical,List.of("draftConfigJson","lastError")));
        return copy;
    }

    public io.github.opensabre.iqc.schedule.model.PeriodicScheduleRevision periodicRevision(io.github.opensabre.iqc.schedule.model.PeriodicScheduleRevision source,
            SensitiveReadAccessService.Access access,boolean detailRead){
        Objects.requireNonNull(access);if(source==null)return null;
        var copy=new io.github.opensabre.iqc.schedule.model.PeriodicScheduleRevision();BeanUtils.copyProperties(source,copy);
        boolean canonical=detailRead && access.fullConversation() && access.pii() && access.prompt();
        if(!canonical)copy.setSnapshotJson(null);
        copy.setPrivacy(schedulePrivacy(access,canonical,List.of("snapshotJson")));
        return copy;
    }

    public io.github.opensabre.iqc.schedule.model.EventSubscriptionRevision subscriptionRevision(io.github.opensabre.iqc.schedule.model.EventSubscriptionRevision source,
            SensitiveReadAccessService.Access access,boolean detailRead){
        Objects.requireNonNull(access);if(source==null)return null;
        var copy=new io.github.opensabre.iqc.schedule.model.EventSubscriptionRevision();BeanUtils.copyProperties(source,copy);
        boolean canonical=detailRead && access.fullConversation() && access.pii() && access.prompt();
        if(!canonical)copy.setSnapshotJson(null);
        copy.setPrivacy(schedulePrivacy(access,canonical,List.of("snapshotJson")));
        return copy;
    }

    public io.github.opensabre.iqc.selector.ConversationSelectionService.Preview selectorPreview(
            io.github.opensabre.iqc.selector.ConversationSelectionService.Preview source,SensitiveReadAccessService.Access access){
        Objects.requireNonNull(source);Objects.requireNonNull(access);
        boolean reveal=access.fullConversation() && access.pii();
        var matched=source.matchedDistribution();var selected=source.selectedDistribution();
        if(!reveal){
            if(matched!=null)matched=new io.github.opensabre.iqc.selector.ConversationSelectionService.Distribution(null,matched.teams(),matched.departments());
            if(selected!=null)selected=new io.github.opensabre.iqc.selector.ConversationSelectionService.Distribution(null,selected.teams(),selected.departments());
        }
        var rows=source.preview().stream().map(row -> new io.github.opensabre.iqc.selector.ConversationSelectionService.PreviewConversation(
                row.conversationId(),fileName(row.fileName(),reveal),reveal?row.employeeId():null,row.employeeGroupId(),
                row.employeeDepartmentId(),text(row.channel(),reveal),row.startedTime(),row.riskLevel())).toList();
        var metadata=new LinkedHashMap<String,Object>();metadata.put("policyVersion","iqc-selector-preview-read-v1");
        metadata.put("piiVisible",reveal);metadata.put("canReadFull",access.fullConversation());metadata.put("canReadPii",access.pii());
        metadata.put("withheldFields",reveal?List.of():List.of("preview.employeeId","matchedDistribution.employees","selectedDistribution.employees"));
        metadata.put("maskedFields",reveal?List.of():List.of("preview.fileName","preview.channel"));
        return new io.github.opensabre.iqc.selector.ConversationSelectionService.Preview(source.schemaVersion(),source.anchor(),source.resolvedWindow(),
                source.candidateCount(),source.matchedCount(),source.sampledCount(),source.selectedCount(),source.limited(),matched,selected,rows,source.definitionHash(),metadata);
    }

    private Map<String,Object> selectorPrivacy(SensitiveReadAccessService.Access access,boolean canonical,List<String> withheld){
        var result=new LinkedHashMap<String,Object>();result.put("policyVersion","iqc-selector-read-v1");
        result.put("canonicalPayloadVisible",canonical);result.put("canReadFull",access.fullConversation());result.put("canReadPii",access.pii());
        result.put("withheldFields",canonical?List.of():withheld);return result;
    }
    public io.github.opensabre.iqc.selector.model.SelectorAsset selectorAsset(io.github.opensabre.iqc.selector.model.SelectorAsset source,
            SensitiveReadAccessService.Access access,boolean detailRead){
        Objects.requireNonNull(access);if(source==null)return null;
        var copy=new io.github.opensabre.iqc.selector.model.SelectorAsset();BeanUtils.copyProperties(source,copy);
        boolean canonical=detailRead && access.fullConversation() && access.pii();
        copy.setName(text(copy.getName(),access.fullConversation() && access.pii()));
        if(!canonical)copy.setDraftConfigJson(null);
        copy.setPrivacy(selectorPrivacy(access,canonical,List.of("draftConfigJson")));return copy;
    }
    public io.github.opensabre.iqc.selector.model.SelectorAssetVersion selectorHistory(io.github.opensabre.iqc.selector.model.SelectorAssetVersion source,
            SensitiveReadAccessService.Access access){
        Objects.requireNonNull(access);if(source==null)return null;
        var copy=new io.github.opensabre.iqc.selector.model.SelectorAssetVersion();BeanUtils.copyProperties(source,copy);
        copy.setSnapshotJson(null);copy.setPrivacy(selectorPrivacy(access,false,List.of("snapshotJson")));return copy;
    }
    public io.github.opensabre.iqc.selector.SelectorAssetService.Prepared publishedSelector(io.github.opensabre.iqc.selector.SelectorAssetService.Prepared source,
            SensitiveReadAccessService.Access access){
        Objects.requireNonNull(access);if(source==null)return null;
        boolean canonical=access.fullConversation() && access.pii();String body=null;
        if(canonical)try{body=json.writeValueAsString(source.definition());}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw io.github.opensabre.iqc.governance.IqcException.invalidState("选择器定义展示不可用");}
        return new io.github.opensabre.iqc.selector.SelectorAssetService.Prepared(source.selectorId(),source.versionNo(),source.sourceDraftRevision(),
                canonical?source.definition():null,source.contentHash(),source.ownerGroupId(),body,selectorPrivacy(access,canonical,List.of("definition","definitionJson")),source.availableForNewTasks());
    }

    private Map<String,Object> configurationPrivacy(String policyVersion,SensitiveReadAccessService.Access access,boolean canonical,List<String> withheld){
        var metadata=new LinkedHashMap<String,Object>();metadata.put("policyVersion",policyVersion);
        metadata.put("canonicalPayloadVisible",canonical);metadata.put("canReadFull",access.fullConversation());
        metadata.put("canReadPii",access.pii());metadata.put("canReadPrompt",access.prompt());
        metadata.put("withheldFields",canonical?List.of():withheld);return metadata;
    }
    public io.github.opensabre.iqc.pipelineasset.model.PipelineAsset pipelineAsset(
            io.github.opensabre.iqc.pipelineasset.model.PipelineAsset source,SensitiveReadAccessService.Access access,boolean detailRead){
        Objects.requireNonNull(access);if(source==null)return null;
        var copy=new io.github.opensabre.iqc.pipelineasset.model.PipelineAsset();BeanUtils.copyProperties(source,copy);
        boolean canonical=detailRead&&access.fullConversation()&&access.pii()&&access.prompt();
        copy.setName(text(copy.getName(),access.fullConversation()&&access.pii()));
        if(!canonical)copy.setDraftConfigJson(null);
        copy.setPrivacy(configurationPrivacy("iqc-pipeline-read-v1",access,canonical,List.of("draftConfigJson")));return copy;
    }
    public io.github.opensabre.iqc.pipelineasset.model.PipelineAssetVersion pipelineVersion(
            io.github.opensabre.iqc.pipelineasset.model.PipelineAssetVersion source,SensitiveReadAccessService.Access access,boolean detailRead){
        Objects.requireNonNull(access);if(source==null)return null;
        var copy=new io.github.opensabre.iqc.pipelineasset.model.PipelineAssetVersion();BeanUtils.copyProperties(source,copy);
        boolean canonical=detailRead&&access.fullConversation()&&access.pii()&&access.prompt();
        if(!canonical){copy.setSnapshotJson(null);copy.setPublicationDecisionJson(null);}
        copy.setPrivacy(configurationPrivacy("iqc-pipeline-read-v1",access,canonical,List.of("snapshotJson","publicationDecisionJson")));return copy;
    }

    public io.github.opensabre.iqc.skill.model.IqcSkill skill(
            io.github.opensabre.iqc.skill.model.IqcSkill source,SensitiveReadAccessService.Access access,boolean detailRead){
        Objects.requireNonNull(access);if(source==null)return null;
        var copy=new io.github.opensabre.iqc.skill.model.IqcSkill();BeanUtils.copyProperties(source,copy);
        boolean canonical=detailRead&&access.fullConversation()&&access.pii()&&access.prompt();
        copy.setName(text(copy.getName(),access.fullConversation()&&access.pii()));
        if(!canonical){copy.setDescription(null);copy.setInstructions(null);copy.setInputSchemaJson(null);copy.setOutputSchemaJson(null);}
        copy.setPrivacy(configurationPrivacy("iqc-skill-read-v1",access,canonical,
                List.of("description","instructions","inputSchemaJson","outputSchemaJson")));return copy;
    }
    public io.github.opensabre.iqc.skill.model.IqcSkillVersion skillVersion(
            io.github.opensabre.iqc.skill.model.IqcSkillVersion source,SensitiveReadAccessService.Access access){
        Objects.requireNonNull(access);if(source==null)return null;
        var copy=new io.github.opensabre.iqc.skill.model.IqcSkillVersion();BeanUtils.copyProperties(source,copy);
        boolean canonical=access.fullConversation()&&access.pii()&&access.prompt();
        copy.setName(text(copy.getName(),access.fullConversation()&&access.pii()));
        if(!canonical){copy.setDescription(null);copy.setInstructions(null);copy.setInputSchemaJson(null);copy.setOutputSchemaJson(null);}
        copy.setPrivacy(configurationPrivacy("iqc-skill-read-v1",access,canonical,
                List.of("description","instructions","inputSchemaJson","outputSchemaJson")));return copy;
    }

}
