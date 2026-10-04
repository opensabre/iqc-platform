package io.github.opensabre.iqc.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessQualityReportServiceTest {
    private final InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
    private final ConversationInspectionResultMapper results = mock(ConversationInspectionResultMapper.class);
    private final BusinessItemReviewService reviews = mock(BusinessItemReviewService.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final TaskExecutionMapper executions = mock(TaskExecutionMapper.class);
    private final BusinessQualityReportService service = new BusinessQualityReportService(tasks, results, reviews, scope, new ObjectMapper(), executions);

    private InspectionTask task(String id, int threshold, String conversations) {
        var task = new InspectionTask(); task.setId(id); task.setConversationIdsJson(conversations);
        task.setRuleSnapshotJson("""
                {"schemeSnapshot":{"schemeId":"s","versionNo":1,"kind":"PUBLISHED","release":{"definition":{
                "schemaVersion":"iqc-scheme-v2","items":[{"itemCode":"a","name":"问候","rule":{"id":"r","versionNo":1},"hitMeaning":"VIOLATION"}],
                "scoring":{"version":"iqc-score-v2","mode":"DEDUCTION","baseScore":100,"passingScore":%d,
                "items":[{"itemCode":"a","points":100,"veto":false}]}}}}}
                """.formatted(threshold));
        when(tasks.selectById(id)).thenReturn(task);
        when(scope.canView(null, null)).thenReturn(true);
        return task;
    }

    private InspectionScoring.Result score(InspectionScoring.ItemStatus status) {
        return InspectionScoring.evaluate(new InspectionScoring.Policy("iqc-score-v2", InspectionScoring.Mode.DEDUCTION,
                100, 90, List.of(new InspectionScoring.Item("a", 100, false))), List.of(new InspectionScoring.Decision("a", status)));
    }

    private io.github.opensabre.iqc.scheme.SchemeResultEvaluator.Evaluation evaluation(InspectionScoring.ItemStatus status) {
        return new io.github.opensabre.iqc.scheme.SchemeResultEvaluator.Evaluation(List.of(
                new io.github.opensabre.iqc.scheme.SchemeResultEvaluator.ItemResult("a", "问候", "r", 1, status, List.of())), score(status));
    }

    @Test
    void weightsConversationsAndPreservesZeroWithoutHumanFallback() {
        task("a", 90, "[\"c1\"]"); task("b", 90, "[\"c2\",\"c3\"]");
        var result = new ConversationInspectionResult(); result.setId("r");
        when(results.selectLatestForTaskConversation(any(), any())).thenReturn(result);
        when(reviews.validatedMachineEvaluation(any(), any())).thenReturn(evaluation(InspectionScoring.ItemStatus.FAIL),
                evaluation(InspectionScoring.ItemStatus.PASS), evaluation(InspectionScoring.ItemStatus.PASS));
        var report = service.report(List.of("a", "b", "a"));
        assertThat(report.taskCount()).isEqualTo(2);
        var group = report.groups().getFirst();
        assertThat(report.groups()).hasSize(1);
        assertThat(group.baseScore()).isEqualTo(100);
        assertThat(group.machine().averageScore()).isEqualByComparingTo("66.67");
        assertThat(group.machine().qualifiedRate()).isEqualByComparingTo("66.67");
        assertThat(group.reviewed().averageScore()).isNull();
        assertThat(group.missingReviewCount()).isEqualTo(3);
        assertThat(group.items().getFirst().machine().failureRate()).isEqualByComparingTo("33.33");
        assertThat(group.items().getFirst().points()).isEqualTo(100);
        assertThat(group.items().getFirst().veto()).isFalse();
        assertThat(group.items().getFirst().reviewed().resultCount()).isZero();
        assertThat(group.items().getFirst().reviewed().failureRate()).isNull();
    }

    @Test
    void separatesFrozenPoliciesAndCountsMissingResultsExplicitly() {
        task("a", 60, "[\"c\"]"); task("b", 90, "[\"c\"]");
        var report = service.report(List.of("a", "b"));
        assertThat(report.conversationCount()).isEqualTo(2);
        assertThat(report.groups()).hasSize(2).allSatisfy(group -> {
            assertThat(group.missingResultCount()).isEqualTo(1);
            assertThat(group.machine().averageScore()).isNull();
            assertThat(group.machine().qualifiedRate()).isNull();
            assertThat(group.items().getFirst().machine().resultCount()).isZero();
            assertThat(group.items().getFirst().machine().failureRate()).isNull();
        });
    }

    @Test
    void pendingAndNotApplicableNeverEnterScoreDenominators() {
        task("a", 90, "[\"c1\",\"c2\"]");
        var result = new ConversationInspectionResult(); result.setId("r");
        when(results.selectLatestForTaskConversation(any(), any())).thenReturn(result);
        when(reviews.validatedMachineEvaluation(any(), any())).thenReturn(evaluation(InspectionScoring.ItemStatus.REVIEW_REQUIRED), evaluation(InspectionScoring.ItemStatus.NOT_APPLICABLE));
        var score = service.report(List.of("a")).groups().getFirst().machine();
        assertThat(score.pendingCount()).isEqualTo(1);
        assertThat(score.notApplicableCount()).isEqualTo(1);
        assertThat(score.finalCount()).isZero();
        assertThat(score.averageScore()).isNull();
    }

    @Test
    void authorizesEveryTaskBeforeReadingResults() {
        task("a", 60, "[\"c\"]");
        assertThatThrownBy(() -> service.report(List.of("a", "forbidden"))).hasMessageContaining("无权");
        verifyNoInteractions(results, reviews);
    }

    @Test
    void completedReviewRemainsSeparateWhenAnotherRoundIsPending() {
        var task = task("a", 90, "[\"c\"]");
        var result = new ConversationInspectionResult(); result.setId("r");
        var completed = new io.github.opensabre.iqc.quality.model.ResultReview(); completed.setStatus("COMPLETED");
        var pending = new io.github.opensabre.iqc.quality.model.ResultReview(); pending.setStatus("PENDING");
        when(results.selectLatestForTaskConversation(any(), any())).thenReturn(result);
        when(reviews.validatedMachineEvaluation(result, task)).thenReturn(evaluation(InspectionScoring.ItemStatus.FAIL));
        when(reviews.latest("r", true)).thenReturn(completed);
        when(reviews.latest("r", false)).thenReturn(pending);
        var projection = mock(BusinessItemReviewEvaluator.Projection.class, RETURNS_DEEP_STUBS);
        when(projection.reviewed()).thenReturn(evaluation(InspectionScoring.ItemStatus.PASS));
        when(reviews.validatedProjection(completed, result, task)).thenReturn(projection);
        var group = service.report(List.of("a")).groups().getFirst();
        assertThat(group.machine().averageScore()).isEqualByComparingTo("0");
        assertThat(group.reviewed().averageScore()).isEqualByComparingTo("100");
        assertThat(group.pendingReviewCount()).isEqualTo(1);
        assertThat(group.missingReviewCount()).isZero();
        assertThat(group.items().getFirst().machine().failureRate()).isEqualByComparingTo("100");
        assertThat(group.items().getFirst().reviewed().failureRate()).isEqualByComparingTo("0");
    }

    @Test
    void allSixItemStatesRemainSeparateAndUnresolvedStatesDoNotDiluteFailureRate() {
        task("a", 90, "[\"c1\",\"c2\",\"c3\",\"c4\",\"c5\",\"c6\"]");
        var result = new ConversationInspectionResult(); result.setId("r");
        when(results.selectLatestForTaskConversation(any(), any())).thenReturn(result);
        when(reviews.validatedMachineEvaluation(any(), any())).thenReturn(
                evaluation(InspectionScoring.ItemStatus.PASS), evaluation(InspectionScoring.ItemStatus.FAIL),
                evaluation(InspectionScoring.ItemStatus.NOT_APPLICABLE), evaluation(InspectionScoring.ItemStatus.NOT_EVALUATED),
                evaluation(InspectionScoring.ItemStatus.REVIEW_REQUIRED), evaluation(InspectionScoring.ItemStatus.ERROR));
        var item = service.report(List.of("a")).groups().getFirst().items().getFirst();
        assertThat(item.name()).isEqualTo("问候");
        assertThat(item.scored()).isTrue();
        var summary = item.machine();
        assertThat(summary.resultCount()).isEqualTo(6);
        assertThat(List.of(summary.passCount(), summary.failCount(), summary.notApplicableCount(),
                summary.notEvaluatedCount(), summary.reviewRequiredCount(), summary.errorCount())).containsOnly(1);
        assertThat(summary.failureRate()).isEqualByComparingTo("50");
    }

    @Test
    void unscoredItemsRemainVisibleEvenWithoutResults() throws Exception {
        var task = task("a", 90, "[\"c\"]");
        var mapper = new ObjectMapper();
        var snapshot = mapper.readTree(task.getRuleSnapshotJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot.path("schemeSnapshot").path("release").path("definition").path("scoring"))
                .putArray("items");
        task.setRuleSnapshotJson(snapshot.toString());
        var item = service.report(List.of("a")).groups().getFirst().items().getFirst();
        assertThat(item.scored()).isFalse();
        assertThat(item.machine().resultCount()).isZero();
        assertThat(item.machine().failureRate()).isNull();
    }

    @Test
    void rejectsMoreThanFiveHundredTaskConversationsBeforeResultReads() throws Exception {
        task("a", 60, new ObjectMapper().writeValueAsString(java.util.stream.IntStream.range(0, 501).mapToObj(i -> "c" + i).toList()));
        assertThatThrownBy(() -> service.report(List.of("a"))).hasMessageContaining("500");
        verifyNoInteractions(results, reviews);
    }

    @Test
    void rejectsEmptyOversizedAndLegacySelectionsBeforeResultReads() {
        assertThatThrownBy(() -> service.report(List.of())).hasMessageContaining("1–20");
        assertThatThrownBy(() -> service.report(java.util.Collections.nCopies(21, "a"))).hasMessageContaining("1–20");
        var task = task("a", 60, "[\"c\"]"); task.setRuleSnapshotJson("{}");
        assertThatThrownBy(() -> service.report(List.of("a"))).hasMessageContaining("旧消息任务");
        verifyNoInteractions(results, reviews);
    }

    @Test
    void explicitExecutionSelectionNeverFallsBackToTheLatestAttempt() {
        var task = task("a", 90, "[\"c\"]");
        var execution = new TaskExecution(); execution.setId("e1"); execution.setTaskId("a");
        when(executions.selectById("e1")).thenReturn(execution);
        var result = new ConversationInspectionResult(); result.setId("r1"); result.setExecutionId("e1");
        when(results.selectForTaskExecutionConversation("a", "e1", "c")).thenReturn(result);
        when(reviews.validatedMachineEvaluation(result, task)).thenReturn(evaluation(InspectionScoring.ItemStatus.FAIL));

        var report = service.report(List.of("a"), "{\"a\":\"e1\"}", "SELECTED_RUNS");

        assertThat(report.runPolicy()).isEqualTo("SELECTED_RUNS");
        assertThat(report.conversationCount()).isEqualTo(1);
        assertThat(report.groups().getFirst().executionIds()).containsExactly("e1");
        assertThat(report.groups().getFirst().machine().averageScore()).isEqualByComparingTo("0");
        verify(results, never()).selectLatestForTaskConversation("a", "c");
    }

    @Test
    void firstAndLatestPoliciesCountARepeatedConversationOnceWithinTheSameFrozenStandard() {
        var firstTask = task("a", 90, "[\"c\"]");
        var latestTask = task("b", 90, "[\"c\"]");
        var firstResult = new ConversationInspectionResult(); firstResult.setId("r1"); firstResult.setConversationId("c"); firstResult.setExecutionId("e1");
        firstResult.setCreatedTime(new java.util.Date(1_000));
        var latestResult = new ConversationInspectionResult(); latestResult.setId("r2"); latestResult.setConversationId("c"); latestResult.setExecutionId("e2");
        latestResult.setCreatedTime(new java.util.Date(2_000));
        when(results.selectPolicyRunsForTaskConversations("a", List.of("c"), false)).thenReturn(List.of(firstResult));
        when(results.selectPolicyRunsForTaskConversations("a", List.of("c"), true)).thenReturn(List.of(firstResult));
        when(results.selectPolicyRunsForTaskConversations("b", List.of("c"), false)).thenReturn(List.of(latestResult));
        when(results.selectPolicyRunsForTaskConversations("b", List.of("c"), true)).thenReturn(List.of(latestResult));
        when(reviews.validatedMachineEvaluation(firstResult, firstTask)).thenReturn(evaluation(InspectionScoring.ItemStatus.FAIL));
        when(reviews.validatedMachineEvaluation(latestResult, latestTask)).thenReturn(evaluation(InspectionScoring.ItemStatus.PASS));

        var first = service.report(List.of("a", "b"), null, "FIRST_PER_CONVERSATION");
        var latest = service.report(List.of("a", "b"), null, "LATEST_PER_CONVERSATION");

        assertThat(first.conversationCount()).isEqualTo(1);
        assertThat(first.groups().getFirst().taskIds()).containsExactly("a");
        assertThat(first.groups().getFirst().machine().averageScore()).isEqualByComparingTo("0");
        assertThat(latest.conversationCount()).isEqualTo(1);
        assertThat(latest.groups().getFirst().taskIds()).containsExactly("b");
        assertThat(latest.groups().getFirst().machine().averageScore()).isEqualByComparingTo("100");
    }

    @Test
    void firstAndLatestPoliciesIncludeRetriesWithinOneTaskAndTrustAttemptOrder() {
        var task = task("a", 90, "[\"c\"]");
        var firstResult = new ConversationInspectionResult(); firstResult.setId("r1"); firstResult.setConversationId("c"); firstResult.setExecutionId("e1");
        firstResult.setCreatedTime(new java.util.Date(2_000));
        var latestResult = new ConversationInspectionResult(); latestResult.setId("r2"); latestResult.setConversationId("c"); latestResult.setExecutionId("e2");
        latestResult.setCreatedTime(new java.util.Date(1_000));
        when(results.selectPolicyRunsForTaskConversations("a", List.of("c"), false)).thenReturn(List.of(firstResult));
        when(results.selectPolicyRunsForTaskConversations("a", List.of("c"), true)).thenReturn(List.of(latestResult));
        when(reviews.validatedMachineEvaluation(firstResult, task)).thenReturn(evaluation(InspectionScoring.ItemStatus.FAIL));
        when(reviews.validatedMachineEvaluation(latestResult, task)).thenReturn(evaluation(InspectionScoring.ItemStatus.PASS));

        var first = service.report(List.of("a"), null, "FIRST_PER_CONVERSATION");
        var latest = service.report(List.of("a"), null, "LATEST_PER_CONVERSATION");

        assertThat(first.conversationCount()).isEqualTo(1);
        assertThat(first.groups().getFirst().executionIds()).containsExactly("e1");
        assertThat(first.groups().getFirst().machine().averageScore()).isEqualByComparingTo("0");
        assertThat(latest.conversationCount()).isEqualTo(1);
        assertThat(latest.groups().getFirst().executionIds()).containsExactly("e2");
        assertThat(latest.groups().getFirst().machine().averageScore()).isEqualByComparingTo("100");
    }

    @Test
    void runTrendsRejectAnExactExecutionSelectorToKeepThePolicyUnambiguous() {
        task("a", 90, "[\"c\"]");
        assertThatThrownBy(() -> service.report(List.of("a"), "{\"a\":\"e1\"}", "LATEST_PER_CONVERSATION"))
                .hasMessageContaining("不能同时指定单个执行实例");
        verifyNoInteractions(results, reviews);
    }

    @Test
    void rejectsAnExecutionThatBelongsToAnotherTaskBeforeReadingResults() {
        task("a", 90, "[\"c\"]");
        var execution = new TaskExecution(); execution.setId("e1"); execution.setTaskId("other");
        when(executions.selectById("e1")).thenReturn(execution);

        assertThatThrownBy(() -> service.report(List.of("a"), "{\"a\":\"e1\"}", "SELECTED_RUNS"))
                .hasMessageContaining("不属于对应任务");
        verifyNoInteractions(results, reviews);
    }
}
