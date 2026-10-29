package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.result.BatchResultQueryService;
import io.github.opensabre.iqc.result.HierarchicalResultService;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.result.model.InspectionEvidence;
import io.github.opensabre.iqc.result.model.RuleInspectionResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResultReadProjectionTest {
    private final ResultReadProjection projection = new ResultReadProjection(new ObjectMapper());

    @Test
    void keepsEveryMessageAndHitIdentityWhileLimitingPreviewAndLeavingSourceUntouched() {
        Conversation conversation = new Conversation(); conversation.setId("conv-1"); conversation.setCustomerName("Alice");
        var messages = new ArrayList<ConversationMessage>();
        for (int i = 1; i <= 5; i++) {
            var message = new ConversationMessage(); message.setId("msg-" + i); message.setConversationId("conv-1");
            message.setSequenceNo(i); message.setSpeakerRole("CUSTOMER");
            message.setContent(i == 5 ? "Call 13812345678" : "m".repeat(240)); message.setRawLine("raw-" + i);
            messages.add(message);
        }
        var hit = new InspectionResult(); hit.setId("result-last"); hit.setConversationId("conv-1");
        hit.setMessageId("msg-5"); hit.setResultStatus("HIT"); hit.setEvidenceJson("[{\"messageId\":\"msg-5\",\"text\":\"Call 13812345678\",\"start\":5,\"end\":16}]");
        var detail = new BatchResultQueryService.ConversationResultDetail(conversation, null, null, messages, List.of(hit));

        Map<String, Object> projected = projection.conversationDetail(detail, access(false, false, false));
        List<?> returned = (List<?>) projected.get("messages");
        assertEquals(5, returned.size());
        assertEquals("msg-5", ((ConversationMessage) returned.get(4)).getId());
        assertEquals(5, ((ConversationMessage) returned.get(4)).getSequenceNo());
        assertNull(((ConversationMessage) returned.get(4)).getContent());
        assertTrue(((ConversationMessage) returned.get(0)).getContent().length() <= 200);
        assertNull(((ConversationMessage) returned.get(0)).getRawLine());
        assertEquals("HIT", ((InspectionResult) ((List<?>) projected.get("results")).getFirst()).getResultStatus());
        assertTrue(((InspectionResult) ((List<?>) projected.get("results")).getFirst()).getEvidenceJson().contains("msg-5"));
        String projectedEvidence = ((InspectionResult) ((List<?>) projected.get("results")).getFirst()).getEvidenceJson();
        assertFalse(projectedEvidence.contains("13812345678"));
        try {
            var originalEvidence = new ObjectMapper().readTree(hit.getEvidenceJson()).get(0);
            var projectedQuote = new ObjectMapper().readTree(projectedEvidence).get(0);
            assertEquals(originalEvidence.get("text").asText().length(), projectedQuote.get("text").asText().length());
            assertEquals(originalEvidence.get("start").intValue(), projectedQuote.get("start").intValue());
            assertEquals(originalEvidence.get("end").intValue(), projectedQuote.get("end").intValue());
        } catch (Exception exception) { fail(exception); }
        assertEquals("Call 13812345678", messages.getLast().getContent());
        assertEquals("[{\"messageId\":\"msg-5\",\"text\":\"Call 13812345678\",\"start\":5,\"end\":16}]", hit.getEvidenceJson());
    }

    @Test
    void fullGrantReturnsAllMessagesButMasksPiiAndRawDisclosureRequiresBothGrants() {
        Conversation conversation = new Conversation(); conversation.setId("conv-1");
        var message = new ConversationMessage(); message.setId("msg-1"); message.setConversationId("conv-1");
        message.setSequenceNo(1); message.setContent("Call 13812345678");
        var hit = new InspectionResult(); hit.setId("r1"); hit.setMessageId("msg-1"); hit.setEvidence("Call 13812345678");
        var detail = new BatchResultQueryService.ConversationResultDetail(conversation, null, null, List.of(message), List.of(hit));

        var masked = projection.conversationDetail(detail, access(true, false, false));
        assertFalse(((ConversationMessage) ((List<?>) masked.get("messages")).getFirst()).getContent().contains("13812345678"));
        var clear = projection.conversationDetail(detail, access(true, true, false));
        assertEquals("Call 13812345678", ((ConversationMessage) ((List<?>) clear.get("messages")).getFirst()).getContent());
        assertEquals("Call 13812345678", ((InspectionResult) ((List<?>) clear.get("results")).getFirst()).getEvidence());
    }

    @Test
    void piiOnlyGrantRevealsOnlyTheBoundedMessagePreview() {
        Conversation conversation = new Conversation(); conversation.setId("conv-1");
        var messages = new ArrayList<ConversationMessage>();
        for (int i = 1; i <= 5; i++) {
            var message = new ConversationMessage(); message.setId("msg-" + i); message.setConversationId("conv-1");
            message.setSequenceNo(i); message.setContent("Call 13812345678"); messages.add(message);
        }
        var detail = new BatchResultQueryService.ConversationResultDetail(conversation, null, null, messages, List.of());

        Map<String, Object> projected = projection.conversationDetail(detail, access(false, true, false));
        List<?> returned = (List<?>) projected.get("messages");
        assertEquals("Call 13812345678", ((ConversationMessage) returned.getFirst()).getContent());
        assertNull(((ConversationMessage) returned.get(3)).getContent());
        var privacy = (Map<?, ?>) projected.get("privacy");
        assertEquals(3, privacy.get("piiUnmaskedMessageCount"));
        assertEquals(List.of("messages[].content"), privacy.get("piiUnmaskedFields"));
    }

    @Test
    void malformedOpaqueJsonIsWithheldAndPromptSnapshotsStaySeparate() {
        var task = new io.github.opensabre.iqc.task.model.InspectionTask();
        task.setAgentSnapshotJson("{\"prompt\":\"private prompt\"}"); task.setRuleSnapshotJson("{\"x\":1}");
        task.setAutoExpandPrompt("secret"); task.setTriggerRequestJson("{\"body\":\"private\"}");
        var result = new InspectionResult(); result.setId("r1"); result.setFindingJson("not-json");
        var source = new java.util.LinkedHashMap<String, Object>(); source.put("result", result); source.put("task", task);
        source.put("message", null); source.put("execution", null);

        var projected = projection.detail(source, access(false, false, false));
        var returnedResult = (InspectionResult) projected.get("result");
        var returnedTask = (io.github.opensabre.iqc.task.model.InspectionTask) projected.get("task");
        assertEquals("{\"privacyProjection\":\"WITHHELD_INVALID_JSON\"}", returnedResult.getFindingJson());
        assertNull(returnedTask.getAgentSnapshotJson()); assertNull(returnedTask.getRuleSnapshotJson());
        assertNull(returnedTask.getTriggerRequestJson()); assertNull(returnedTask.getAutoExpandPrompt());
        assertEquals("not-json", result.getFindingJson());
    }

    @Test
    void hierarchyMasksTextLeavesButPreservesFindingIdentityOffsetsAndCanonicalRows() {
        var conversation = new ConversationInspectionResult(); conversation.setId("conv-result-1");
        conversation.setResultStatus("HIT"); conversation.setScoringResultJson(
                "{\"messageId\":\"msg-last\",\"text\":\"call 13812345678\",\"score\":9}");
        var rule = new RuleInspectionResult(); rule.setId("rule-result-1"); rule.setResultStatus("HIT");
        rule.setReason("Call 13812345678"); rule.setFindingJson(
                "{\"schemaVersion\":\"iqc-finding-v1\",\"evidence\":[{\"id\":\"ev-1\",\"type\":\"CONVERSATION\",\"reference\":\"msg-last\",\"details\":{\"messageId\":\"msg-last\",\"sequenceNo\":99,\"text\":\"call 13812345678\"}}]}");
        var evidence = new InspectionEvidence(); evidence.setId("ev-row-1"); evidence.setMessageId("msg-last");
        evidence.setSequenceNo(99); evidence.setStartOffset(5); evidence.setEndOffset(16); evidence.setMatchedText("13812345678");
        var source = new HierarchicalResultService.ResultHierarchy(conversation, List.of(rule), Map.of("rule-result-1", List.of(evidence)));

        Map<String, Object> projected = projection.hierarchy(source, access(false, false, false));
        var returnedConversation = (ConversationInspectionResult) projected.get("conversation");
        var returnedEvidence = (InspectionEvidence) ((List<?>) ((Map<?, ?>) projected.get("evidenceByRuleResult")).get("rule-result-1")).getFirst();
        assertTrue(returnedConversation.getScoringResultJson().contains("msg-last"));
        assertFalse(returnedConversation.getScoringResultJson().contains("13812345678"));
        assertEquals(99, returnedEvidence.getSequenceNo()); assertEquals(5, returnedEvidence.getStartOffset());
        assertEquals(16, returnedEvidence.getEndOffset()); assertEquals("msg-last", returnedEvidence.getMessageId());
        assertFalse(returnedEvidence.getMatchedText().contains("13812345678"));
        assertEquals("13812345678", evidence.getMatchedText());
        assertTrue(rule.getFindingJson().contains("13812345678"));
        assertEquals("{\"messageId\":\"msg-last\",\"text\":\"call 13812345678\",\"score\":9}", conversation.getScoringResultJson());
    }

    @Test
    void labelReadProjectionMasksEvidenceAndValuesWithoutChangingJoinKeys() {
        var source = new io.github.opensabre.iqc.label.LabelResultQueryService.LabelResultView("label-result-1", "conv-1",
                "source.txt", "label-1", "customer number", "L-1", "group / customer number", 1, "VALUE-1",
                "{\"value\":\"13812345678\"}", java.math.BigDecimal.ONE, "FINDING", "rule-result-1",
                "[{\"messageId\":\"msg-99\",\"sequenceNo\":99,\"text\":\"call 13812345678\",\"start\":5,\"end\":16}]",
                "HIT", null);

        Map<String, Object> projected = projection.labelResults(List.of(source), access(false, false, false)).getFirst();
        assertEquals("label-result-1", projected.get("id")); assertEquals("conv-1", projected.get("conversationId"));
        assertTrue(((String) projected.get("valueJson")).contains("value"));
        assertFalse(((String) projected.get("valueJson")).contains("13812345678"));
        assertFalse(((String) projected.get("evidenceJson")).contains("13812345678"));
        assertTrue(((String) projected.get("evidenceJson")).contains("msg-99"));
        assertEquals("{\"value\":\"13812345678\"}", source.valueJson());
    }

    @Test
    void reviewBodiesRequireBothGrantsAndCommandsNeverEchoBodies() {
        var source = new io.github.opensabre.iqc.quality.model.ResultReview();
        source.setId("review-1"); source.setSourceHash("immutable-hash");
        source.setRequestComment("private request"); source.setReviewComment("private decision");
        source.setReviewedResultJson("{\"secret\":true}");
        for (boolean full : List.of(false, true)) for (boolean pii : List.of(false, true)) {
            for (boolean detail : List.of(false, true)) {
                for (var returned : List.of(projection.labelReview(source, access(full, pii, true), detail),
                        projection.messageReview(source, access(full, pii, true), detail))) {
                    boolean visible = full && pii && detail;
                    assertEquals(visible ? source.getReviewedResultJson() : null, returned.getReviewedResultJson());
                    assertEquals(visible ? "private request" : null, returned.getRequestComment());
                    assertEquals(visible ? "private decision" : null, returned.getReviewComment());
                    assertEquals(visible, returned.getPrivacy().get("canonicalPayloadVisible"));
                    assertEquals("review-1", returned.getId()); assertEquals("immutable-hash", returned.getSourceHash());
                }
            }
        }
        assertEquals("private request", source.getRequestComment());
        assertEquals("{\"secret\":true}", source.getReviewedResultJson());
        assertNull(source.getPrivacy());
    }

    @Test
    void manualScoreReasonIsWithheldWithoutChangingExactScoreOrStaleState() throws Exception {
        var json = new ObjectMapper();
        var adjustment = new io.github.opensabre.iqc.quality.BusinessItemReviewEvaluator.ScoreAdjustment(
                "r1", "computed-hash", "99.123456789012345678", "private reason");
        for (String status : List.of("APPLIED", "STALE")) {
            var manual = new io.github.opensabre.iqc.quality.BusinessItemReviewEvaluator.ManualScoreProjection(
                    adjustment, status, "computed-hash", new java.math.BigDecimal("80.123456789012345678"));
            var source = new io.github.opensabre.iqc.quality.BusinessItemReviewService.ScoreContext(
                    "r1", "source-hash", "computed-hash", "COMPLETE", "80", manual);
            for (boolean full : List.of(false, true)) for (boolean pii : List.of(false, true)) {
                var projected = projection.scoreReviewContext(source, access(full, pii, false));
                var value = json.valueToTree(projected.manualScore());
                assertEquals(status, value.path("status").asText());
                assertEquals("99.123456789012345678", value.path("adjustment").path("finalScore").asText());
                assertEquals(full && pii ? "private reason" : null,
                        value.path("adjustment").path("reason").isNull() ? null : value.path("adjustment").path("reason").asText());
                assertEquals("computed-hash", projected.computedScoringHash());
            }
            assertEquals("private reason", source.manualScore().adjustment().reason());
        }
    }

    @Test
    void workflowPresentationRejectsMalformedOrNonPriorParentsWhileBodyRemainsHidden() {
        var source = new io.github.opensabre.iqc.quality.model.ResultReview();
        source.setReviewRevision(2); source.setStatus("PENDING");
        source.setReviewedResultJson("{\"schemaVersion\":\"iqc-review-workflow-v1\",\"stage\":\"SECOND_REVIEW\","
                + "\"parentReviewId\":\"parent\",\"parentRevision\":1,\"parentDecisionHash\":\"" + "a".repeat(64) + "\"}");
        var safe = projection.businessReview(source, access(false, false, false), true);
        assertNull(safe.getReviewedResultJson());
        assertEquals("AVAILABLE", safe.getReviewPresentation().get("state"));
        assertEquals("SECOND_REVIEW", safe.getReviewPresentation().get("stage"));
        source.setReviewRevision(1);
        assertEquals("UNAVAILABLE", projection.businessReview(source, access(true, true, true), true).getReviewPresentation().get("state"));
        source.setReviewedResultJson("broken");
        assertEquals("INVALID", projection.businessReview(source, access(false, false, false), true).getReviewPresentation().get("stage"));
        assertNull(projection.businessReview(source, access(true, true, true), false).getReviewPresentation());
    }

    private SensitiveReadAccessService.Access access(boolean full, boolean pii, boolean prompt) {
        return new SensitiveReadAccessService.Access("test", full, pii, prompt, Instant.now());
    }
}
