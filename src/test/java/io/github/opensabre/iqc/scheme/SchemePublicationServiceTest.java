package io.github.opensabre.iqc.scheme;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper;
import io.github.opensabre.iqc.label.model.InspectionLabelResult;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SchemePublicationServiceTest {
    private final InspectionSchemeService schemes = mock(InspectionSchemeService.class);
    private final InspectionTaskService taskService = mock(InspectionTaskService.class);
    private final InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
    private final ConversationInspectionResultMapper results = mock(ConversationInspectionResultMapper.class);
    private final InspectionLabelResultMapper labelResults = mock(InspectionLabelResultMapper.class);
    private final io.github.opensabre.iqc.label.LabelResultQueryService labelQueries = mock(io.github.opensabre.iqc.label.LabelResultQueryService.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final SchemePublicationService service = new SchemePublicationService(schemes, taskService, tasks, results, labelResults, labelQueries, scope, mapper);
    private InspectionTask task;
    private ConversationInspectionResult result;
    private String hash;
    private String publishHash;

    @BeforeEach
    void setup() throws Exception {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "scheme-publication"),
                ConversationInspectionResult.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "scheme-trial"),
                InspectionTask.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "scheme-label-result"),
                InspectionLabelResult.class);
        var release = new InspectionSchemeService.ReleaseSnapshot("方案", "sales", null, "销售", InspectionSchemeServiceTest.definition(),
                new SchemeDependencyResolver.Dependencies(List.of(), null, "RULE_ONLY"));
        hash = InspectionSchemeService.contentHash(mapper.writeValueAsString(release));
        publishHash = InspectionSchemeService.contentHash(mapper.writeValueAsString(release.forTaskSnapshot()));
        when(schemes.preview("s1", 2)).thenReturn(release);
        task = new InspectionTask(); task.setId("trial"); task.setStatus("SUCCEEDED");
        task.setTotalMessages(1); task.setProcessedMessages(1); task.setFailedMessages(0); task.setConversationIdsJson("[\"c1\"]");
        var root = mapper.createObjectNode(); var snapshot = root.putObject("schemeSnapshot");
        snapshot.put("kind", "DRAFT_TRIAL").put("schemeId", "s1").put("draftRevision", 2).put("contentHash", hash);
        snapshot.set("release", mapper.valueToTree(release)); task.setRuleSnapshotJson(root.toString());
        when(tasks.selectById("trial")).thenReturn(task); when(scope.canView(null, null)).thenReturn(true);
        result = new ConversationInspectionResult(); result.setConversationId("c1"); result.setScoreStatus("FINAL");
        result.setBusinessItemResultsJson("[{\"itemCode\":\"greeting\",\"status\":\"FAIL\"}]");
        when(results.selectLatestForTaskConversation("trial", "c1")).thenReturn(result);
    }

    @Test
    void expertConfirmedCompleteTrialCanPublishEvenWhenSampleViolatesBusinessStandard() throws Exception {
        service.publish("s1", 2, "trial", true);
        verify(schemes).publishValidated("s1", 2, "trial", publishHash);
        verifyNoInteractions(labelResults, labelQueries);
    }

    @Test
    void protocolNormalizationCannotDiscardUnexpectedFrozenFields() throws Exception {
        var root = mapper.readTree(task.getRuleSnapshotJson());
        var frozen = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("schemeSnapshot").path("release");
        frozen.put("unexpectedBusinessSetting", "changed");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("schemeSnapshot"))
                .put("contentHash", InspectionSchemeService.contentHash(mapper.writeValueAsString(frozen)));
        task.setRuleSnapshotJson(root.toString());
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("配置已变化");
        verify(schemes, never()).publishValidated(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void publicationUsesExecutionOrderedCanonicalResult() {
        service.publish("s1", 2, "trial", true);
        verify(results).selectLatestForTaskConversation("trial", "c1");
    }

    @Test
    void jointTrialCannotPublishWhenCandidateEvidenceIsNotPersisted() throws Exception {
        jointTrial();
        var value = labelValue("KNOWN", "false");
        when(labelResults.selectList(any())).thenReturn(List.of(value));
        doThrow(io.github.opensabre.iqc.governance.IqcException.invalidState("标签候选引文缺少已持久化证据"))
                .when(labelQueries).requirePersistedCandidateEvidence(any());

        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true))
                .hasMessageContaining("缺少已持久化证据");
        verify(schemes, never()).publishValidated(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void labelOnlyTrialChecksLabelCoverageWithoutInventingAnApplicableScore() throws Exception {
        var selection = jointTrial();
        var policy = new io.github.opensabre.iqc.scoring.InspectionScoring.Policy("iqc-score-v2",
                io.github.opensabre.iqc.scoring.InspectionScoring.Mode.DEDUCTION, 100, 60, List.of());
        var definition = new SchemeDefinition(SchemeDefinition.SCHEMA, List.of(), null, policy, null,
                List.of(new io.github.opensabre.iqc.label.LabelResolutionService.LabelReference("l1", 1)));
        var release = new InspectionSchemeService.ReleaseSnapshot("方案", "sales", null, "销售", definition,
                new SchemeDependencyResolver.Dependencies(List.of(), null, "RULE_ONLY", selection));
        when(schemes.preview("s1", 2)).thenReturn(release);
        hash = InspectionSchemeService.contentHash(mapper.writeValueAsString(release));
        publishHash = InspectionSchemeService.contentHash(mapper.writeValueAsString(release.forTaskSnapshot()));
        var root = mapper.createObjectNode(); var marker = root.putObject("schemeSnapshot");
        marker.put("kind", "DRAFT_TRIAL").put("schemeId", "s1").put("draftRevision", 2).put("contentHash",
                InspectionSchemeService.contentHash(mapper.readTree(mapper.writeValueAsString(release)).toString()));
        marker.set("release", mapper.readTree(mapper.writeValueAsString(release))); task.setRuleSnapshotJson(root.toString());
        result.setScoreStatus("NOT_APPLICABLE"); result.setBusinessItemResultsJson("[]");
        when(labelResults.selectList(any())).thenReturn(List.of(labelValue("UNKNOWN", null)));

        service.publish("s1", 2, "trial", true);
        verify(labelQueries).requirePersistedCandidateEvidence(any());
        verify(schemes).publishValidated("s1", 2, "trial", publishHash);
    }

    @Test
    void trialCreationRetryReturnsTheSameFrozenTaskAndRejectsChangedInput() throws Exception {
        String requestId = "trial-request-1234567890";
        String taskId = "tr-" + InspectionSchemeService.contentHash("null:" + requestId).substring(0, 60);
        String fingerprint = InspectionSchemeService.contentHash(mapper.writeValueAsString(
                new TrialInput("s1", 2, List.of("c1"))));
        var existing = new InspectionTask(); existing.setId(taskId);
        var root = mapper.createObjectNode();
        var snapshot = root.putObject("schemeSnapshot");
        snapshot.put("kind", "DRAFT_TRIAL").put("requestFingerprint", fingerprint);
        existing.setRuleSnapshotJson(root.toString());
        when(tasks.selectById(taskId)).thenReturn(null, existing);
        when(taskService.createSchemeTrial(eq("s1"), eq(2), eq("方案"), eq(List.of("c1")), any(), eq(taskId),
                eq(fingerprint), isNull(), eq(1), isNull()))
                .thenReturn(existing);

        assertThat(service.trial("s1", 2, List.of("c1"), requestId)).isSameAs(existing);
        assertThat(service.trial("s1", 2, List.of("c1"), requestId)).isSameAs(existing);
        verify(taskService, times(1)).createSchemeTrial(eq("s1"), eq(2), eq("方案"), eq(List.of("c1")), any(), eq(taskId),
                eq(fingerprint), isNull(), eq(1), isNull());
        assertThatThrownBy(() -> service.trial("s1", 2, List.of("c2"), requestId))
                .hasMessageContaining("同一试跑请求标识不能用于不同配置");
        assertThatThrownBy(() -> service.trial("s1", 2, List.of("c1"), requestId, null, 3, new java.math.BigDecimal("0.80")))
                .hasMessageContaining("同一试跑请求标识不能用于不同配置");
    }

    @Test
    void racingTrialCreationRecoversTheCommittedTask() throws Exception {
        String requestId = "racing-trial-123456789";
        String taskId = "tr-" + InspectionSchemeService.contentHash("null:" + requestId).substring(0, 60);
        String fingerprint = InspectionSchemeService.contentHash(mapper.writeValueAsString(
                new TrialInput("s1", 2, List.of("c1"))));
        var existing = new InspectionTask(); existing.setId(taskId);
        var root = mapper.createObjectNode();
        root.putObject("schemeSnapshot").put("kind", "DRAFT_TRIAL").put("requestFingerprint", fingerprint);
        existing.setRuleSnapshotJson(root.toString());
        when(tasks.selectById(taskId)).thenReturn(null, existing);
        when(taskService.createSchemeTrial(eq("s1"), eq(2), eq("方案"), eq(List.of("c1")), any(), eq(taskId),
                eq(fingerprint), isNull(), eq(1), isNull()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("race"));

        assertThat(service.trial("s1", 2, List.of("c1"), requestId)).isSameAs(existing);
    }

    private record TrialInput(String schemeId, int revision, List<String> conversationIds) { }

    @Test
    void jointTrialRequiresFrozenLabelScopeAndCompleteValueResults() throws Exception {
        var label = jointTrial();
        var value = labelValue("KNOWN", "false");
        when(labelResults.selectList(any())).thenReturn(List.of(value));
        service.publish("s1", 2, "trial", true);
        verify(schemes).publishValidated("s1", 2, "trial", publishHash);
        verify(labelQueries).requirePersistedCandidateEvidence(List.of(value));

        task.setLabelScopeSnapshotJson(null);
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("标签快照");
        task.setLabelScopeSnapshotJson(mapper.writeValueAsString(label));
        when(labelResults.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("覆盖不完整");
    }

    @Test
    void jointTrialKeepsUnknownAndConflictButRejectsErrorsAndWrongVersions() throws Exception {
        jointTrial();
        when(labelResults.selectList(any())).thenReturn(List.of(labelValue("UNKNOWN", null)));
        service.publish("s1", 2, "trial", true);
        when(labelResults.selectList(any())).thenReturn(List.of(labelValue("CONFLICT", null)));
        service.publish("s1", 2, "trial", true);
        when(labelResults.selectList(any())).thenReturn(List.of(labelValue("ERROR", null)));
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("提取错误");
        var wrongVersion = labelValue("KNOWN", "true"); wrongVersion.setLabelVersionNo(2);
        when(labelResults.selectList(any())).thenReturn(List.of(wrongVersion));
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("不一致");
    }

    @Test
    void jointTrialRejectsInventedKnownValuesAndUnexplainedConflicts() throws Exception {
        jointTrial();
        var invented = labelValue("KNOWN", "false");
        var inventedPayload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(invented.getValueJson());
        inventedPayload.put("value", true);
        invented.setValueJson(inventedPayload.toString());
        when(labelResults.selectList(any())).thenReturn(List.of(invented));
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("有效候选");

        var unexplained = labelValue("CONFLICT", null);
        var conflictPayload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(unexplained.getValueJson());
        conflictPayload.withArray("candidates").remove(1);
        unexplained.setValueJson(conflictPayload.toString());
        when(labelResults.selectList(any())).thenReturn(List.of(unexplained));
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("状态与候选不一致");

        var noQuote = labelValue("KNOWN", "false");
        var noQuotePayload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(noQuote.getValueJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) noQuotePayload.path("candidates").get(0)).putArray("evidence");
        noQuote.setValueJson(noQuotePayload.toString());
        when(labelResults.selectList(any())).thenReturn(List.of(noQuote));
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("值或候选无效");

        var wrongType = labelValue("KNOWN", "false");
        var wrongTypePayload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(wrongType.getValueJson());
        wrongTypePayload.put("valueType", "FIXED");
        wrongType.setValueJson(wrongTypePayload.toString());
        when(labelResults.selectList(any())).thenReturn(List.of(wrongType));
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("值或候选无效");

        var wrongSubject = labelValue("KNOWN", "false");
        var wrongSubjectPayload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(wrongSubject.getValueJson());
        wrongSubjectPayload.put("subjectRole", "agent");
        wrongSubject.setValueJson(wrongSubjectPayload.toString());
        when(labelResults.selectList(any())).thenReturn(List.of(wrongSubject));
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("值或候选无效");

        var coercedQuote = labelValue("KNOWN", "false");
        var coercedPayload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(coercedQuote.getValueJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) coercedPayload.path("candidates").get(0)
                .path("evidence").get(0)).put("text", 123);
        coercedQuote.setValueJson(coercedPayload.toString());
        when(labelResults.selectList(any())).thenReturn(List.of(coercedQuote));
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("值或候选无效");
    }

    @Test
    void jointTrialChecksEverySelectedConversationRatherThanOnlyFirstResult() throws Exception {
        jointTrial();
        task.setConversationIdsJson("[\"c1\",\"c2\"]");
        task.setTotalMessages(2); task.setProcessedMessages(2);
        var second = new ConversationInspectionResult(); second.setId("result-2"); second.setConversationId("c2");
        second.setScoreStatus("FINAL"); second.setBusinessItemResultsJson(result.getBusinessItemResultsJson());
        when(results.selectLatestForTaskConversation("trial", "c2")).thenReturn(second);
        when(labelResults.selectList(any())).thenReturn(List.of(labelValue("KNOWN", "false")));
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("覆盖不完整");
        var secondValue = labelValue("UNKNOWN", null); secondValue.setConversationResultId("result-2");
        when(labelResults.selectList(any())).thenReturn(List.of(labelValue("KNOWN", "false"), secondValue));
        service.publish("s1", 2, "trial", true);
        verify(schemes).publishValidated("s1", 2, "trial", publishHash);
    }

    private io.github.opensabre.iqc.label.LabelResolutionService.ResolvedSelection jointTrial() throws Exception {
        var base = InspectionSchemeServiceTest.definition();
        var definition = new SchemeDefinition(base.schemaVersion(), base.items(), base.agent(), base.scoring(), base.runLimits(),
                List.of(new io.github.opensabre.iqc.label.LabelResolutionService.LabelReference("l1", 1)));
        var binding = new io.github.opensabre.iqc.label.model.LabelRuleBinding();
        binding.setRuleId("rule"); binding.setRuleVersionNo(1);
        var value = new io.github.opensabre.iqc.label.model.LabelValueDefinition();
        value.setValueCode("interested"); value.setValueType("BOOLEAN");
        var label = new io.github.opensabre.iqc.label.LabelResolutionService.LabelSnapshot("l1", 1, "意向", "intent",
                null, null, null, null, null, false, "customer", new java.math.BigDecimal("1.00"), List.of(binding), List.of(value));
        var selection = new io.github.opensabre.iqc.label.LabelResolutionService.ResolvedSelection("2.0", List.of(label), List.of("rule"));
        var release = new InspectionSchemeService.ReleaseSnapshot("方案", "sales", null, "销售", definition,
                new SchemeDependencyResolver.Dependencies(List.of(), null, "RULE_ONLY", selection));
        when(schemes.preview("s1", 2)).thenReturn(release);
        hash = InspectionSchemeService.contentHash(mapper.writeValueAsString(release));
        publishHash = InspectionSchemeService.contentHash(mapper.writeValueAsString(release.forTaskSnapshot()));
        var root = mapper.createObjectNode(); var snapshot = root.putObject("schemeSnapshot");
        snapshot.put("kind", "DRAFT_TRIAL").put("schemeId", "s1").put("draftRevision", 2).put("contentHash",
                InspectionSchemeService.contentHash(mapper.readTree(mapper.writeValueAsString(release)).toString()));
        snapshot.set("release", mapper.readTree(mapper.writeValueAsString(release))); task.setRuleSnapshotJson(root.toString());
        task.setLabelScopeSnapshotJson(mapper.writeValueAsString(selection));
        result.setId("result-1");
        return selection;
    }

    private InspectionLabelResult labelValue(String status, String jsonValue) {
        var value = new InspectionLabelResult();
        value.setConversationResultId("result-1"); value.setLabelId("l1"); value.setLabelVersionNo(1);
        value.setValueCode("interested");
        var payload = mapper.createObjectNode().put("schemaVersion", "iqc-label-result-v2")
                .put("valueCode", "interested").put("valueType", "BOOLEAN")
                .put("subjectRole", "customer").put("status", status);
        var candidates = payload.putArray("candidates");
        if ("KNOWN".equals(status)) {
            boolean known = Boolean.parseBoolean(jsonValue);
            payload.put("value", known);
            candidate(candidates, known);
        } else if ("CONFLICT".equals(status)) {
            candidate(candidates, true);
            candidate(candidates, false);
        }
        value.setValueJson(payload.toString());
        return value;
    }

    private void candidate(com.fasterxml.jackson.databind.node.ArrayNode candidates, boolean value) {
        candidates.addObject().put("sourceRuleResultId", "rr1").put("value", value)
                .putArray("evidence").addObject().put("messageId", "m1").put("text", "有意向");
    }

    @Test
    void publicationRequiresExplicitReviewAcknowledgement() {
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", false)).hasMessageContaining("确认");
        verifyNoInteractions(tasks, results);
    }

    @Test
    void oldDraftTrialCannotPublishNewRevision() {
        task.setRuleSnapshotJson(task.getRuleSnapshotJson().replace("\"draftRevision\":2", "\"draftRevision\":1"));
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("重新试跑");
        verify(schemes, never()).publishValidated(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void modifiedReleaseContentCannotBorrowMatchingMetadataHash() {
        task.setRuleSnapshotJson(task.getRuleSnapshotJson().replace("开场白", "被修改的要求"));
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("重新试跑");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ERROR", "REVIEW_REQUIRED", "NOT_EVALUATED", "NOT_APPLICABLE"})
    void unfinishedOrEntirelyInapplicableTrialCannotPublish(String status) {
        result.setBusinessItemResultsJson("[{\"itemCode\":\"greeting\",\"status\":\"" + status + "\"}]");
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true))
                .hasMessageContaining("NOT_APPLICABLE".equals(status) ? "没有适用" : "未评估、错误或待复核");
        verify(schemes, never()).publishValidated(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void missingConversationResultAndRunningTaskCannotPublish() {
        when(results.selectLatestForTaskConversation("trial", "c1")).thenReturn(null);
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("完整会话");
        task.setStatus("RUNNING");
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("完整成功");
    }

    @Test
    void inaccessibleTrialCannotBeUsedAsPublicationEvidence() {
        when(scope.canView(null, null)).thenReturn(false);
        assertThatThrownBy(() -> service.publish("s1", 2, "trial", true)).hasMessageContaining("无权");
        verifyNoInteractions(results);
    }

    @Test
    void recentTrialsAreBoundedAndScopedBeforeReturningRecords() {
        when(scope.owner()).thenReturn("owner"); when(scope.groupId()).thenReturn("team");
        when(tasks.selectList(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.Wrapper<InspectionTask> query = invocation.getArgument(0);
            assertThat(query.getSqlSegment()).contains("JSON_EXTRACT", "schemeSnapshot.kind", "schemeSnapshot.schemeId", "created_by", "owner_group_id", "LIMIT 20");
            return List.of(task);
        });
        assertThat(service.trials("s1")).containsExactly(task);
        verify(schemes).get("s1");
    }
}
