package io.github.opensabre.iqc.quality;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.quality.dao.ResultReviewMapper;
import io.github.opensabre.iqc.quality.model.ResultReview;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BusinessItemReviewServiceTest {
    private final ResultReviewMapper reviews = mock(ResultReviewMapper.class);
    private final ConversationInspectionResultMapper results = mock(ConversationInspectionResultMapper.class);
    private final InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
    private final ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final BusinessItemReviewService service = new BusinessItemReviewService(reviews, results, tasks, messages, scope, mapper);
    private InspectionTask task;
    private ConversationInspectionResult result;

    @BeforeEach
    void setup() {
        for (Class<?> type : List.of(InspectionTask.class, ResultReview.class, ConversationInspectionResult.class,
                io.github.opensabre.iqc.conversation.model.ConversationMessage.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), type.getSimpleName()), type);
        task = new InspectionTask(); task.setId("task"); task.setStatus("SUCCEEDED");
        task.setRuleSnapshotJson("""
                {"schemeSnapshot":{"release":{"definition":{"schemaVersion":"iqc-scheme-v2","items":[
                {"itemCode":"a","name":"问候","rule":{"id":"r1","versionNo":2},"hitMeaning":"VIOLATION"}],
                "scoring":{"version":"iqc-score-v2","mode":"DEDUCTION","baseScore":100,"passingScore":60,
                "items":[{"itemCode":"a","points":20,"veto":false}]}}}}}
                """);
        result = new ConversationInspectionResult(); result.setId("result"); result.setTaskId("task"); result.setConversationId("conversation");
        result.setResultStatus("FAIL"); result.setScoringResultJson("{}");
        result.setBusinessItemResultsJson("""
                [{"itemCode":"a","name":"问候","ruleId":"r1","ruleVersionNo":2,"status":"FAIL","matchedMessageIds":[]}]
                """);
        when(results.selectById("result")).thenReturn(result); when(results.selectLatestForTaskConversation(any(), any())).thenReturn(result);
        when(tasks.selectById("task")).thenReturn(task); when(tasks.selectOne(any())).thenReturn(task);
        when(scope.canView(null, null)).thenReturn(true); when(scope.owner()).thenReturn("reviewer");
    }

    private ResultReview request() {
        var review = service.request("result", 0, "request-token-0001", "申请原因");
        when(reviews.selectById(review.getId())).thenReturn(review);
        when(reviews.selectOne(any())).thenReturn(review);
        return review;
    }

    @Test
    void validatesUnchangedMachineScoreAndRejectsTamperedTotals() throws Exception {
        var policy = new InspectionScoring.Policy("iqc-score-v2", InspectionScoring.Mode.DEDUCTION, 100, 60,
                List.of(new InspectionScoring.Item("a", 20, false)));
        var expected = InspectionScoring.evaluate(policy, List.of(new InspectionScoring.Decision("a", InspectionScoring.ItemStatus.FAIL)));
        result.setScoringResultJson(mapper.writeValueAsString(expected));
        result.setScoreStatus("FINAL"); result.setFinalScore(new java.math.BigDecimal("80"));
        assertThat(service.validatedMachine(result, task)).isEqualTo(expected);
        result.setFinalScore(new java.math.BigDecimal("100"));
        assertThatThrownBy(() -> service.validatedMachine(result, task)).hasMessageContaining("不一致");
        result.setFinalScore(new java.math.BigDecimal("80")); result.setScoreStatus("PENDING");
        assertThatThrownBy(() -> service.validatedMachine(result, task)).hasMessageContaining("不一致");
    }
    private List<BusinessItemReviewEvaluator.Decision> decisions() {
        return List.of(new BusinessItemReviewEvaluator.Decision("result", "a", InspectionScoring.ItemStatus.FAIL,
                InspectionScoring.ItemStatus.PASS, "核对会话确认满足", List.of()));
    }

    @Test
    void queueDefaultsToPendingAndBoundsPagesBeforeQuerying() {
        when(reviews.selectBusinessQueue(any(), any(), any(), anyBoolean(), any(), any())).thenAnswer(call -> call.getArgument(0));
        var page = service.queue(null, null, 1, 20);
        assertThat(page.current()).isEqualTo(1); assertThat(page.size()).isEqualTo(20);
        verify(reviews).selectBusinessQueue(any(), eq("PENDING"), isNull(), eq(false), eq("reviewer"), isNull());
        when(scope.canViewAll()).thenReturn(true);
        service.queue("ALL", "task", 2, 10);
        verify(reviews).selectBusinessQueue(any(), isNull(), eq("task"), eq(true), eq("reviewer"), isNull());
        assertThatThrownBy(() -> service.queue("unknown", null, 1, 20)).hasMessageContaining("状态无效");
        assertThatThrownBy(() -> service.queue(null, null, 0, 20)).hasMessageContaining("分页范围");
        assertThatThrownBy(() -> service.queue(null, null, 1, 101)).hasMessageContaining("分页范围");
    }

    @Test
    void requestRetriesAreIdempotentAndDoNotReuseKeyForDifferentContent() {
        var first = request();
        assertThat(first.getResultId()).isNull(); assertThat(first.getReviewRevision()).isEqualTo(1);
        assertThat(service.request("result", 0, "request-token-0001", "申请原因")).isSameAs(first);
        verify(reviews, times(1)).insert(any(ResultReview.class));
        assertThatThrownBy(() -> service.request("result", 0, "request-token-0001", "其他原因")).hasMessageContaining("不同内容");
    }

    @Test
    void completesOncePreservesOriginalAndRejectsOverwritingHistory() throws Exception {
        var review = request();
        when(reviews.selectOne(any())).thenReturn(review, null);
        String machine = result.getBusinessItemResultsJson();
        service.decide(review.getId(), 1, false, decisions(), "裁决原因");
        var projection = mapper.readValue(review.getReviewedResultJson(), BusinessItemReviewEvaluator.Projection.class);
        assertThat(projection.original().scoring().finalScore()).isEqualByComparingTo("80");
        assertThat(projection.reviewed().scoring().finalScore()).isEqualByComparingTo("100");
        assertThat(review.getRequestComment()).isEqualTo("申请原因");
        assertThat(review.getReviewComment()).isEqualTo("裁决原因");
        assertThat(result.getBusinessItemResultsJson()).isEqualTo(machine);
        assertThat(service.decide(review.getId(), 1, false, decisions(), "裁决原因")).isSameAs(review);
        verify(reviews, times(1)).updateById(any(ResultReview.class));
        assertThatThrownBy(() -> service.decide(review.getId(), 1, true, List.of(), "改判")).hasMessageContaining("不能覆盖");
    }

    @Test
    void cannotApplyToChangedOrSupersededResult() {
        var review = request();
        result.setScoringResultJson("{\"changed\":true}");
        assertThatThrownBy(() -> service.decide(review.getId(), 1, false, decisions(), "原因")).hasMessageContaining("原始结果已变化");
        var newer = new ConversationInspectionResult(); newer.setId("new-result"); when(results.selectLatestForTaskConversation(any(), any())).thenReturn(newer);
        assertThatThrownBy(() -> service.decide(review.getId(), 1, false, decisions(), "原因")).hasMessageContaining("更新的质检结果");
        verify(reviews, never()).updateById(any(ResultReview.class));
    }

    @Test
    void enforcesScopeTerminalStateAndRevision() {
        when(scope.canView(null, null)).thenReturn(false);
        assertThatThrownBy(() -> service.request("result", 0, "request-token-0001", "原因")).hasMessageContaining("无权");
        assertThatThrownBy(() -> service.history("result")).hasMessageContaining("无权");
        when(scope.canView(null, null)).thenReturn(true); task.setStatus("RUNNING");
        assertThatThrownBy(() -> service.request("result", 0, "request-token-0001", "原因")).hasMessageContaining("尚未结束");
        task.setStatus("SUCCEEDED");
        assertThatThrownBy(() -> service.request("result", 2, "request-token-0001", "原因")).hasMessageContaining("版本已变化");
        verify(reviews, never()).insert(any(ResultReview.class));
    }

    @Test
    void rejectsParallelPendingRoundAndSupportsRejectionWithoutScoreChanges() {
        var review = request();
        assertThatThrownBy(() -> service.request("result", 1, "request-token-0002", "原因")).hasMessageContaining("待处理");
        service.decide(review.getId(), 1, true, List.of(), "退回补充证据");
        assertThat(review.getStatus()).isEqualTo("REJECTED"); assertThat(review.getReviewedResultJson()).isNull();
        assertThat(service.request("result", 1, "request-token-0002", "再次申请").getReviewRevision()).isEqualTo(2);
    }

    @Test
    void nextRoundPreservesEarlierDecisionAndHistory() throws Exception {
        var first = request();
        when(reviews.selectOne(any())).thenReturn(first, null);
        service.decide(first.getId(), 1, false, decisions(), "第一轮");
        String firstSnapshot = first.getReviewedResultJson();
        when(reviews.selectOne(any())).thenReturn(first);
        var second = service.request("result", 1, "request-token-0002", "第二轮申请");
        when(reviews.selectById(second.getId())).thenReturn(second);
        when(reviews.selectOne(any())).thenReturn(second, first);
        var correction = new BusinessItemReviewEvaluator.Decision("result", "a", InspectionScoring.ItemStatus.FAIL,
                InspectionScoring.ItemStatus.NOT_APPLICABLE, "确认不适用", List.of());
        service.decide(second.getId(), 2, false, List.of(correction), "第二轮裁决");
        assertThat(first.getReviewedResultJson()).isEqualTo(firstSnapshot);
        assertThat(second.getReviewRevision()).isEqualTo(2);
        var projection = mapper.readValue(second.getReviewedResultJson(), BusinessItemReviewEvaluator.Projection.class);
        assertThat(projection.original().items().getFirst().status()).isEqualTo(InspectionScoring.ItemStatus.FAIL);
        assertThat(projection.reviewed().scoring().scoreStatus()).isEqualTo(InspectionScoring.ScoreStatus.NOT_APPLICABLE);
    }

    private ResultReview completed() {
        var review = request();
        when(reviews.selectOne(any())).thenReturn(review, null);
        service.decide(review.getId(), 1, false, decisions(), "裁决原因");
        return review;
    }

    private java.util.Map<String, String> unzip(byte[] bytes) throws Exception {
        var entries = new java.util.LinkedHashMap<String, String>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes), java.nio.charset.StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
                entries.put(entry.getName(), new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return entries;
    }

    @Test
    void batchExportIncludesCoverageGapsAndFlagsANewerPendingRound() throws Exception {
        task.setConversationIdsJson("[\"conversation\",\"missing\",\"unreviewed\"]");
        var first = completed();
        var pending = new ResultReview(); pending.setTargetType("BUSINESS"); pending.setBusinessResultId("result");
        pending.setId("pending-round"); pending.setReviewRevision(2); pending.setStatus("PENDING");
        var unreviewed = new ConversationInspectionResult(); unreviewed.setId("unreviewed-result");
        unreviewed.setTaskId("task"); unreviewed.setConversationId("unreviewed");
        when(results.selectLatestForTaskConversation(any(), any())).thenReturn(result, null, unreviewed);
        when(reviews.selectOne(any())).thenReturn(first, pending, null, null);
        var entries = unzip(service.exportTaskZip("task"));
        assertThat(entries.keySet()).containsExactly("coverage.csv", "reviewed-items.csv");
        assertThat(entries.get("coverage.csv")).contains("\"missing\",\"\",\"\",\"NO_RESULT\"")
                .contains("NO_COMPLETED_REVIEW").contains("\"PENDING\",\"2\",\"true\"");
        assertThat(entries.get("reviewed-items.csv")).contains(first.getId()).doesNotContain("pending-round");
        assertThat(entries.get("reviewed-items.csv")).isEqualTo(service.exportCsv(first.getId()));
    }

    @Test
    void batchDoesNotFallBackToAReviewOfAnOlderExecution() throws Exception {
        task.setConversationIdsJson("[\"conversation\"]");
        completed();
        var current = new ConversationInspectionResult(); current.setId("new-result"); current.setTaskId("task");
        current.setConversationId("conversation");
        when(results.selectLatestForTaskConversation(any(), any())).thenReturn(current);
        when(reviews.selectOne(any())).thenAnswer(call -> {
            var query = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResultReview>) call.getArgument(0);
            query.getSqlSegment();
            assertThat(query.getParamNameValuePairs().values()).contains("new-result").doesNotContain("result");
            return null;
        });
        var entries = unzip(service.exportTaskZip("task"));
        assertThat(entries.keySet()).containsExactly("coverage.csv");
        assertThat(entries.get("coverage.csv")).contains("new-result", "NO_COMPLETED_REVIEW");
    }

    @Test
    void batchRejectsScopeRunningTaskMalformedSelectionAndLimitsBeforeReadingResults() {
        task.setConversationIdsJson("[\"conversation\"]");
        when(scope.canView(null, null)).thenReturn(false);
        assertThatThrownBy(() -> service.exportTaskZip("task")).hasMessageContaining("无权");
        when(scope.canView(null, null)).thenReturn(true); task.setStatus("RUNNING");
        assertThatThrownBy(() -> service.exportTaskZip("task")).hasMessageContaining("任务结束");
        task.setStatus("SUCCEEDED"); task.setConversationIdsJson("[null]");
        assertThatThrownBy(() -> service.exportTaskZip("task")).hasMessageContaining("范围损坏");
        task.setConversationIdsJson(java.util.stream.IntStream.range(0, 501).mapToObj(index -> "\"c" + index + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]")));
        assertThatThrownBy(() -> service.exportTaskZip("task")).hasMessageContaining("500 个会话");
        verifyNoInteractions(results, reviews, messages);
    }

    @Test
    void batchRejectsTheWholeArchiveWhenASelectedReviewIsCorrupt() {
        task.setConversationIdsJson("[\"conversation\"]");
        var review = completed();
        review.setReviewedResultJson("{}");
        when(reviews.selectOne(any())).thenReturn(review);
        assertThatThrownBy(() -> service.exportTaskZip("task")).hasMessageContaining("来源不一致");
    }

    @Test
    void batchCombinesCsvRowsWithoutDuplicatingHeadersOrBreakingMultilineReasons() throws Exception {
        task.setConversationIdsJson("[\"conversation\",\"other\"]");
        var first = completed();
        var secondResult = new ConversationInspectionResult(); secondResult.setId("second"); secondResult.setTaskId("task");
        secondResult.setConversationId("other"); secondResult.setBusinessItemResultsJson(result.getBusinessItemResultsJson());
        secondResult.setScoringResultJson(result.getScoringResultJson());
        when(results.selectById("second")).thenReturn(secondResult); when(results.selectLatestForTaskConversation(any(), any())).thenReturn(secondResult);
        when(reviews.selectOne(any())).thenReturn(null);
        var second = service.request("second", 0, "request-token-0002", "第二会话");
        when(reviews.selectById(second.getId())).thenReturn(second);
        when(reviews.selectOne(any())).thenReturn(second, null);
        service.decide(second.getId(), 1, false, List.of(new BusinessItemReviewEvaluator.Decision("second", "a",
                InspectionScoring.ItemStatus.FAIL, InspectionScoring.ItemStatus.PASS, "多行,\"原因\"\n证据", List.of())), "裁决");
        when(results.selectLatestForTaskConversation(any(), any())).thenReturn(result, secondResult);
        when(reviews.selectOne(any())).thenReturn(first, first, second, second);
        String csv = unzip(service.exportTaskZip("task")).get("reviewed-items.csv");
        assertThat(csv).contains(first.getId(), second.getId(), "多行,\"\"原因\"\"\n证据");
        assertThat(csv.chars().filter(value -> value == '\uFEFF').count()).isEqualTo(1);
        assertThat(csv.lines().filter(line -> line.contains("机器会话分数（勿按行求和）")).count()).isEqualTo(1);
    }

    @Test
    void batchEnforcesItemBudgetEvenBelowTheConversationLimit() throws Exception {
        var snapshot = mapper.readTree(task.getRuleSnapshotJson());
        var items = (com.fasterxml.jackson.databind.node.ArrayNode) snapshot.path("schemeSnapshot").path("release").path("definition").path("items");
        for (int index = 1; index < 200; index++) {
            var item = (com.fasterxml.jackson.databind.node.ObjectNode) items.get(0).deepCopy();
            item.put("itemCode", "a" + index); items.add(item);
        }
        task.setRuleSnapshotJson(mapper.writeValueAsString(snapshot));
        task.setConversationIdsJson(mapper.writeValueAsString(java.util.stream.IntStream.range(0, 251).mapToObj(index -> "c" + index).toList()));
        assertThatThrownBy(() -> service.exportTaskZip("task")).hasMessageContaining("50000");
        verifyNoInteractions(results, reviews, messages);
    }

    @Test
    void exportsThePinnedRoundEvenWhenItsExecutionIsSuperseded() {
        task.setName("=HYPERLINK(\"unsafe\")");
        var review = completed();
        var newer = new ConversationInspectionResult(); newer.setId("newer");
        when(results.selectLatestForTaskConversation(any(), any())).thenReturn(newer);
        String csv = service.exportCsv(review.getId());
        assertThat(csv).startsWith("\uFEFF任务ID").contains("\"80\",\"FINAL\",\"100\"")
                .contains("\"FAIL\",\"PASS\",\"HUMAN_EFFECTIVE\"")
                .contains("\"'=HYPERLINK(\"\"unsafe\"\")\"").contains(review.getId()).doesNotContain("newer");
        verify(results, never()).updateById(any(ConversationInspectionResult.class));
    }

    @Test
    void exportPreservesZeroAndLeavesNonApplicableScoreBlank() {
        task.setRuleSnapshotJson(task.getRuleSnapshotJson().replace("\"baseScore\":100", "\"baseScore\":20")
                .replace("\"passingScore\":60", "\"passingScore\":10"));
        var review = completed();
        assertThat(service.exportCsv(review.getId())).contains("\"0\",\"FINAL\",\"20\"");
        // A separate completed round can legitimately have no applicable scoring items.
        when(reviews.selectOne(any())).thenReturn(review);
        var second = service.request("result", 1, "request-token-0002", "确认不适用");
        when(reviews.selectById(second.getId())).thenReturn(second);
        when(reviews.selectOne(any())).thenReturn(second, review);
        service.decide(second.getId(), 2, false, List.of(new BusinessItemReviewEvaluator.Decision("result", "a",
                InspectionScoring.ItemStatus.FAIL, InspectionScoring.ItemStatus.NOT_APPLICABLE, "不适用", List.of())), "不适用");
        assertThat(service.exportCsv(second.getId())).contains("\"NOT_APPLICABLE\",\"\"");
    }

    @Test
    void exportKeepsPointsModeDecimalScores() throws Exception {
        var snapshot = mapper.readTree(task.getRuleSnapshotJson());
        var definition = snapshot.path("schemeSnapshot").path("release").path("definition");
        ((com.fasterxml.jackson.databind.node.ArrayNode) definition.path("items")).add(mapper.readTree("""
                {"itemCode":"b","name":"其他","rule":{"id":"r2","versionNo":1},"hitMeaning":"VIOLATION"}
                """));
        var policy = (com.fasterxml.jackson.databind.node.ObjectNode) definition.path("scoring");
        policy.put("mode", "POINTS");
        policy.set("items", mapper.readTree("""
                [{"itemCode":"a","points":2,"veto":false},{"itemCode":"b","points":1,"veto":false}]
                """));
        task.setRuleSnapshotJson(mapper.writeValueAsString(snapshot));
        var items = (com.fasterxml.jackson.databind.node.ArrayNode) mapper.readTree(result.getBusinessItemResultsJson());
        items.add(mapper.readTree("""
                {"itemCode":"b","name":"其他","ruleId":"r2","ruleVersionNo":1,"status":"FAIL","matchedMessageIds":[]}
                """));
        result.setBusinessItemResultsJson(mapper.writeValueAsString(items));
        var review = completed();
        assertThat(service.exportCsv(review.getId())).contains("\"66.67\"").contains("\"MACHINE\"");
    }

    @Test
    void exportRejectsUnauthorizedUnfinishedAndChangedSources() {
        var review = request();
        assertThatThrownBy(() -> service.exportCsv(review.getId())).hasMessageContaining("已完成");
        when(reviews.selectOne(any())).thenReturn(review, null);
        service.decide(review.getId(), 1, false, decisions(), "完成");
        when(scope.canView(null, null)).thenReturn(false);
        assertThatThrownBy(() -> service.exportCsv(review.getId())).hasMessageContaining("无权");
        when(scope.canView(null, null)).thenReturn(true);
        result.setScoringResultJson("changed");
        assertThatThrownBy(() -> service.exportCsv(review.getId())).hasMessageContaining("基准");
        assertThatThrownBy(() -> service.exportCsv(null)).hasMessageContaining("轮次 ID");
    }

    @Test
    void exportDoesNotInventAMachineScoreForAnUnresolvedFinding() {
        result.setBusinessItemResultsJson(result.getBusinessItemResultsJson().replace("FAIL", "ERROR"));
        var review = request();
        when(reviews.selectOne(any())).thenReturn(review, null);
        service.decide(review.getId(), 1, false, List.of(new BusinessItemReviewEvaluator.Decision("result", "a",
                InspectionScoring.ItemStatus.ERROR, InspectionScoring.ItemStatus.PASS, "人工核对", List.of())), "确认");
        assertThat(service.exportCsv(review.getId())).contains("\"PENDING\",\"\",\"FINAL\",\"100\"");
    }

    @Test
    void exportRejectsTamperedScoringAndCrossConversationEvidence() throws Exception {
        var review = completed();
        String saved = review.getReviewedResultJson();
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(saved);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("reviewed").path("scoring")).put("finalScore", 99);
        review.setReviewedResultJson(mapper.writeValueAsString(json));
        assertThatThrownBy(() -> service.exportCsv(review.getId())).hasMessageContaining("快照不一致");
        json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(saved);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("decisions").get(0)).putArray("evidenceMessageIds").add("foreign-message");
        review.setReviewedResultJson(mapper.writeValueAsString(json));
        assertThatThrownBy(() -> service.exportCsv(review.getId())).hasMessageContaining("当前会话");
    }
}
