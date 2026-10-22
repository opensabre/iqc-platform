package io.github.opensabre.iqc.quality.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.pipeline.PipelineDefinition;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.quality.dao.EvaluationSampleResultMapper;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises real report assembly; prepared frozen-input admission and database reads are isolated. */
class PipelineEvaluationReportTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final io.github.opensabre.iqc.pipeline.PipelineRuntime runtime = mock(io.github.opensabre.iqc.pipeline.PipelineRuntime.class);
    private final io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator evaluator = new io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator(runtime, json, null);
    private final InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
    private final TaskExecutionMapper executions = mock(TaskExecutionMapper.class);
    private final EvaluationSampleResultMapper results = mock(EvaluationSampleResultMapper.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final PipelineEvaluationService service = spy(new PipelineEvaluationService(null, null, null,
            tasks, executions, null, results, evaluator, json, scope));
    private final PipelineEvaluationService.Prepared prepared = mock(PipelineEvaluationService.Prepared.class);
    private final TaskExecution execution = new TaskExecution();
    private boolean exactProtocol;

    @BeforeEach
    void frozenDatasetWithoutArtifacts() {
        var task = new InspectionTask(); task.setId("task"); task.setTaskType("PIPELINE_EVALUATION");
        task.setRuleSnapshotJson("{}"); task.setCurrentExecutionId("execution"); task.setCreatedBy("owner");
        when(tasks.selectById("task")).thenReturn(task);
        when(scope.canView(any(), any())).thenReturn(true);
        execution.setId("execution"); execution.setTaskId("task"); execution.setAttemptNo(1); execution.setStatus("FAILED");
        when(executions.selectById("execution")).thenReturn(execution);
        when(executions.selectList(any())).thenAnswer(call -> new ArrayList<TaskExecution>());
        when(results.selectList(any())).thenReturn(List.of());
        doReturn(prepared).when(service).prepare(eq(task), any(), eq(false));
        when(prepared.dataset()).thenReturn(dataset(DatasetDraft.Type.REGRESSION));
        when(prepared.datasetHash()).thenReturn("frozen-dataset");
        var release = mock(PipelineRelease.class);
        when(release.pipeline()).thenReturn(new PipelineDefinition(PipelineDefinition.SCHEMA,
                List.of(new PipelineDefinition.Node("llm", "LLM", "model", "v1", List.of(), Map.of(), null,
                        null, 1000, 0, PipelineDefinition.OnError.FAIL_PIPELINE, null, null, true))));
        when(prepared.release()).thenReturn(release);
    }
    private DatasetSnapshot dataset(DatasetDraft.Type type) {
        return new DatasetSnapshot(DatasetSnapshot.SCHEMA, "dataset", "Gold", type, null,
                List.of(member("first"), member("second")));
    }
    private DatasetSnapshot.Member member(String id) {
        var gold = new GoldConversation(GoldConversation.SCHEMA, id, "conversation-" + id,
                List.of(new GoldConversation.Message("message-" + id, "agent", "text", 1, null)), null,
                List.of(new GoldFinding("REDLINE", Finding.ValueType.BOOLEAN, Finding.State.KNOWN,
                        json.valueToTree(true), List.of())), "annotator", "reviewer", "candidate", List.of(),
                GoldConversation.Difficulty.BASIC,
                new GoldDecision(new BigDecimal("80"), GoldDecision.ScoreStatus.FINAL,
                        Map.of("intent", json.valueToTree("sales")), GoldDecision.Risk.HIGH));
        return new DatasetSnapshot.Member(id, 1, 1, "a".repeat(64), gold);
    }
    @Test
    void missingArtifactsRetainEveryGoldDenominatorAndNeverPassRegression() {
        var report = service.report("task", "execution");
        assertThat(report.expectedSamples()).isEqualTo(2);
        assertThat(report.availableSamples()).isZero(); assertThat(report.successfulSamples()).isZero();
        assertThat(report.complete()).isFalse();
        assertThat(report.missingSampleIds()).containsExactly("first", "second");
        assertThat(report.sourceExecutionIds()).isEmpty();
        assertThat(report.findingMetrics().aggregate().counts().falseNegative()).isEqualTo(2);
        assertThat(report.findingMetrics().aggregate().recall()).isEqualByComparingTo("0");
        assertThat(report.regressionMetrics().expectedSamples()).isEqualTo(2);
        assertThat(report.regressionMetrics().passRate()).isEqualByComparingTo("0");
        assertThat(report.regressionMetrics().failedSampleIds()).containsExactly("first", "second");
        assertThat(report.businessMetrics().scoreGold()).isEqualTo(2);
        assertThat(report.businessMetrics().comparableScores()).isZero();
        assertThat(report.businessMetrics().scoreCoverage()).isEqualByComparingTo("0");
        assertThat(report.businessMetrics().meanAbsoluteScoreError()).isNull();
        assertThat(report.businessMetrics().highRiskGold()).isEqualTo(2);
        assertThat(report.businessMetrics().highRiskRecall()).isEqualByComparingTo("0");
        assertThat(report.businessMetrics().labelGold()).isEqualTo(2);
        assertThat(report.businessMetrics().labelAccuracy()).isNull();
    }
    @Test
    void noMeasuredCallsOrDurationsCannotProducePerfectSuccessOrZeroLatency() {
        var report = service.report("task", null);
        assertThat(report.executionId()).isEqualTo("execution");
        assertThat(report.structuredMetrics().declaredLlmNodes()).isEqualTo(1);
        assertThat(report.structuredMetrics().logicalInvocations()).isZero();
        assertThat(report.structuredMetrics().successRate()).isNull();
        assertThat(report.latencyMetrics().measuredSamples()).isZero();
        assertThat(report.latencyMetrics().p50Ms()).isNull(); assertThat(report.latencyMetrics().p95Ms()).isNull();
        assertThat(report.latencyMetrics().executionFailureRate()).isNull();
    }
    @Test
    void basicDatasetDoesNotInventRegressionPassRate() {
        when(prepared.dataset()).thenReturn(dataset(DatasetDraft.Type.BASIC));
        var report = service.report("task", "execution");
        assertThat(report.regressionMetrics().expectedSamples()).isZero();
        assertThat(report.regressionMetrics().passRate()).isNull();
        assertThat(report.regressionMetrics().failedSampleIds()).isEmpty();
    }
    @Test
    void reportRequiresVisibleEvaluationTaskAndAnExecutionOwnedByIt() {
        when(scope.canView(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.report("task", "execution")).hasMessageContaining("无权查看");
        when(scope.canView(any(), any())).thenReturn(true);
        execution.setTaskId("foreign");
        assertThatThrownBy(() -> service.report("task", "execution")).hasMessageContaining("轮次不存在");
        verifyNoInteractions(results);
    }
    private io.github.opensabre.iqc.quality.model.EvaluationSampleResult artifact(String sampleId, String eid, long duration) throws Exception {
        return artifact(sampleId, eid, duration, false);
    }
    private io.github.opensabre.iqc.quality.model.EvaluationSampleResult artifact(String sampleId, String eid, long duration, boolean failed) throws Exception {
        var release = json.readValue("""
                {"schemaVersion":"iqc-pipeline-release-v1",
                 "pipeline":{"schemaVersion":"iqc-pipeline-v1","nodes":[
                  {"nodeId":"llm","type":"LLM","version":"1","enabled":true,
                   "config":{"findingDefinition":{"schemaVersion":"iqc-finding-definition-v1","name":"Redline","code":"REDLINE","category":"risk","valueType":"BOOLEAN","defaultSeverity":"HIGH","evidenceRequired":true,"evidenceTypes":["CONVERSATION"]},"ruleSnapshot":{"id":"rule","versionNo":1}}}]},
                 "fusion":{"id":"fusion","version":1,"method":"PRIORITY"},
                 "items":[{"itemCode":"redline","findingCode":"REDLINE","hitMeaning":"VIOLATION"}],
                 "scoring":{"version":"iqc-score-v2","mode":"DEDUCTION","baseScore":100,"passingScore":80,
                  "items":[{"itemCode":"redline","points":20,"veto":false}]}}
                """.replace("iqc-pipeline-release-v1", exactProtocol ? PipelineRelease.EXACT_SCHEMA : PipelineRelease.SCHEMA), PipelineRelease.class);
        when(prepared.release()).thenReturn(release);
        var gold = prepared.dataset().members().stream().filter(m -> sampleId.equals(m.sampleId())).findFirst().orElseThrow();
        when(prepared.member(gold.gold().conversationId())).thenReturn(gold);
        var message = new io.github.opensabre.iqc.conversation.model.ConversationMessage();
        message.setId("message-" + sampleId); message.setConversationId(gold.gold().conversationId());
        message.setSpeakerRole("agent"); message.setContent("text"); message.setSequenceNo(1);
        var identity = eid + ":" + gold.gold().conversationId() + ":llm:1";
        var fact = new Finding(io.github.opensabre.iqc.pipelineasset.PipelineAssetService.hash(json.valueToTree(identity).toString()),
                "REDLINE", "risk", Finding.ValueType.BOOLEAN, json.valueToTree(true), Finding.State.KNOWN, null,
                Finding.Severity.HIGH, "redline", List.of(new Finding.Evidence("evidence", Finding.EvidenceType.CONVERSATION,
                message.getId(), null)), new Finding.Source("llm", "LLM", "1", 1, null));
        var metrics = json.createObjectNode().put("schemaVersion", "iqc-llm-structured-metrics-v1")
                .put("logicalInvocations", 1).put("validOutputs", failed ? 0 : 1).put("invalidOrFailedOutputs", failed ? 1 : 0);
        if (exactProtocol) metrics.put("numericAudit",new BigDecimal("0.900000000000000001"));
        var output = new io.github.opensabre.iqc.pipeline.NodeResult(failed ? io.github.opensabre.iqc.pipeline.NodeResult.Status.ERROR : io.github.opensabre.iqc.pipeline.NodeResult.Status.SUCCESS,
                failed ? List.of() : List.of(fact), Map.of(), Map.of("llmStructuredOutput", metrics), failed ? "model failed" : null);
        var run = new io.github.opensabre.iqc.pipeline.PipelineRuntime.Run(failed ? io.github.opensabre.iqc.pipeline.PipelineRuntime.Status.FAILED : io.github.opensabre.iqc.pipeline.PipelineRuntime.Status.SUCCESS,
                List.of(new io.github.opensabre.iqc.pipeline.PipelineRuntime.Trace("llm", 1, java.time.Instant.EPOCH, duration, output)),
                failed ? List.of() : List.of(fact), Map.of(), false);
        when(runtime.execute(any(), any(), any(), any(), any())).thenReturn(run);
        var task = new InspectionTask(); task.setId("task"); task.setCurrentExecutionId(eid);
        var actual = evaluator.evaluate(task, release, List.of(message), io.github.opensabre.iqc.pipeline.PipelineRuntime.Mode.EVALUATION, gold.gold().businessContext());
        var row = new io.github.opensabre.iqc.quality.model.EvaluationSampleResult();
        row.setTaskId("task"); row.setExecutionId(eid); row.setConversationId(gold.gold().conversationId());
        row.setSampleId(sampleId); row.setSampleVersion(1); row.setGoldHash(gold.contentHash());
        row.setDatasetId("dataset"); row.setDatasetVersion(1); row.setDatasetHash("frozen-dataset");
        row.setStatus(failed ? "ERROR" : "SUCCESS"); row.setDurationMs(duration); row.setActualJson(json.writeValueAsString(actual));
        row.setActualHash(exactProtocol ? io.github.opensabre.iqc.selector.SelectorAssetService.hash(row.getActualJson())
                : io.github.opensabre.iqc.pipelineasset.PipelineAssetService.hash(row.getActualJson()));
        return row;
    }
    @Test
    void exactStoredEvaluationReportUsesFrozenInputAndTraceProtocol() throws Exception {
        exactProtocol=true;
        var original=dataset(DatasetDraft.Type.REGRESSION);
        var members=original.members().stream().map(member -> {
            var g=member.gold();
            var exactGold=new GoldConversation(GoldConversation.EXACT_SCHEMA,g.sourceSampleId(),g.conversationId(),g.messages(),
                    json.createObjectNode().put("amount",new BigDecimal("0.900000000000000001")),g.findings(),
                    g.annotatorId(),g.reviewerId(),g.source(),g.tags(),g.difficulty(),g.decision());
            return new DatasetSnapshot.Member(member.sampleId(),member.versionNo(),member.draftRevision(),member.contentHash(),exactGold);
        }).toList();
        when(prepared.dataset()).thenReturn(new DatasetSnapshot(DatasetSnapshot.EXACT_SCHEMA,original.datasetId(),original.name(),original.type(),original.description(),members));
        artifactSnapshot();
        var row=artifact("first","execution",10);
        when(results.selectList(any())).thenReturn(List.of(row));
        assertThat(service.report("task","execution").availableSamples()).isEqualTo(1);
        var altered=row.getActualJson().replace("0.900000000000000001","0.900000000000000002");
        assertThat(io.github.opensabre.iqc.selector.SelectorAssetService.hash(altered)).isNotEqualTo(row.getActualHash());
        row.setActualJson(altered);
        assertThatThrownBy(() -> service.report("task","execution")).hasMessageContaining("摘要无效");
    }

    @Test
    void protocolSpecificTraceReadRetainsSnapshotBounds() throws Exception {
        artifactSnapshot();
        var row=artifact("first","execution",10);
        row.setActualJson(null);
        when(results.selectList(any())).thenReturn(List.of(row));
        assertThatThrownBy(() -> service.report("task","execution")).hasMessageContaining("为空或超过16MiB");
    }

    private void artifactSnapshot() {
        var task = tasks.selectById("task");
        task.setRuleSnapshotJson("{\"evaluationSnapshot\":{\"versionNo\":1}}");
        execution.setStatus("SUCCEEDED");
    }
    @Test
    void realArtifactsAggregateScoresRiskLatencyAndStructuredCallsWithoutInventingLabelOutput() throws Exception {
        artifactSnapshot();
        var first = artifact("first", "execution", 10); var second = artifact("second", "execution", 100);
        when(results.selectList(any())).thenReturn(List.of(first, second));
        var report = service.report("task", "execution");
        assertThat(report.complete()).isTrue(); assertThat(report.successfulSamples()).isEqualTo(2);
        assertThat(report.businessMetrics().meanAbsoluteScoreError()).isEqualByComparingTo("0");
        assertThat(report.businessMetrics().highRiskRecall()).isEqualByComparingTo("1");
        assertThat(report.latencyMetrics().p50Ms()).isEqualTo(10); assertThat(report.latencyMetrics().p95Ms()).isEqualTo(100);
        assertThat(report.structuredMetrics().logicalInvocations()).isEqualTo(2);
        assertThat(report.structuredMetrics().successRate()).isEqualByComparingTo("1");
        assertThat(report.businessMetrics().labelAccuracy()).isNull();
        assertThat(report.regressionMetrics().passRate()).isEqualByComparingTo("0"); // Label output is not implemented.
    }
    @Test
    void retryReportRetainsEarlierSampleWithItsOriginalExecutionSource() throws Exception {
        artifactSnapshot(); execution.setAttemptNo(2);
        var prior = new TaskExecution(); prior.setId("prior"); prior.setTaskId("task"); prior.setStatus("PARTIAL_FAILED"); prior.setAttemptNo(1);
        when(executions.selectById("prior")).thenReturn(prior);
        when(executions.selectList(any())).thenAnswer(call -> new ArrayList<>(List.of(prior)));
        var first = artifact("first", "prior", 10); var second = artifact("second", "execution", 100);
        when(results.selectList(any())).thenReturn(List.of(first), List.of(second));
        var report = service.report("task", "execution");
        assertThat(report.sourceExecutionIds()).containsEntry("first", "prior").containsEntry("second", "execution");
        assertThat(report.complete()).isTrue(); assertThat(report.availableSamples()).isEqualTo(2);
    }
    @Test
    void rejectsChangedArtifactDigestAndForeignFactIdentity() throws Exception {
        artifactSnapshot();
        var row = artifact("first", "execution", 10);
        row.setActualHash("tampered"); when(results.selectList(any())).thenReturn(List.of(row));
        assertThatThrownBy(() -> service.report("task", "execution")).hasMessageContaining("摘要无效");
        var foreign = artifact("first", "foreign", 10); foreign.setExecutionId("execution");
        when(results.selectList(any())).thenReturn(List.of(foreign));
        assertThatThrownBy(() -> service.report("task", "execution")).hasMessageContaining("该执行轮次");
    }

    private void structuredMetrics(io.github.opensabre.iqc.quality.model.EvaluationSampleResult row,
            com.fasterxml.jackson.databind.JsonNode metrics) throws Exception {
        var actual = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(row.getActualJson());
        var target = (com.fasterxml.jackson.databind.node.ObjectNode) actual.at("/run/trace/0/result/metrics");
        if (metrics == null) target.remove("llmStructuredOutput");
        else target.set("llmStructuredOutput", metrics);
        row.setActualJson(actual.toString());
        row.setActualHash(io.github.opensabre.iqc.pipelineasset.PipelineAssetService.hash(row.getActualJson()));
    }
    private com.fasterxml.jackson.databind.node.ObjectNode counters(int calls, int valid, int failed) {
        return json.createObjectNode().put("schemaVersion", "iqc-llm-structured-metrics-v1")
                .put("logicalInvocations", calls).put("validOutputs", valid).put("invalidOrFailedOutputs", failed);
    }
    @Test
    void structuredSuccessUsesInvocationCountsRatherThanAverageOfSampleRates() throws Exception {
        artifactSnapshot();
        var first = artifact("first", "execution", 10); var second = artifact("second", "execution", 100);
        structuredMetrics(first, counters(3, 2, 1));
        when(results.selectList(any())).thenReturn(List.of(first, second));
        var metrics = service.report("task", "execution").structuredMetrics();
        assertThat(metrics.logicalInvocations()).isEqualTo(4);
        assertThat(metrics.validOutputs()).isEqualTo(3);
        assertThat(metrics.invalidOrFailedOutputs()).isEqualTo(1);
        assertThat(metrics.successRate()).isEqualByComparingTo("0.75");
    }
    @Test
    void oneUnmeasuredAttemptPreventsMeasuredCallsFromClaimingCompleteSuccess() throws Exception {
        artifactSnapshot();
        var first = artifact("first", "execution", 10); var second = artifact("second", "execution", 100);
        structuredMetrics(second, null);
        when(results.selectList(any())).thenReturn(List.of(first, second));
        var metrics = service.report("task", "execution").structuredMetrics();
        assertThat(metrics.logicalInvocations()).isEqualTo(1);
        assertThat(metrics.unmeasuredAttempts()).isEqualTo(1);
        assertThat(metrics.successRate()).isNull();
    }
    @Test
    void rejectsMalformedStructuredCountsEvenWithValidArtifactDigest() throws Exception {
        artifactSnapshot();
        var row = artifact("first", "execution", 10);
        when(results.selectList(any())).thenReturn(List.of(row));
        for (var metrics : List.of(counters(0, 0, 0), counters(1, 2, -1), counters(2, 1, 0),
                counters(10001, 10001, 0), counters(1, 1, 0).put("validOutputs", "1"),
                counters(1, 1, 0).put("schemaVersion", "future"))) {
            structuredMetrics(row, metrics);
            assertThatThrownBy(() -> service.report("task", "execution")).hasMessageContaining("结构化指标");
        }
    }

    @Test
    void failedModelRemainsErrorAndContributesToFullGoldAndInvocationDenominators() throws Exception {
        artifactSnapshot(); execution.setStatus("PARTIAL_FAILED");
        var first = artifact("first", "execution", 10);
        var second = artifact("second", "execution", 100, true);
        when(results.selectList(any())).thenReturn(List.of(first, second));
        var report = service.report("task", "execution");
        assertThat(report.complete()).isTrue(); // Complete coverage is distinct from successful execution.
        assertThat(report.availableSamples()).isEqualTo(2); assertThat(report.successfulSamples()).isEqualTo(1);
        assertThat(report.findingMetrics().aggregate().counts().errors()).isEqualTo(1);
        assertThat(report.findingMetrics().aggregate().counts().falseNegative()).isEqualTo(1);
        assertThat(report.businessMetrics().scoreCoverage()).isEqualByComparingTo("0.5");
        assertThat(report.businessMetrics().highRiskRecall()).isEqualByComparingTo("0.5");
        assertThat(report.latencyMetrics().executionFailureRate()).isEqualByComparingTo("0.5");
        assertThat(report.structuredMetrics().successRate()).isEqualByComparingTo("0.5");
        assertThat(report.regressionMetrics().failedSampleIds()).contains("second");
    }
    @Test
    void cancelledRoundDoesNotClaimCompleteCoverageOrEraseItsPersistedFailure() throws Exception {
        artifactSnapshot(); execution.setStatus("CANCELLED");
        var row = artifact("first", "execution", 10, true);
        when(results.selectList(any())).thenReturn(List.of(row));
        var report = service.report("task", "execution");
        assertThat(report.complete()).isFalse(); assertThat(report.successfulSamples()).isZero();
        assertThat(report.missingSampleIds()).containsExactly("second");
        assertThat(report.latencyMetrics().executionFailureRate()).isEqualByComparingTo("1");
        assertThat(report.regressionMetrics().passRate()).isEqualByComparingTo("0");
    }

    @Test void consistencyRequiresExplicitExecutionIdentitiesBeforeReadingArtifacts(){
        assertThatThrownBy(()->service.consistency("task",null,"task","execution"))
                .hasMessageContaining("双方Task与执行轮次");
        verifyNoInteractions(results);
    }
    @Test void consistencyAuthorizesReferenceTaskBeforeReadingEitherReport(){
        var forbidden=new InspectionTask();forbidden.setId("forbidden");forbidden.setTaskType("PIPELINE_EVALUATION");forbidden.setCreatedBy("outsider");
        when(tasks.selectById("forbidden")).thenReturn(forbidden);
        when(scope.canView(eq("outsider"),any())).thenReturn(false);
        assertThatThrownBy(()->service.consistency("task","execution","forbidden","other"))
                .hasMessageContaining("无权查看评测任务");
        verifyNoInteractions(results);
    }
    @Test void consistencyRetainsMissingSampleAndNodeDenominator() {
        var task=tasks.selectById("task");
        task.setRuleSnapshotJson("{\"pipelineSnapshot\":{\"contentHash\":\""+"a".repeat(64)+"\"}}");
        var result=service.consistency("task","execution","task","execution");
        assertThat(result.expectedPairs()).isEqualTo(2);assertThat(result.comparablePairs()).isZero();
        assertThat(result.agreementRate()).isNull();
        assertThat(result.excludedReasons()).containsEntry("MISSING_ARTIFACT",2);
    }

    @Test void consistencyUsesInheritedArtifactIdentityInsteadOfRequestedRetryExecution() throws Exception {
        artifactSnapshot();execution.setAttemptNo(2);
        tasks.selectById("task").setRuleSnapshotJson("{\"evaluationSnapshot\":{\"versionNo\":1},\"pipelineSnapshot\":{\"contentHash\":\""+"a".repeat(64)+"\"}}");
        var prior=new TaskExecution();prior.setId("prior");prior.setTaskId("task");
        prior.setStatus("PARTIAL_FAILED");prior.setAttemptNo(1);
        when(executions.selectById("prior")).thenReturn(prior);
        when(executions.selectList(any())).thenAnswer(call->new ArrayList<>(List.of(prior)))
                .thenAnswer(call->new ArrayList<TaskExecution>());
        var inherited=artifact("first","prior",10);
        when(results.selectList(any())).thenReturn(List.of(inherited),List.of(),List.of(inherited));
        when(results.selectOne(any())).thenReturn(inherited);
        var comparison=service.consistency("task","execution","task","prior");
        assertThat(comparison.expectedPairs()).isEqualTo(2);
        assertThat(comparison.comparablePairs()).isZero();assertThat(comparison.agreementRate()).isNull();
        assertThat(comparison.excludedReasons()).containsOnly(
                org.assertj.core.api.Assertions.entry("SAME_ARTIFACT",1),
                org.assertj.core.api.Assertions.entry("MISSING_ARTIFACT",1));
        verify(results,times(2)).selectOne(any());
    }

    @Test void consistencyRejectsArtifactDisappearingAfterReportValidation() throws Exception {
        artifactSnapshot();
        tasks.selectById("task").setRuleSnapshotJson("{\"evaluationSnapshot\":{\"versionNo\":1},\"pipelineSnapshot\":{\"contentHash\":\""+"a".repeat(64)+"\"}}");
        var row=artifact("first","execution",10);
        when(results.selectList(any())).thenReturn(List.of(row));
        when(results.selectOne(any())).thenReturn(null);
        assertThatThrownBy(()->service.consistency("task","execution","task","execution"))
                .hasMessageContaining("已校验产物已消失");
    }

    @Test void consistencyRejectsChangedFrozenPipelineBeforeArtifactReads(){
        var selected=tasks.selectById("task");
        selected.setRuleSnapshotJson("{\"pipelineSnapshot\":{\"contentHash\":\""+"a".repeat(64)+"\"}}");
        var reference=new InspectionTask();reference.setId("reference");reference.setTaskType("PIPELINE_EVALUATION");reference.setCreatedBy("owner");
        reference.setRuleSnapshotJson("{\"pipelineSnapshot\":{\"contentHash\":\""+"b".repeat(64)+"\"}}");
        when(tasks.selectById("reference")).thenReturn(reference);doReturn(prepared).when(service).prepare(eq(reference),any(),eq(false));
        assertThatThrownBy(()->service.consistency("task","execution","reference","other"))
                .hasMessageContaining("相同冻结数据集与流水线版本");
        verifyNoInteractions(results);
    }

}
