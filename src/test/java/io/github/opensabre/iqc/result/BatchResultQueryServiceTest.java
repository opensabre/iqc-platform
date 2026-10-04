package io.github.opensabre.iqc.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BatchResultQueryServiceTest {
    private final InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
    private final InspectionResultMapper results = mock(InspectionResultMapper.class);
    private final ConversationInspectionResultMapper canonical = mock(ConversationInspectionResultMapper.class);
    private final TaskExecutionMapper executions = mock(TaskExecutionMapper.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final BatchResultQueryService service = new BatchResultQueryService(tasks, conversations,
            messages, results, scope, new ObjectMapper(), canonical, executions);
    private InspectionTask task;

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "batch-query"), ConversationInspectionResult.class);
        task = new InspectionTask(); task.setId("t1"); task.setConversationId("c1"); task.setStatus("SUCCEEDED");
        task.setRuleSnapshotJson("{\"schemeSnapshot\":{}}"); task.setCurrentExecutionId("e2");
        task.setTotalMessages(1); task.setProcessedMessages(1); task.setFailedMessages(0);
        when(tasks.selectById("t1")).thenReturn(task); when(scope.canView(null, null)).thenReturn(true);
        var conversation = new Conversation(); conversation.setId("c1"); conversation.setMessageCount(2);
        when(conversations.selectById("c1")).thenReturn(conversation);
        var first = new TaskExecution(); first.setId("e1"); first.setAttemptNo(1);
        var second = new TaskExecution(); second.setId("e2"); second.setAttemptNo(2);
        when(executions.selectList(any())).thenReturn(List.of(first, second));
        var raw = new InspectionResult(); raw.setConversationId("c1"); raw.setExecutionId("e1"); raw.setResultStatus("HIT"); raw.setScore(100);
        raw.setMessageId("m1");
        when(results.selectList(any())).thenReturn(List.of(raw));
    }

    @Test
    void schemeSummaryUsesCanonicalDecimalInsteadOfMessageAverage() {
        var decision = new ConversationInspectionResult(); decision.setFinalScore(new BigDecimal("66.67")); decision.setScoreStatus("FINAL");
        decision.setBusinessItemResultsJson("[{\"status\":\"FAIL\"}]"); decision.setRiskLevel("HIGH");
        // A retry of another conversation must not hide this conversation's completed result from e1.
        decision.setExecutionId("e1"); when(canonical.selectLatestForTaskConversation("t1", "c1")).thenReturn(decision);
        var summary = service.summary("t1");
        assertThat(summary.averageScore()).isEqualByComparingTo("66.67");
        assertThat(summary.conversations().getFirst().scoreStatus()).isEqualTo("FINAL");
        assertThat(summary.hitCount()).isEqualTo(1);
    }

    @Test
    void batchSummaryUsesExecutionOrderedCanonicalResult() {
        service.summary("t1");
        verify(canonical).selectLatestForTaskConversation("t1", "c1");
    }

    @Test
    void schemeSummaryExcludesHistoricalRetryErrorsFromCurrentObservationCounts() {
        var previous = new InspectionResult(); previous.setConversationId("c1"); previous.setMessageId("m1"); previous.setExecutionId("e1"); previous.setResultStatus("LLM_ERROR");
        var recovered = new InspectionResult(); recovered.setConversationId("c1"); recovered.setMessageId("m1"); recovered.setExecutionId("e2"); recovered.setResultStatus("HIT");
        when(results.selectList(any())).thenReturn(List.of(previous, recovered));
        var decision = new ConversationInspectionResult(); decision.setExecutionId("e2"); decision.setScoreStatus("FINAL");
        decision.setFinalScore(BigDecimal.valueOf(90));
        when(canonical.selectLatestForTaskConversation("t1", "c1")).thenReturn(decision);

        var row = service.summary("t1").conversations().getFirst();

        assertThat(row.resultCount()).isEqualTo(1);
        assertThat(row.errorCount()).isZero();
    }

    @Test
    void partialConversationRetryKeepsEarlierSuccessfulMessageAndReplacesOnlyFailedObservation() {
        var successful = new InspectionResult(); successful.setId("old-success"); successful.setConversationId("c1");
        successful.setMessageId("m1"); successful.setExecutionId("e1"); successful.setResultStatus("HIT");
        var failed = new InspectionResult(); failed.setId("old-failure"); failed.setConversationId("c1");
        failed.setMessageId("m2"); failed.setExecutionId("e1"); failed.setResultStatus("LLM_ERROR");
        var recovered = new InspectionResult(); recovered.setId("new-success"); recovered.setConversationId("c1");
        recovered.setMessageId("m2"); recovered.setExecutionId("e2"); recovered.setResultStatus("HIT");
        when(results.selectList(any())).thenReturn(List.of(successful, failed, recovered));
        var decision = new ConversationInspectionResult(); decision.setExecutionId("e2"); decision.setScoreStatus("FINAL");
        decision.setFinalScore(BigDecimal.valueOf(90));
        when(canonical.selectLatestForTaskConversation("t1", "c1")).thenReturn(decision);

        var row = service.summary("t1").conversations().getFirst();

        assertThat(row.resultCount()).isEqualTo(2);
        assertThat(row.errorCount()).isZero();
        assertThat(service.conversationDetail("t1", "c1").results()).extracting(InspectionResult::getId)
                .containsExactlyInAnyOrder("old-success", "new-success");
    }

    @Test
    void pendingOrMissingCanonicalResultNeverFallsBackToOldMessageScore() {
        assertThat(service.summary("t1").averageScore()).isNull();
        var pending = new ConversationInspectionResult(); pending.setScoreStatus("PENDING"); pending.setFinalScore(BigDecimal.valueOf(100));
        when(canonical.selectLatestForTaskConversation("t1", "c1")).thenReturn(pending);
        assertThat(service.summary("t1").conversations().getFirst().averageScore()).isNull();
    }

    @Test
    void legacyTaskRetainsItsHistoricalMessageAverage() {
        task.setRuleSnapshotJson("[]");
        assertThat(service.summary("t1").averageScore()).isEqualByComparingTo("100");
        verifyNoInteractions(canonical);
    }

    @Test
    void unauthorizedTaskCannotReadCanonicalResults() {
        when(scope.canView(null, null)).thenReturn(false);
        assertThatThrownBy(() -> service.summary("t1")).hasMessageContaining("无权");
        verifyNoInteractions(canonical, results);
    }

    @Test
    void businessPageDoesNotReadResultsWithoutAnyVisibleTask() {
        when(tasks.selectList(any())).thenReturn(List.of());
        assertThat(service.businessPage(0, 200, "hidden", null, null, null, null).total()).isZero();
        verifyNoInteractions(canonical, results);
    }

    @Test
    void businessPageRejectsMessageStatusesAndReversedScoreBounds() {
        assertThatThrownBy(() -> service.businessPage(1, 20, null, "HIT", null, null, null))
                .hasMessageContaining("筛选条件");
        assertThatThrownBy(() -> service.businessPage(1, 20, null, null, null, BigDecimal.TEN, BigDecimal.ONE))
                .hasMessageContaining("筛选条件");
        verifyNoInteractions(canonical);
    }

    private void businessSnapshot() {
        task.setRuleSnapshotJson("""
                {"schemeSnapshot":{"schemeId":"scheme1","kind":"PUBLISHED","versionNo":2,"contentHash":"frozen-hash",
                "release":{"definition":{"schemaVersion":"iqc-scheme-v2","items":[
                {"itemCode":"greeting","name":"问候","rule":{"id":"r1","versionNo":3},"hitMeaning":"VIOLATION"}],
                "scoring":{"version":"iqc-score-v2","mode":"DEDUCTION","baseScore":100,"passingScore":60,
                "items":[{"itemCode":"greeting","points":10,"veto":false}]}}}}}
                """);
    }

    @Test
    void labelOnlyBusinessExportPointsToExistingLabelExportInsteadOfReturningHeaderOnly() {
        task.setRuleSnapshotJson("""
                {"schemeSnapshot":{"release":{"definition":{"schemaVersion":"iqc-scheme-v2","items":[],
                "scoring":{"version":"iqc-score-v2","mode":"DEDUCTION","baseScore":100,"passingScore":60,"items":[]},
                "labels":[{"id":"house","versionNo":1}]}}}}
                """);
        assertThatThrownBy(() -> service.exportSchemeCsv("t1"))
                .hasMessageContaining("仅识别标签").hasMessageContaining("XLSX");
        verifyNoInteractions(canonical, messages, results);
    }

    @Test
    void exportMissingCanonicalShowsPendingWithoutLegacyScore() {
        businessSnapshot();
        String csv = service.exportSchemeCsv("t1");
        assertThat(csv).contains("勿按行求和", "\"frozen-hash\"", "\"PENDING\",\"\"", "\"NOT_EVALUATED\"")
                .doesNotContain("\"100\"");
        verifyNoInteractions(results);
    }

    @Test
    void exportKeepsCanonicalDecimalAndPreviousExecution() {
        businessSnapshot();
        var decision = new ConversationInspectionResult();
        decision.setExecutionId("previous-execution"); decision.setScoreStatus("FINAL"); decision.setFinalScore(new BigDecimal("66.67"));
        decision.setBusinessItemResultsJson("""
                [{"itemCode":"greeting","ruleId":"r1","ruleVersionNo":3,"status":"FAIL","matchedMessageIds":[]}]
                """);
        decision.setScoringResultJson("""
                {"scoreStatus":"FINAL","finalScore":66.67,"lines":[{"itemCode":"greeting","status":"FAIL","contribution":10,"vetoTriggered":false}]}
                """);
        when(canonical.selectLatestForTaskConversation("t1", "c1")).thenReturn(decision);
        assertThat(service.exportSchemeCsv("t1")).contains("\"previous-execution\",\"FINAL\",\"66.67\"", "\"FAIL\",\"r1\",\"3\",\"10\"");
        decision.setFinalScore(BigDecimal.ZERO);
        decision.setScoringResultJson(decision.getScoringResultJson().replace("66.67", "0"));
        assertThat(service.exportSchemeCsv("t1")).contains("\"FINAL\",\"0\"");
        decision.setScoreStatus("PENDING");
        decision.setScoringResultJson(decision.getScoringResultJson().replace("FINAL", "PENDING"));
        assertThat(service.exportSchemeCsv("t1")).contains("\"PENDING\",\"\"");
        decision.setBusinessItemResultsJson(decision.getBusinessItemResultsJson().replace("[]", "[\"m1\"]"));
        assertThatThrownBy(() -> service.exportSchemeCsv("t1")).hasMessageContaining("不属于当前会话");
        var message = new io.github.opensabre.iqc.conversation.model.ConversationMessage();
        message.setId("m1"); message.setContent("原文,\"证据\"\n下一行");
        when(messages.selectList(any())).thenReturn(List.of(message));
        assertThat(service.exportSchemeCsv("t1")).contains("\"m1\"", "m1: 原文,\"\"证据\"\"\n下一行");
        decision.setBusinessItemResultsJson("[]");
        assertThatThrownBy(() -> service.exportSchemeCsv("t1")).hasMessageContaining("不完整");
    }

    @Test
    void exportRejectsLegacyInvalidAndUnauthorizedTasks() {
        task.setRuleSnapshotJson("[]");
        assertThatThrownBy(() -> service.exportSchemeCsv("t1")).hasMessageContaining("不是业务方案");
        task.setRuleSnapshotJson("{\"schemeSnapshot\":{}}");
        assertThatThrownBy(() -> service.exportSchemeCsv("t1")).hasMessageContaining("快照无效");
        when(scope.canView(null, null)).thenReturn(false);
        assertThatThrownBy(() -> service.exportSchemeCsv("t1")).hasMessageContaining("无权");
        assertThatThrownBy(() -> service.exportSchemeCsv(" ")).hasMessageContaining("必须选择");
        verifyNoInteractions(canonical, results);
    }

    @Test
    void csvEscapesQuotesNewlinesAndSpreadsheetFormulas() {
        assertThat(InspectionExecutionService.row("a,\"b\"\nc")).isEqualTo("\"a,\"\"b\"\"\nc\"");
        for (String input : List.of("=1+1", " +cmd", "-cmd", "@SUM(1)", "\ttext", "\rtext", "\ntext"))
            assertThat(InspectionExecutionService.row(input)).startsWith("\"'");
        assertThat(InspectionExecutionService.row(null)).isEqualTo("\"\"");
    }

    @Test
    void exportRejectsOversizedOrCorruptScopeBeforeQueryingResults() throws Exception {
        businessSnapshot();
        var mapper = new ObjectMapper();
        var snapshot = mapper.readTree(task.getRuleSnapshotJson());
        var items = (com.fasterxml.jackson.databind.node.ArrayNode) snapshot.at("/schemeSnapshot/release/definition/items");
        var first = items.get(0).deepCopy();
        for (int i = 1; i < 200; i++) {
            var next = (com.fasterxml.jackson.databind.node.ObjectNode) first.deepCopy();
            next.put("itemCode", "item-" + i); items.add(next);
        }
        task.setRuleSnapshotJson(mapper.writeValueAsString(snapshot));
        task.setConversationIdsJson(mapper.writeValueAsString(java.util.stream.IntStream.range(0, 251).mapToObj(i -> "c" + i).toList()));
        assertThatThrownBy(() -> service.exportSchemeCsv("t1")).hasMessageContaining("50000");
        task.setConversationIdsJson("[null]");
        assertThatThrownBy(() -> service.exportSchemeCsv("t1")).hasMessageContaining("范围损坏");
        verifyNoInteractions(canonical, messages, results);
    }
}
