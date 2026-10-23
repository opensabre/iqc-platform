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
        when(projection.original()).thenReturn(evaluation(InspectionScoring.ItemStatus.FAIL));
        when(projection.findingReview()).thenReturn(null);
        when(projection.reviewed()).thenReturn(evaluation(InspectionScoring.ItemStatus.PASS));
        when(reviews.validatedProjection(completed, result, task)).thenReturn(projection);
        var group = service.report(List.of("a")).groups().getFirst();
        assertThat(group.machine().averageScore()).isEqualByComparingTo("0");
        assertThat(group.reviewed().averageScore()).isEqualByComparingTo("100");
        assertThat(group.pendingReviewCount()).isEqualTo(1);
        assertThat(group.missingReviewCount()).isZero();
        assertThat(group.reviewChanges().reviewedResults()).isEqualTo(1);
        assertThat(group.reviewChanges().modifiedResults()).isEqualTo(1);
        assertThat(group.reviewChanges().modificationRate()).isEqualByComparingTo("1");
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
    @Test void manualOverrideAveragesUseOnlyAppliedScoresAndExcludePendingNumericResults() {
        task("a",90,"[\"c1\",\"c2\",\"c3\"]");
        var result=new ConversationInspectionResult();result.setId("r");
        when(results.selectLatestForTaskConversation(any(),any())).thenReturn(result);
        var complete=new io.github.opensabre.iqc.quality.model.ResultReview();complete.setStatus("COMPLETED");
        when(reviews.latest(any(),eq(true))).thenReturn(complete);
        var pass=evaluation(InspectionScoring.ItemStatus.PASS);var pending=evaluation(InspectionScoring.ItemStatus.ERROR);
        when(reviews.validatedMachineEvaluation(any(),any())).thenReturn(pass);
        var json=new ObjectMapper();
        var adjustment=new BusinessItemReviewEvaluator.ScoreAdjustment("r",BusinessItemReviewEvaluator.scoringHash("r",pass.scoring(),json),"90.25","reason");
        var applied=BusinessItemReviewEvaluator.manualScore("r",pass.scoring(),adjustment,true,json);
        var old=score(InspectionScoring.ItemStatus.FAIL);
        var previous=new BusinessItemReviewEvaluator.ScoreAdjustment("r",BusinessItemReviewEvaluator.scoringHash("r",old,json),"99","old reason");
        var stale=BusinessItemReviewEvaluator.manualScore("r",pass.scoring(),previous,false,json);
        when(reviews.validatedProjection(any(),any(),any())).thenReturn(
                new BusinessItemReviewEvaluator.Projection("r",pass,pass,List.of(),null,applied),
                new BusinessItemReviewEvaluator.Projection("r",pass,pass,List.of(),null,stale),
                new BusinessItemReviewEvaluator.Projection("r",pass,pending,List.of()));
        var group=service.report(List.of("a")).groups().getFirst();
        assertThat(group.reviewed().averageScore()).isEqualByComparingTo("100");
        assertThat(group.reviewed().pendingCount()).isEqualTo(1);
        var manual=group.reviewed().manualOverrides();
        assertThat(group.reviewChanges().reviewedResults()).isEqualTo(3);
        assertThat(group.reviewChanges().modifiedResults()).isEqualTo(2);
        assertThat(group.reviewChanges().effectiveNumericScoreChangedResults()).isEqualTo(1);
        assertThat(group.reviewChanges().modificationRate()).isEqualByComparingTo("0.666667");
        assertThat(manual.appliedCount()).isEqualTo(1);assertThat(manual.staleCount()).isEqualTo(1);
        assertThat(manual.averageOverrideScore()).isEqualByComparingTo("90.25");
        assertThat(manual.effectiveNumericCount()).isEqualTo(2);assertThat(manual.averageEffectiveScore()).isEqualByComparingTo("95.13");
    }

    @Test void pipelineVersionsAndSchemeKeepSeparateReportIdentitiesAndValidateFrozenHash() throws Exception {
        var json=new ObjectMapper();task("scheme",90,"[\"c0\"]");
        var config=json.createObjectNode();
        config.set("findingDefinition",json.valueToTree(new io.github.opensabre.iqc.finding.FindingDefinition(
                io.github.opensabre.iqc.finding.FindingDefinition.SCHEMA,"CHECK","Check","BUSINESS",null,
                io.github.opensabre.iqc.finding.Finding.ValueType.BOOLEAN,List.of(),io.github.opensabre.iqc.finding.Finding.Severity.LOW,false,List.of())));
        var node=new io.github.opensabre.iqc.pipeline.PipelineDefinition.Node("node","RULE","Check","v1",List.of(),java.util.Map.of(),config,null,1000,0,
                io.github.opensabre.iqc.pipeline.PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true);
        var release=new io.github.opensabre.iqc.pipelineasset.PipelineRelease(io.github.opensabre.iqc.pipelineasset.PipelineRelease.SCHEMA,
                new io.github.opensabre.iqc.pipeline.PipelineDefinition(io.github.opensabre.iqc.pipeline.PipelineDefinition.SCHEMA,List.of(node)),
                new io.github.opensabre.iqc.fusion.FusionPolicy("fusion",1,io.github.opensabre.iqc.fusion.FusionPolicy.Method.PRIORITY,java.util.Map.of(),java.util.Map.of(),null,java.util.Set.of()),
                new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,90,List.of(new InspectionScoring.Item("a",100,false))),
                List.of(new io.github.opensabre.iqc.pipelineasset.PipelineRelease.ItemMapping("a","CHECK",io.github.opensabre.iqc.scheme.SchemeDefinition.HitMeaning.VIOLATION)));
        var validator=new BusinessItemReviewService(null,null,null,null,null,json);
        when(reviews.pipelineRelease(any())).thenAnswer(call->validator.pipelineRelease(call.getArgument(0)));
        for(int version:List.of(1,2)){
            var pipeline=task("pipe"+version,90,"[\"c"+version+"\"]");var snapshot=json.createObjectNode();
            var marker=snapshot.putObject("pipelineSnapshot").put("pipelineId","pipeline").put("versionNo",version);
            marker.set("release",json.valueToTree(release));marker.put("contentHash",io.github.opensabre.iqc.pipelineasset.PipelineAssetService.hash(json.writeValueAsString(release)));
            pipeline.setRuleSnapshotJson(snapshot.toString());
        }
        var report=service.report(List.of("scheme","pipe1","pipe2"));
        assertThat(report.groups()).hasSize(3);
        assertThat(report.groups().stream().filter(group->group.pipelineId()!=null).toList()).hasSize(2)
                .allSatisfy(group->{assertThat(group.pipelineId()).isEqualTo("pipeline");assertThat(group.schemeId()).isEmpty();});
        assertThat(report.groups().stream().filter(group->group.pipelineId()!=null).map(BusinessQualityReportService.PolicyGroup::versionNo).toList()).containsExactlyInAnyOrder("1","2");
        var bad=tasks.selectById("pipe1");var broken=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(bad.getRuleSnapshotJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode)broken.path("pipelineSnapshot")).put("contentHash","wrong");bad.setRuleSnapshotJson(broken.toString());
        assertThatThrownBy(()->service.report(List.of("pipe1"))).hasMessageContaining("快照摘要不一致");
    }

    @Test void reviewChangeCoverageDoesNotCountUnreviewedResultsAsUnchanged() {
        task("a",90,"[\"c1\",\"c2\",\"c3\"]");
        var r1=new ConversationInspectionResult();r1.setId("r1");
        var r2=new ConversationInspectionResult();r2.setId("r2");
        when(results.selectLatestForTaskConversation(any(),any())).thenReturn(r1,r2,null);
        var pass=evaluation(InspectionScoring.ItemStatus.PASS);
        when(reviews.validatedMachineEvaluation(any(),any())).thenReturn(pass);
        var complete=new io.github.opensabre.iqc.quality.model.ResultReview();complete.setStatus("COMPLETED");
        when(reviews.latest("r1",true)).thenReturn(complete);
        when(reviews.validatedProjection(any(),any(),any())).thenReturn(new BusinessItemReviewEvaluator.Projection("r1",pass,pass,List.of()));
        var group=service.report(List.of("a")).groups().getFirst();
        assertThat(group.reviewChanges().reviewedResults()).isEqualTo(1);
        assertThat(group.reviewChanges().modifiedResults()).isZero();
        assertThat(group.reviewChanges().modificationRate()).isEqualByComparingTo("0");
        assertThat(group.reviewChanges().reviewCoverage()).isEqualByComparingTo("0.333333");
        assertThat(group.missingResultCount()).isEqualTo(1);
    }

    @Test void noCompletedReviewsHaveNullModificationRateAndLegacyGroupOmitsNewField() throws Exception {
        task("a",90,"[\"c\"]");
        var group=service.report(List.of("a")).groups().getFirst();
        assertThat(group.reviewChanges().reviewedResults()).isZero();
        assertThat(group.reviewChanges().modificationRate()).isNull();
        assertThat(group.reviewChanges().reviewCoverage()).isEqualByComparingTo("0");
        var old=new BusinessQualityReportService.PolicyGroup(group.groupKey(),group.schemeId(),group.versionNo(),
                group.draftRevision(),group.kind(),group.mode(),group.baseScore(),group.passingScore(),group.taskIds(),
                group.conversationCount(),group.executionIds(),group.missingResultCount(),group.missingReviewCount(),
                group.pendingReviewCount(),group.machine(),group.reviewed(),group.items());
        var json=new ObjectMapper();var encoded=json.writeValueAsString(old);
        assertThat(json.readTree(encoded).has("reviewChanges")).isFalse();
        assertThat(json.writeValueAsString(json.readValue(encoded,BusinessQualityReportService.PolicyGroup.class))).isEqualTo(encoded);
    }

    @Test void findingModificationUsesExactValuesAndSemanticEvidenceInsteadOfIdsOrExplanation() throws Exception {
        task("a",90,"[\"c\"]");
        var result=new ConversationInspectionResult();result.setId("r");
        when(results.selectLatestForTaskConversation(any(),any())).thenReturn(result);
        var pass=evaluation(InspectionScoring.ItemStatus.PASS);
        when(reviews.validatedMachineEvaluation(any(),any())).thenReturn(pass);
        var completed=new io.github.opensabre.iqc.quality.model.ResultReview();completed.setStatus("COMPLETED");
        when(reviews.latest("r",true)).thenReturn(completed);
        var json=new ObjectMapper();
        var e1=new io.github.opensabre.iqc.finding.Finding.Evidence("e1",io.github.opensabre.iqc.finding.Finding.EvidenceType.CONVERSATION,"m1",null);
        var e2=new io.github.opensabre.iqc.finding.Finding.Evidence("e2",io.github.opensabre.iqc.finding.Finding.EvidenceType.CONVERSATION,"m2",null);
        var equivalentEvidence=new io.github.opensabre.iqc.finding.Finding.Evidence("human-e1",e1.type(),e1.reference(),json.readTree("{\"explanation\":\"human\"}"));
        var changedEvidence=new io.github.opensabre.iqc.finding.Finding.Evidence("e3",e1.type(),"m3",null);
        var original=reviewMetricFact("1.000000000000000001",io.github.opensabre.iqc.finding.Finding.Severity.LOW,
                io.github.opensabre.iqc.finding.Finding.State.KNOWN,List.of(e1,e2),"machine");
        var before=json.writeValueAsString(original);
        var variants=List.of(
                reviewMetricFact("1.0000000000000000010",original.severity(),original.state(),List.of(e2,equivalentEvidence,equivalentEvidence),"human explanation"),
                reviewMetricFact("1.000000000000000002",original.severity(),original.state(),original.evidence(),"machine"),
                reviewMetricFact("1.000000000000000001",io.github.opensabre.iqc.finding.Finding.Severity.HIGH,original.state(),original.evidence(),"machine"),
                reviewMetricFact(null,original.severity(),io.github.opensabre.iqc.finding.Finding.State.UNKNOWN,original.evidence(),"machine"),
                reviewMetricFact("1.000000000000000001",original.severity(),original.state(),List.of(e1,changedEvidence),"machine"));
        for(int i=0;i<variants.size();i++){
            var findingProjection=new HumanFindingReviewEvaluator.PolicyProjection(
                    new HumanFindingReviewEvaluator.Projection(List.of(original),List.of(variants.get(i)),List.of()),
                    List.of(),pass.scoring(),null,"NONE",java.util.Map.of(),null,"PASS");
            when(reviews.validatedProjection(any(),any(),any())).thenReturn(
                    new BusinessItemReviewEvaluator.Projection("r",pass,pass,List.of(),findingProjection));
            var changes=service.report(List.of("a")).groups().getFirst().reviewChanges();
            assertThat(changes.reviewedResults()).isEqualTo(1);
            assertThat(changes.findingChangedResults()).as("variant %s",i).isEqualTo(i==0?0:1);
            assertThat(changes.modifiedResults()).as("variant %s",i).isEqualTo(i==0?0:1);
            assertThat(changes.itemOrScoreChangedResults()).isZero();
            assertThat(changes.effectiveNumericScoreChangedResults()).isZero();
        }
        assertThat(json.writeValueAsString(original)).isEqualTo(before);
    }

    private io.github.opensabre.iqc.fusion.FindingFusion.Result reviewMetricFact(String number,
            io.github.opensabre.iqc.finding.Finding.Severity severity,io.github.opensabre.iqc.finding.Finding.State state,
            List<io.github.opensabre.iqc.finding.Finding.Evidence> evidence,String explanation){
        return new io.github.opensabre.iqc.fusion.FindingFusion.Result("METRIC",io.github.opensabre.iqc.finding.Finding.ValueType.NUMBER,state,
                number==null?null:com.fasterxml.jackson.databind.node.DecimalNode.valueOf(new java.math.BigDecimal(number)),
                severity,List.of(),List.of(),List.of(),evidence,explanation,"fusion",1);
    }

    private BusinessQualityReportService usageReport(
            io.github.opensabre.iqc.result.llm.gateway.GatewayCallService calls,
            io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive) {
        when(calls.taskRunUsage(anyList())).thenReturn(new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.RunUsage(
                new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.CostSummary("0","0","0",List.of()),
                new io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.CallLatencySummary("0","0","0",null)));
        return new BusinessQualityReportService(tasks,results,reviews,scope,new ObjectMapper(),executions,null,calls,sensitive);
    }
    @Test void modelUsageFollowsOnlyTheFinalFirstOrLatestResultChoice() {
        var calls=mock(io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.class);
        var sensitive=mock(io.github.opensabre.iqc.privacy.SensitiveReadAccessService.class);var reportService=usageReport(calls,sensitive);
        for(int index=1;index<=2;index++){
            String taskId=index==1?"a":"b";var task=task(taskId,90,"[\"c\"]");
            var result=new ConversationInspectionResult();result.setId("r"+index);result.setConversationId("c");
            result.setExecutionId("e"+index);result.setCreatedTime(new java.util.Date(index*1000L));
            when(results.selectPolicyRunsForTaskConversations(eq(taskId),eq(List.of("c")),anyBoolean())).thenReturn(List.of(result));
            when(reviews.validatedMachineEvaluation(result,task)).thenReturn(evaluation(InspectionScoring.ItemStatus.PASS));
        }
        for(boolean latest:List.of(false,true)){
            clearInvocations(calls);
            var report=reportService.report(List.of("a","b"),null,latest?"LATEST_PER_CONVERSATION":"FIRST_PER_CONVERSATION",true);
            assertThat(report.conversationCount()).isOne();
            var usage=report.groups().getFirst().modelUsage();assertThat(usage.selectedResults()).isOne();assertThat(usage.boundResults()).isOne();assertThat(usage.unboundResults()).isZero();
            verify(calls).taskRunUsage(List.of(new io.github.opensabre.iqc.result.llm.gateway.GatewayCallMapper.RunReference(latest?"b":"a",latest?"e2":"e1","c")));
        }
        verify(sensitive,times(2)).current(java.util.Set.of("iqc:report:view","iqc:task:model-calls:view"));
    }
    @Test void modelUsageKeepsMissingResultsAndMissingExecutionIdsUnbound() {
        var calls=mock(io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.class);
        var reportService=usageReport(calls,mock(io.github.opensabre.iqc.privacy.SensitiveReadAccessService.class));
        var task=task("a",90,"[\"c1\",\"c2\",\"c3\"]");
        var bound=new ConversationInspectionResult();bound.setId("r1");bound.setExecutionId("e1");
        var unbound=new ConversationInspectionResult();unbound.setId("r2");
        when(results.selectLatestForTaskConversation("a","c1")).thenReturn(bound);
        when(results.selectLatestForTaskConversation("a","c2")).thenReturn(unbound);
        when(reviews.validatedMachineEvaluation(any(),eq(task))).thenReturn(evaluation(InspectionScoring.ItemStatus.PASS));
        var group=reportService.report(List.of("a"),null,"SELECTED_RUNS",true).groups().getFirst();
        assertThat(group.modelUsage().selectedResults()).isEqualTo(3);assertThat(group.modelUsage().boundResults()).isOne();assertThat(group.modelUsage().unboundResults()).isEqualTo(2);
        assertThat(group.missingResultCount()).isOne();
        verify(calls).taskRunUsage(List.of(new io.github.opensabre.iqc.result.llm.gateway.GatewayCallMapper.RunReference("a","e1","c1")));
    }
    @Test void defaultReportSkipsUsageAndRevocationRejectsBeforeTaskOrResultReads() {
        var calls=mock(io.github.opensabre.iqc.result.llm.gateway.GatewayCallService.class);
        var sensitive=mock(io.github.opensabre.iqc.privacy.SensitiveReadAccessService.class);var reportService=usageReport(calls,sensitive);
        task("a",90,"[\"c\"]");
        assertThat(reportService.report(List.of("a")).groups().getFirst().modelUsage()).isNull();
        verifyNoInteractions(calls,sensitive);clearInvocations(tasks,results,reviews);
        when(sensitive.current(anySet())).thenThrow(io.github.opensabre.iqc.governance.IqcException.accessDenied("revoked"));
        assertThatThrownBy(()->reportService.report(List.of("a"),null,"SELECTED_RUNS",true)).hasMessageContaining("revoked");
        verifyNoInteractions(tasks,results,reviews,calls);
    }

}
