package io.github.opensabre.iqc.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import io.github.opensabre.iqc.label.model.InspectionLabelResult;
import io.github.opensabre.iqc.result.model.RuleInspectionResult;
import io.github.opensabre.iqc.result.model.InspectionEvidence;
import io.github.opensabre.iqc.task.model.InspectionTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabelResultQueryServiceTest {
    private final io.github.opensabre.iqc.result.dao.InspectionEvidenceMapper evidenceMapper = mock(io.github.opensabre.iqc.result.dao.InspectionEvidenceMapper.class);
    private final io.github.opensabre.iqc.result.dao.RuleInspectionResultMapper ruleMapper = mock(io.github.opensabre.iqc.result.dao.RuleInspectionResultMapper.class);
    private final LabelResultReviewService labelReviews = mock(LabelResultReviewService.class);
    private final LabelResultQueryService service = new LabelResultQueryService(
            mock(io.github.opensabre.iqc.task.dao.InspectionTaskMapper.class),
            mock(io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper.class),
            mock(io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper.class),
            mock(io.github.opensabre.iqc.label.dao.InsightLabelMapper.class),
            mock(io.github.opensabre.iqc.label.dao.LabelGroupMapper.class),
            mock(io.github.opensabre.iqc.label.dao.LabelCategoryMapper.class),
            mock(io.github.opensabre.iqc.conversation.dao.ConversationMapper.class), evidenceMapper,
            new ObjectMapper(), mock(io.github.opensabre.iqc.shared.IqcDataScope.class), ruleMapper, labelReviews);

    @org.junit.jupiter.api.BeforeEach
    void initializeLambdaMetadata() {
        when(labelReviews.effectiveForTask(any(), any(), any())).thenReturn(java.util.Map.of());
        for (var type : List.of(RuleInspectionResult.class, InspectionEvidence.class, InspectionTask.class,
                ConversationInspectionResult.class, InspectionLabelResult.class))
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), type.getName()), type);
    }

    @Test void collectsEveryCandidateSourceWithoutUsingLegacyAnchor() {
        when(ruleMapper.selectList(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?> query = invocation.getArgument(0);
            assertThat(query.getSqlSegment()).contains("conversation_result_id", "id IN");
            assertThat(query.getParamNameValuePairs().values()).contains("cr1", "rr1", "rr2").doesNotContain("anchor");
            return List.of(source("rr1", "cr1"), source("rr2", "cr1"));
        });
        var first = quote("rr1");
        var second = quote("rr2");
        when(evidenceMapper.selectList(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?> query = invocation.getArgument(0);
            query.getSqlSegment();
            assertThat(query.getParamNameValuePairs().values()).containsExactlyInAnyOrder("rr1", "rr2");
            return List.of(first, second);
        });
        String evidence = ReflectionTestUtils.invokeMethod(service, "evidenceJson", projection("rr1", "rr2", "rr1"));
        assertThat(evidence).contains("m-rr1", "m-rr2", "rr1", "rr2");
    }

    @Test void v2EvidenceExcludesOtherLabelsSharingTheSameRule() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(source("rr1", "cr1")));
        var own = quote("rr1");
        var unrelated = new InspectionEvidence(); unrelated.setRuleResultId("rr1");
        unrelated.setMessageId("m-other"); unrelated.setMatchedText("其他标签引文");
        when(evidenceMapper.selectList(any())).thenReturn(List.of(own, unrelated));

        String evidence = ReflectionTestUtils.invokeMethod(service, "evidenceJson", projection("rr1"));

        assertThat(evidence).contains("quote-rr1", "m-rr1").doesNotContain("其他标签引文", "m-other");
    }

    @Test void v2CandidateWithoutPersistedQuoteFailsInsteadOfShowingUnrelatedEvidence() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(source("rr1", "cr1")));
        var unrelated = new InspectionEvidence(); unrelated.setRuleResultId("rr1");
        unrelated.setMessageId("m-other"); unrelated.setMatchedText("其他标签引文");
        when(evidenceMapper.selectList(any())).thenReturn(List.of(unrelated));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "evidenceJson", projection("rr1")))
                .hasMessageContaining("缺少已持久化证据");
    }

    @Test void publicationEvidenceAuditBatchesSharedSourcesAndChecksEveryQuote() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(source("rr1", "cr1"), source("rr2", "cr1")));
        when(evidenceMapper.selectList(any())).thenReturn(List.of(quote("rr1"), quote("rr2")));

        service.requirePersistedCandidateEvidence(List.of(projection("rr1"), projection("rr1", "rr2")));

        verify(ruleMapper).selectList(any());
        verify(evidenceMapper).selectList(any());
    }

    @Test void publicationEvidenceAuditRejectsForeignSourceAndMissingQuote() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(source("rr1", "foreign")));
        assertThatThrownBy(() -> service.requirePersistedCandidateEvidence(List.of(projection("rr1"))))
                .hasMessageContaining("不属于当前会话结果");
        verifyNoInteractions(evidenceMapper);

        reset(ruleMapper);
        when(ruleMapper.selectList(any())).thenReturn(List.of(source("rr1", "cr1")));
        when(evidenceMapper.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.requirePersistedCandidateEvidence(List.of(projection("rr1"))))
                .hasMessageContaining("缺少已持久化证据");
    }

    @Test void publicationEvidenceAuditDoesNotDisguiseDatabaseFailureAsBadCandidate() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(source("rr1", "cr1")));
        when(evidenceMapper.selectList(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.requirePersistedCandidateEvidence(List.of(projection("rr1"))))
                .isInstanceOf(IllegalStateException.class).hasMessage("database unavailable");
    }

    @Test void rejectsCrossConversationCandidateBeforeReadingEvidence() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(source("rr1", "foreign")));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "evidenceJson", projection("rr1")))
                .hasMessageContaining("不属于当前会话结果");
        verifyNoInteractions(evidenceMapper);
    }

    @Test void emptyCandidatesDoNotReadAnchorEvidence() {
        String evidence = ReflectionTestUtils.invokeMethod(service, "evidenceJson", projection());
        assertThat(evidence).isEqualTo("[]"); verifyNoInteractions(ruleMapper, evidenceMapper);
    }

    @Test void v2EvidenceRejectsCoercedCandidateFieldsBeforeDatabaseLookup() throws Exception {
        var value = projection("rr1");
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(value.getValueJson());
        var candidate = (com.fasterxml.jackson.databind.node.ObjectNode) payload.path("candidates").get(0);
        candidate.put("sourceRuleResultId", 123); value.setValueJson(payload.toString());
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "evidenceJson", value))
                .hasMessageContaining("候选来源无效");

        candidate.put("sourceRuleResultId", "rr1");
        ((com.fasterxml.jackson.databind.node.ObjectNode) candidate.path("evidence").get(0)).put("text", 123);
        value.setValueJson(payload.toString());
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "evidenceJson", value))
                .hasMessageContaining("候选引文无效");
        verifyNoInteractions(ruleMapper, evidenceMapper);
    }

    @Test void coverageStatesAreNotCountedAsLegacyHits() {
        for (String state : List.of("KNOWN", "UNKNOWN", "CONFLICT", "ERROR")) {
            String actual = ReflectionTestUtils.invokeMethod(service, "resultStatus", coveragePayload(state));
            assertThat(actual).isEqualTo(state);
            Boolean detected = ReflectionTestUtils.invokeMethod(service, "detected", actual);
            assertThat(detected).isEqualTo("KNOWN".equals(state));
        }
        String legacy = ReflectionTestUtils.invokeMethod(service, "resultStatus", "{\"value\":false}");
        assertThat(legacy).isEqualTo("HIT");
    }

    @Test void invalidCoverageStatusFailsInsteadOfCountingAsAHit() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "resultStatus",
                "{\"schemaVersion\":\"iqc-label-result-v2\",\"status\":\"HIT\"}"))
                .hasMessageContaining("状态无效");
    }

    @Test void databaseFailureIsNotDisguisedAsEmptyEvidence() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(source("rr1", "cr1")));
        when(evidenceMapper.selectList(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "evidenceJson", projection("rr1")))
                .hasMessageContaining("database unavailable");
    }

    @Test void legacyProjectionStillUsesItsSingleSource() {
        var result = projection(); result.setValueJson(null);
        when(evidenceMapper.selectList(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<?> query = invocation.getArgument(0);
            query.getSqlSegment(); assertThat(query.getParamNameValuePairs().values()).containsExactly("anchor");
            return List.of();
        });
        String evidence = ReflectionTestUtils.invokeMethod(service, "evidenceJson", result);
        assertThat(evidence).isEqualTo("[]"); verifyNoInteractions(ruleMapper);
    }

    @Test
    void jointTaskCannotFallBackToCurrentDirectoryWhenFrozenScopeIsMissingOrBroken() {
        var task = jointTask(); task.setLabelScopeSnapshotJson(null);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "snapshotLabels", task))
                .hasMessageContaining("缺少冻结标签快照");
        task.setLabelScopeSnapshotJson("{");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "snapshotLabels", task))
                .hasMessageContaining("快照损坏");
        task.setLabelScopeSnapshotJson("""
                {"schemaVersion":"2.0","labels":[{"id":"house","versionNo":2,"name":"篡改名称"}]}
                """);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "snapshotLabels", task))
                .hasMessageContaining("冻结依赖不一致");
    }

    @Test
    void legacyMalformedLabelScopeRetainsTolerantReadBehavior() {
        var task = new InspectionTask(); task.setRuleSnapshotJson("[]"); task.setLabelScopeSnapshotJson("{");
        Object catalog = ReflectionTestUtils.invokeMethod(service, "snapshotLabels", task);
        assertThat(catalog).isNotNull();
        task.setLabelScopeSnapshotJson("[]");
        Object arrayCatalog = ReflectionTestUtils.invokeMethod(service, "snapshotLabels", task);
        assertThat(arrayCatalog).isNotNull();
    }

    @Test
    void jointResultUsesFrozenNameAndRejectsDuplicateRowsInsteadOfHidingThem() {
        var taskMapper = (io.github.opensabre.iqc.task.dao.InspectionTaskMapper) ReflectionTestUtils.getField(service, "taskMapper");
        var conversationMapper = (io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper)
                ReflectionTestUtils.getField(service, "conversationResultMapper");
        var labelResultMapper = (io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper)
                ReflectionTestUtils.getField(service, "labelResultMapper");
        var scope = (io.github.opensabre.iqc.shared.IqcDataScope) ReflectionTestUtils.getField(service, "dataScope");
        when(taskMapper.selectById("trial")).thenReturn(jointTask());
        when(scope.canView(null, null)).thenReturn(true);
        var conversation = new ConversationInspectionResult(); conversation.setId("cr1"); conversation.setConversationId("c1");
        when(conversationMapper.selectLatestForTask("trial")).thenReturn(List.of(conversation));
        var first = jointResult("lr1"); var duplicate = jointResult("lr2");
        when(labelResultMapper.selectList(any())).thenReturn(List.of(first));

        assertThat(service.listByTask("trial")).singleElement().satisfies(value ->
                assertThat(value.labelName()).isEqualTo("冻结名称"));
        verifyNoInteractions(ReflectionTestUtils.getField(service, "labelMapper"),
                ReflectionTestUtils.getField(service, "groupMapper"),
                ReflectionTestUtils.getField(service, "categoryMapper"));

        for (String payload : new String[]{null, "{}", "{"}) {
            first.setValueJson(payload);
            assertThatThrownBy(() -> service.listByTask("trial"))
                    .hasMessageContaining("联合任务标签结果");
        }
        first.setValueJson(jointResult("lr1").getValueJson());

        var wrongVersion = jointResult("lr3"); wrongVersion.setLabelVersionNo(3);
        when(labelResultMapper.selectList(any())).thenReturn(List.of(wrongVersion));
        assertThatThrownBy(() -> service.listByTask("trial")).hasMessageContaining("不属于任务冻结标签快照");

        when(labelResultMapper.selectList(any())).thenReturn(List.of(first, duplicate));
        assertThatThrownBy(() -> service.listByTask("trial")).hasMessageContaining("标签结果重复");
    }

    @Test
    void singleTaskSummaryUsesFrozenJointGroupInsteadOfCurrentDirectory() {
        var taskMapper = (io.github.opensabre.iqc.task.dao.InspectionTaskMapper) ReflectionTestUtils.getField(service, "taskMapper");
        var conversationMapper = (io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper)
                ReflectionTestUtils.getField(service, "conversationResultMapper");
        var labelResultMapper = (io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper)
                ReflectionTestUtils.getField(service, "labelResultMapper");
        var labelMapper = (io.github.opensabre.iqc.label.dao.InsightLabelMapper) ReflectionTestUtils.getField(service, "labelMapper");
        var groupMapper = (io.github.opensabre.iqc.label.dao.LabelGroupMapper) ReflectionTestUtils.getField(service, "groupMapper");
        var scope = (io.github.opensabre.iqc.shared.IqcDataScope) ReflectionTestUtils.getField(service, "dataScope");
        when(taskMapper.selectById("trial")).thenReturn(jointTask());
        when(scope.canView(null, null)).thenReturn(true);
        var conversation = new ConversationInspectionResult(); conversation.setId("cr1"); conversation.setConversationId("c1");
        when(conversationMapper.selectLatestForTask("trial")).thenReturn(List.of(conversation));
        var result = jointResult("lr1");
        result.setValueJson("""
                {"schemaVersion":"iqc-label-result-v2","valueCode":"owns","valueType":"BOOLEAN",
                "subjectRole":"customer","status":"KNOWN","value":false,
                "candidates":[{"sourceRuleResultId":"rr1","value":false,
                "evidence":[{"messageId":"m-rr1","text":"quote-rr1"}]}]}
                """);
        when(labelResultMapper.selectList(any())).thenReturn(List.of(result));
        when(ruleMapper.selectList(any())).thenReturn(List.of(source("rr1", "cr1")));
        when(evidenceMapper.selectList(any())).thenReturn(List.of(quote("rr1")));
        var currentLabel = new io.github.opensabre.iqc.label.model.InsightLabel();
        currentLabel.setId("house"); currentLabel.setName("当前名称"); currentLabel.setGroupId("current");
        var currentGroup = new io.github.opensabre.iqc.label.model.LabelGroup();
        currentGroup.setId("current"); currentGroup.setName("当前分组");
        when(labelMapper.selectBatchIds(any())).thenReturn(List.of(currentLabel));
        when(groupMapper.selectBatchIds(any())).thenReturn(List.of(currentGroup));

        var summary = service.summary("trial");
        assertThat(summary.labelDistribution()).containsEntry("冻结名称", 1L).doesNotContainKey("当前名称");
        assertThat(summary.groupDistribution()).containsEntry("冻结分组", 1L).doesNotContainKey("当前分组");
        assertThat(summary.coverageValueCount()).isEqualTo(1);
        assertThat(summary.coverageStatusCounts()).containsEntry("KNOWN", 1L);
        result.setValueJson(coveragePayload("UNKNOWN"));
        var unknownSummary = service.summary("trial");
        assertThat(unknownSummary.coverageStatusCounts()).containsEntry("UNKNOWN", 1L);
        assertThat(unknownSummary.detectedConversationCount()).isZero();
        assertThat(unknownSummary.reviewed().detectedConversationCount()).isZero();

        var human = new LabelResultReviewService.ReviewOverlay(2, "PENDING", "review-1", 1,
                "KNOWN", new ObjectMapper().getNodeFactory().booleanNode(false), List.of("m-rr1"));
        when(labelReviews.effectiveForTask(any(), any(), any())).thenReturn(java.util.Map.of("lr1", human));
        var corrected = service.summary("trial");
        assertThat(corrected.detectedConversationCount()).isZero();
        assertThat(corrected.coverageStatusCounts()).containsEntry("UNKNOWN", 1L);
        assertThat(corrected.reviewed().detectedConversationCount()).isEqualTo(1);
        assertThat(corrected.reviewed().coverageStatusCounts()).containsEntry("KNOWN", 1L);
        assertThat(corrected.reviewed().correctedValueCount()).isEqualTo(1);
        assertThat(corrected.reviewed().pendingReviewCount()).isEqualTo(1);
        assertThat(service.listByTask("trial")).singleElement().satisfies(value -> {
            assertThat(value.status()).isEqualTo("UNKNOWN");
            assertThat(value.reviewOverlay()).isEqualTo(human);
        });
    }

    @Test
    void dashboardSummaryUsesFrozenJointNamesAndRejectsInvalidCoverageRows() {
        var taskMapper = (io.github.opensabre.iqc.task.dao.InspectionTaskMapper) ReflectionTestUtils.getField(service, "taskMapper");
        var conversationMapper = (io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper)
                ReflectionTestUtils.getField(service, "conversationResultMapper");
        var labelResultMapper = (io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper)
                ReflectionTestUtils.getField(service, "labelResultMapper");
        when(taskMapper.selectBatchIds(List.of("trial"))).thenReturn(List.of(jointTask()));
        var conversation = new ConversationInspectionResult(); conversation.setId("cr1");
        conversation.setTaskId("trial"); conversation.setConversationId("c1");
        when(conversationMapper.selectLatestInWindow(List.of("trial"), null, null)).thenReturn(List.of(conversation));
        var known = jointResult("lr1");
        known.setValueJson(coveragePayload("KNOWN"));
        when(labelResultMapper.selectList(any())).thenReturn(List.of(known));

        var summary = service.summaryByTasks(List.of("trial"), null, null);
        assertThat(summary.labelDistribution()).containsEntry("冻结名称", 1L);
        assertThat(summary.groupDistribution()).containsEntry("冻结分组", 1L);
        assertThat(summary.coverageValueCount()).isEqualTo(1);
        assertThat(summary.coverageStatusCounts()).containsEntry("KNOWN", 1L);
        verifyNoInteractions(ReflectionTestUtils.getField(service, "labelMapper"),
                ReflectionTestUtils.getField(service, "groupMapper"));

        for (String status : List.of("UNKNOWN", "CONFLICT", "ERROR")) {
            known.setValueJson(coveragePayload(status));
            var stateSummary = service.summaryByTasks(List.of("trial"), null, null);
            assertThat(stateSummary.coverageValueCount()).isEqualTo(1);
            assertThat(stateSummary.coverageStatusCounts()).containsEntry(status, 1L);
            assertThat(stateSummary.detectedConversationCount()).isZero();
        }
        known.setValueJson(coveragePayload("KNOWN"));

        for (String invalid : List.of(
                "{\"schemaVersion\":\"iqc-label-result-v2\",\"status\":\"KNOWN\",\"candidates\":[]}",
                coveragePayload("KNOWN").replace("\"value\":false,\"candidates\"", "\"value\":true,\"candidates\""),
                "{\"schemaVersion\":\"iqc-label-result-v2\",\"status\":\"CONFLICT\",\"candidates\":[]}",
                coveragePayload("KNOWN").replace("\"valueType\":\"BOOLEAN\"", "\"valueType\":\"MONTH\""),
                coveragePayload("KNOWN").replace("false", "3"),
                coveragePayload("KNOWN").replace("\"subjectRole\":\"customer\"", "\"subjectRole\":\"agent\""),
                coveragePayload("KNOWN").replace("\"valueCode\":\"owns\"", "\"valueCode\":\"other\""))) {
            known.setValueJson(invalid);
            assertThatThrownBy(() -> service.summaryByTasks(List.of("trial"), null, null)).hasMessageContaining("标签结果");
        }
        known.setValueJson(coveragePayload("KNOWN"));

        for (String payload : new String[]{null, "{}", "{"}) {
            known.setValueJson(payload);
            assertThatThrownBy(() -> service.summaryByTasks(List.of("trial"), null, null))
                    .hasMessageContaining("联合任务标签结果");
        }
        known.setValueJson(coveragePayload("KNOWN"));

        var noFrozenGroup = jointTask();
        noFrozenGroup.setLabelScopeSnapshotJson(noFrozenGroup.getLabelScopeSnapshotJson().replace("\"groupName\":\"冻结分组\",", ""));
        noFrozenGroup.setRuleSnapshotJson(noFrozenGroup.getRuleSnapshotJson().replace("\"groupName\":\"冻结分组\",", ""));
        when(taskMapper.selectBatchIds(List.of("trial"))).thenReturn(List.of(noFrozenGroup));
        var currentLabel = new io.github.opensabre.iqc.label.model.InsightLabel();
        currentLabel.setId("house"); currentLabel.setGroupId("current");
        var currentGroup = new io.github.opensabre.iqc.label.model.LabelGroup();
        currentGroup.setId("current"); currentGroup.setName("当前分组");
        var labelMapper = (io.github.opensabre.iqc.label.dao.InsightLabelMapper) ReflectionTestUtils.getField(service, "labelMapper");
        var groupMapper = (io.github.opensabre.iqc.label.dao.LabelGroupMapper) ReflectionTestUtils.getField(service, "groupMapper");
        when(labelMapper.selectBatchIds(any())).thenReturn(List.of(currentLabel));
        when(groupMapper.selectBatchIds(any())).thenReturn(List.of(currentGroup));
        assertThat(service.summaryByTasks(List.of("trial"), null, null).groupDistribution())
                .containsEntry("未知标签组", 1L).doesNotContainKey("当前分组");
        when(taskMapper.selectBatchIds(List.of("trial"))).thenReturn(List.of(jointTask()));

        var wrongVersion = jointResult("lr2"); wrongVersion.setLabelVersionNo(3);
        when(labelResultMapper.selectList(any())).thenReturn(List.of(wrongVersion));
        assertThatThrownBy(() -> service.summaryByTasks(List.of("trial"), null, null))
                .hasMessageContaining("不属于任务冻结标签快照");

        when(labelResultMapper.selectList(any())).thenReturn(List.of(jointResult("lr2"), jointResult("lr3")));
        assertThatThrownBy(() -> service.summaryByTasks(List.of("trial"), null, null))
                .hasMessageContaining("标签结果重复");

        when(taskMapper.selectBatchIds(List.of("trial"))).thenReturn(List.of());
        assertThatThrownBy(() -> service.summaryByTasks(List.of("trial"), null, null))
                .hasMessageContaining("任务不存在");
    }

    @Test
    void legacyAndMixedDashboardSummaryUseCurrentDirectoryOnlyForLegacyLabels() {
        var taskMapper = (io.github.opensabre.iqc.task.dao.InspectionTaskMapper) ReflectionTestUtils.getField(service, "taskMapper");
        var conversationMapper = (io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper)
                ReflectionTestUtils.getField(service, "conversationResultMapper");
        var labelResultMapper = (io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper)
                ReflectionTestUtils.getField(service, "labelResultMapper");
        var labelMapper = (io.github.opensabre.iqc.label.dao.InsightLabelMapper) ReflectionTestUtils.getField(service, "labelMapper");
        var groupMapper = (io.github.opensabre.iqc.label.dao.LabelGroupMapper) ReflectionTestUtils.getField(service, "groupMapper");
        var task = new InspectionTask(); task.setId("legacy");
        when(taskMapper.selectBatchIds(List.of("legacy"))).thenReturn(List.of(task));
        var conversation = new ConversationInspectionResult(); conversation.setId("cr1");
        conversation.setTaskId("legacy"); conversation.setConversationId("c1");
        when(conversationMapper.selectLatestInWindow(List.of("legacy"), null, null)).thenReturn(List.of(conversation));
        var hit = jointResult("lr1"); hit.setValueJson(null);
        when(labelResultMapper.selectList(any())).thenReturn(List.of(hit));
        var label = new io.github.opensabre.iqc.label.model.InsightLabel();
        label.setId("house"); label.setName("当前标签"); label.setGroupId("current");
        var group = new io.github.opensabre.iqc.label.model.LabelGroup();
        group.setId("current"); group.setName("当前分组");
        when(labelMapper.selectBatchIds(any())).thenReturn(List.of(label));
        when(groupMapper.selectBatchIds(any())).thenReturn(List.of(group));

        var summary = service.summaryByTasks(List.of("legacy"), null, null);
        assertThat(summary.labelDistribution()).containsEntry("当前标签", 1L);
        assertThat(summary.groupDistribution()).containsEntry("当前分组", 1L);
        assertThat(summary.coverageValueCount()).isZero();
        assertThat(summary.coverageStatusCounts()).isEmpty();
        var joint = jointTask();
        joint.setLabelScopeSnapshotJson(joint.getLabelScopeSnapshotJson().replace("house", "frozen-only"));
        joint.setRuleSnapshotJson(joint.getRuleSnapshotJson().replace("house", "frozen-only"));
        when(taskMapper.selectBatchIds(List.of("legacy", "trial"))).thenReturn(List.of(task, joint));
        var jointConversation = new ConversationInspectionResult(); jointConversation.setId("cr2");
        jointConversation.setTaskId("trial"); jointConversation.setConversationId("c2");
        when(conversationMapper.selectLatestInWindow(List.of("legacy", "trial"), null, null))
                .thenReturn(List.of(conversation, jointConversation));
        var jointValue = jointResult("joint"); jointValue.setConversationResultId("cr2"); jointValue.setLabelId("frozen-only");
        jointValue.setValueJson(coveragePayload("KNOWN"));
        when(labelResultMapper.selectList(any())).thenReturn(List.of(hit, jointValue));
        var mixed = service.summaryByTasks(List.of("legacy", "trial"), null, null);
        assertThat(mixed.labelDistribution()).containsEntry("当前标签", 1L).containsEntry("冻结名称", 1L);
        assertThat(mixed.groupDistribution()).containsEntry("当前分组", 1L).containsEntry("冻结分组", 1L);
        assertThat(mixed.conversationCount()).isEqualTo(2);
        assertThat(mixed.detectedConversationCount()).isEqualTo(2);
        assertThat(mixed.coverageValueCount()).isEqualTo(1);
        verify(labelMapper, times(2)).selectBatchIds(List.of("house"));
        verify(labelMapper, never()).selectBatchIds(argThat(ids -> ids.contains("frozen-only")));
    }

    @Test
    void dashboardCountsTheSameConversationInSeparateTasksAsSeparateEvaluations() {
        var taskMapper = (io.github.opensabre.iqc.task.dao.InspectionTaskMapper) ReflectionTestUtils.getField(service, "taskMapper");
        var conversationMapper = (io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper)
                ReflectionTestUtils.getField(service, "conversationResultMapper");
        var labelResultMapper = (io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper)
                ReflectionTestUtils.getField(service, "labelResultMapper");
        var labelMapper = (io.github.opensabre.iqc.label.dao.InsightLabelMapper) ReflectionTestUtils.getField(service, "labelMapper");
        var groupMapper = (io.github.opensabre.iqc.label.dao.LabelGroupMapper) ReflectionTestUtils.getField(service, "groupMapper");
        var firstTask = new InspectionTask(); firstTask.setId("task-1");
        var secondTask = new InspectionTask(); secondTask.setId("task-2");
        when(taskMapper.selectBatchIds(List.of("task-1", "task-2"))).thenReturn(List.of(firstTask, secondTask));
        var first = new ConversationInspectionResult(); first.setId("cr-1"); first.setTaskId("task-1"); first.setConversationId("shared");
        var second = new ConversationInspectionResult(); second.setId("cr-2"); second.setTaskId("task-2"); second.setConversationId("shared");
        when(conversationMapper.selectLatestInWindow(List.of("task-1", "task-2"), null, null)).thenReturn(List.of(first, second));
        var known = jointResult("known"); known.setConversationResultId("cr-1");
        known.setValueJson(coveragePayload("KNOWN"));
        var unknown = jointResult("unknown"); unknown.setConversationResultId("cr-2");
        when(labelResultMapper.selectList(any())).thenReturn(List.of(known, unknown));
        var label = new io.github.opensabre.iqc.label.model.InsightLabel();
        label.setId("house"); label.setName("有房"); label.setGroupId("property");
        var group = new io.github.opensabre.iqc.label.model.LabelGroup();
        group.setId("property"); group.setName("资产");
        when(labelMapper.selectBatchIds(any())).thenReturn(List.of(label));
        when(groupMapper.selectBatchIds(any())).thenReturn(List.of(group));

        var summary = service.summaryByTasks(List.of("task-1", "task-2"), null, null);
        assertThat(summary.conversationCount()).isEqualTo(2);
        assertThat(summary.detectedConversationCount()).isEqualTo(1);
        assertThat(summary.detectionRate()).isEqualByComparingTo("0.5000");
        assertThat(summary.coverageValueCount()).isEqualTo(2);
        assertThat(summary.coverageStatusCounts()).containsEntry("KNOWN", 1L).containsEntry("UNKNOWN", 1L);
    }

    private InspectionTask jointTask() {
        var task = new InspectionTask(); task.setId("trial");
        String scope = """
                {"schemaVersion":"2.0","labels":[{"id":"house","versionNo":2,"name":"冻结名称",
                "groupName":"冻结分组","targetRole":"customer","values":[{"valueCode":"owns","valueType":"BOOLEAN"}]}]}
                """;
        task.setRuleSnapshotJson("""
                {"schemeSnapshot":{"release":{"definition":{"labels":[{"id":"house","versionNo":2}]},
                "dependencies":{"labels":
                """ + scope + "}}}}" );
        task.setLabelScopeSnapshotJson(scope);
        return task;
    }

    private InspectionLabelResult jointResult(String id) {
        var result = new InspectionLabelResult(); result.setId(id); result.setConversationResultId("cr1");
        result.setLabelId("house"); result.setLabelVersionNo(2); result.setValueCode("owns");
        result.setValueJson("""
                {"schemaVersion":"iqc-label-result-v2","valueCode":"owns","valueType":"BOOLEAN",
                "subjectRole":"customer","status":"UNKNOWN","candidates":[],"reasons":[]}
                """);
        return result;
    }

    private String coveragePayload(String status) {
        String candidate = "{\"sourceRuleResultId\":\"rr1\",\"value\":false,"
                + "\"evidence\":[{\"messageId\":\"m1\",\"text\":\"否认拥有房产\"}]}";
        return switch (status) {
            case "KNOWN" -> "{\"schemaVersion\":\"iqc-label-result-v2\",\"valueCode\":\"owns\","
                    + "\"valueType\":\"BOOLEAN\",\"subjectRole\":\"customer\",\"status\":\"KNOWN\","
                    + "\"value\":false,\"candidates\":[" + candidate + "]}";
            case "CONFLICT" -> "{\"schemaVersion\":\"iqc-label-result-v2\",\"valueCode\":\"owns\","
                    + "\"valueType\":\"BOOLEAN\",\"subjectRole\":\"customer\",\"status\":\"CONFLICT\","
                    + "\"candidates\":[" + candidate + ","
                    + candidate.replace("false", "true").replace("rr1", "rr2") + "]}";
            case "UNKNOWN", "ERROR" -> "{\"schemaVersion\":\"iqc-label-result-v2\",\"valueCode\":\"owns\","
                    + "\"valueType\":\"BOOLEAN\",\"subjectRole\":\"customer\",\"status\":\"" + status + "\",\"candidates\":[]}";
            default -> throw new IllegalArgumentException(status);
        };
    }

    private InspectionLabelResult projection(String... ids) {
        var result = new InspectionLabelResult(); result.setConversationResultId("cr1"); result.setSourceRuleResultId("anchor");
        var payload = new ObjectMapper().createObjectNode().put("schemaVersion", "iqc-label-result-v2");
        var candidates = payload.putArray("candidates");
        for (var id : ids) candidates.addObject().put("sourceRuleResultId", id)
                .putArray("evidence").addObject().put("messageId", "m-" + id).put("text", "quote-" + id);
        result.setValueJson(payload.toString()); return result;
    }

    private InspectionEvidence quote(String sourceId) {
        var quote = new InspectionEvidence(); quote.setRuleResultId(sourceId);
        quote.setMessageId("m-" + sourceId); quote.setMatchedText("quote-" + sourceId);
        return quote;
    }

    private RuleInspectionResult source(String id, String conversationResultId) {
        var source = new RuleInspectionResult(); source.setId(id); source.setConversationResultId(conversationResultId); return source;
    }
    @Test
    void taskLabelsRequestExecutionOrderedCanonicalRows() {
        var taskMapper = (io.github.opensabre.iqc.task.dao.InspectionTaskMapper) ReflectionTestUtils.getField(service, "taskMapper");
        var conversations = (io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper)
                ReflectionTestUtils.getField(service, "conversationResultMapper");
        var scope = (io.github.opensabre.iqc.shared.IqcDataScope) ReflectionTestUtils.getField(service, "dataScope");
        when(taskMapper.selectById("trial")).thenReturn(jointTask());
        when(scope.canView(null, null)).thenReturn(true);
        when(conversations.selectLatestForTask("trial")).thenReturn(List.of());

        assertThat(service.listByTask("trial")).isEmpty();
        verify(conversations).selectLatestForTask("trial");
    }
}
