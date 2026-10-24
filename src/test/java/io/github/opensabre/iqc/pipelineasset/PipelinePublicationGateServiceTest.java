package io.github.opensabre.iqc.pipelineasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.quality.evaluation.*;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.model.TaskExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PipelinePublicationGateServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PipelineEvaluationService evaluations = mock(PipelineEvaluationService.class);
    private final TaskExecutionMapper executions = mock(TaskExecutionMapper.class);
    private final PipelineRelease release = mock(PipelineRelease.class);
    private final PipelinePublicationGateService gates = new PipelinePublicationGateService(evaluations, json, executions);
    private final PipelinePublicationGateService.ExecutionEvidence quality = evidence("quality");
    private final PipelinePublicationGateService.ExecutionEvidence regression = evidence("regression");
    private ObjectNode qualityTarget;
    private TaskExecution qualityExecution;

    private static BigDecimal number(String value) { return value == null ? null : new BigDecimal(value); }
    private static PipelinePublicationGateService.ExecutionEvidence evidence(String task) {
        return new PipelinePublicationGateService.ExecutionEvidence(task, task + "-execution");
    }
    @BeforeEach
    void prepare() {
        when(release.fusion()).thenReturn(new io.github.opensabre.iqc.fusion.FusionPolicy("fusion",1,io.github.opensabre.iqc.fusion.FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,java.util.Set.of()));
        qualityTarget = context("quality", DatasetDraft.Type.RISK);
        context("regression", DatasetDraft.Type.REGRESSION);
        qualityExecution = execution("quality");
        execution("regression");
        when(evaluations.report("quality", "quality-execution")).thenReturn(report("quality", 1, 1, 1, true, 1, "0.995", "0.95", "0.92", "1", 5000L));
        when(evaluations.report("regression", "regression-execution")).thenReturn(report("regression", 1, 1, 1, true, 1, "0.995", "0.95", "0.92", "1", 5000L));
    }
    private ObjectNode context(String task, DatasetDraft.Type type) {
        var snapshot = json.createObjectNode();
        var target = snapshot.putObject("pipelineSnapshot");
        target.put("pipelineId", "pipeline").put("contentHash", "frozen-hash").put("draftRevision", 3).put("trial", true);
        snapshot.putObject("evaluationSnapshot").put("versionNo", 2);
        var prepared = mock(PipelineEvaluationService.Prepared.class);
        var dataset = mock(DatasetSnapshot.class);
        when(dataset.type()).thenReturn(type);
        when(dataset.datasetId()).thenReturn(task + "-dataset");
        when(prepared.dataset()).thenReturn(dataset);
        when(evaluations.context(task)).thenReturn(new PipelineEvaluationService.Context(null, snapshot, prepared));
        return target;
    }
    private TaskExecution execution(String task) {
        var execution = new TaskExecution();
        execution.setId(task + "-execution"); execution.setTaskId(task); execution.setStatus("SUCCEEDED");
        when(executions.selectById(task + "-execution")).thenReturn(execution);
        return execution;
    }
    private PipelineEvaluationService.Report report(String task, int expected, int available, int successful,
            boolean complete, int llmNodes, String structured, String risk, String f1, String pass, Long p95) {
        var summary = new FindingEvaluationMetrics.Summary(null, null, null, number(f1), null, null, null, null);
        return new PipelineEvaluationService.Report("iqc-evaluation-report-v1", task, task + "-execution", "dataset-hash",
                expected, available, successful, complete, List.of(), Map.of(),
                new FindingEvaluationMetrics.Result("metrics", expected, summary, Map.of(), 0),
                new PipelineEvaluationService.BusinessMetrics(0, 0, null, null, 1, 1, 1, number(risk), 0, 0, null),
                new PipelineEvaluationService.LatencyMetrics("compute", available, p95, p95, p95, BigDecimal.ZERO),
                new PipelineEvaluationService.RegressionMetrics("exact", expected, successful, number(pass), List.of()),
                new PipelineEvaluationService.StructuredMetrics("logical", llmNodes, available, available, 0, 0, number(structured)));
    }
    private PipelinePublicationGateService.Decision evaluate() {
        return gates.evaluate("pipeline", 3, "frozen-hash", release, quality, regression);
    }
    private PipelinePublicationGateService.Criterion criterion(PipelinePublicationGateService.Decision decision, String purpose, String code) {
        return decision.criteria().stream().filter(c -> purpose.equals(c.purpose()) && code.equals(c.code())).findFirst().orElseThrow();
    }
    @Test
    void passesExactThresholdsAndFreezesBothEvidenceHashes() {
        var decision = evaluate();
        assertThat(decision.passed()).isTrue();
        assertThat(decision.criteria()).allMatch(c -> c.status() == PipelinePublicationGateService.Status.PASSED);
        assertThat(decision.evidence()).hasSize(2).allSatisfy(e -> {
            assertThat(e.reportHash()).isEqualTo(PipelineAssetService.hash(json.valueToTree(e.report()).toString()));
            assertThat(e.datasetVersion()).isEqualTo(2);
        });
        assertThatThrownBy(() -> decision.criteria().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test
    void missingDenominatorsAndUnmeasuredLatencyFailInsteadOfPassing() {
        when(evaluations.report("quality", "quality-execution")).thenReturn(report("quality", 1, 1, 1, true, 1, null, null, null, "1", null));
        var decision = evaluate();
        assertThat(decision.passed()).isFalse();
        for (var code : List.of("HIGH_RISK_RECALL", "OVERALL_F1", "STRUCTURED_SUCCESS", "P95_EVALUATOR_MS"))
            assertThat(criterion(decision, "QUALITY", code).status()).isEqualTo(PipelinePublicationGateService.Status.FAILED);
    }
    @Test
    void noLlmIsExplicitlyNotApplicableAndDoesNotHideOtherFailures() {
        when(evaluations.report("quality", "quality-execution")).thenReturn(report("quality", 1, 1, 1, true, 0, null, "0.94", "0.92", "1", 5000L));
        var decision = evaluate();
        assertThat(criterion(decision, "QUALITY", "STRUCTURED_SUCCESS").status()).isEqualTo(PipelinePublicationGateService.Status.NOT_APPLICABLE);
        assertThat(decision.passed()).isFalse();
    }
    @Test
    void missingAndFailedSamplesAndRegressionMismatchBlockPublication() {
        when(evaluations.report("regression", "regression-execution")).thenReturn(report("regression", 3, 2, 1, false, 1, "0.99", "1", "1", "0.333", 5001L));
        var decision = evaluate();
        assertThat(decision.passed()).isFalse();
        for (var code : List.of("COMPLETE", "EXECUTION_SUCCESS", "REGRESSION_PASS", "STRUCTURED_SUCCESS", "P95_EVALUATOR_MS"))
            assertThat(criterion(decision, "REGRESSION", code).status()).isEqualTo(PipelinePublicationGateService.Status.FAILED);
    }
    @Test
    void rejectsMissingArtifactsEvenBeforeConsideringMetricOverride() {
        when(evaluations.report("quality", "quality-execution")).thenReturn(report("quality", 1, 0, 0, true, 0, null, "1", "1", "1", 1L));
        assertThatThrownBy(this::evaluate).hasMessageContaining("真实评测产物");
    }
    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "PENDING"})
    void rejectsNonterminalEvidence(String status) {
        qualityExecution.setStatus(status);
        assertThatThrownBy(this::evaluate).hasMessageContaining("终态执行轮次");
        verify(evaluations, never()).report(anyString(), anyString());
    }
    @Test
    void rejectsForeignExecutionAndWrongFrozenContent() {
        qualityExecution.setTaskId("foreign");
        assertThatThrownBy(this::evaluate).hasMessageContaining("终态执行轮次");
        qualityExecution.setTaskId("quality");
        qualityTarget.put("contentHash", "changed");
        assertThatThrownBy(this::evaluate).hasMessageContaining("冻结内容");
        qualityTarget.put("contentHash", "frozen-hash").put("pipelineId", "other");
        assertThatThrownBy(this::evaluate).hasMessageContaining("冻结内容");
    }
    @Test
    void draftEvidenceRequiresExactRevisionAndTrialButPublishedEvidenceUsesFrozenHash() {
        qualityTarget.put("draftRevision", 2);
        assertThatThrownBy(this::evaluate).hasMessageContaining("精确修订");
        qualityTarget.put("draftRevision", 3).put("trial", false);
        assertThatThrownBy(this::evaluate).hasMessageContaining("精确修订");
        qualityTarget.put("versionNo", 1);
        assertThat(evaluate().passed()).isTrue();
    }
    @Test
    void regressionMustUseRegressionDatasetAndBothEvidenceReferencesAreRequired() {
        context("regression", DatasetDraft.Type.BASIC);
        assertThatThrownBy(this::evaluate).hasMessageContaining("REGRESSION");
        assertThatThrownBy(() -> gates.evaluate("pipeline", 3, "frozen-hash", release, null, regression)).hasMessageContaining("Task/EID");
    }
    @Test
    void appliesFrozenCustomThresholdsIncludingMinimumSampleCount() {
        when(release.publicationGate()).thenReturn(new PublicationGatePolicy(PublicationGatePolicy.SCHEMA,
                number("1"), number("1"), number("1"), number("1"), 100L, 2));
        var decision = evaluate();
        assertThat(decision.passed()).isFalse();
        assertThat(criterion(decision, "QUALITY", "MINIMUM_GOLD_SAMPLES").status()).isEqualTo(PipelinePublicationGateService.Status.FAILED);
        assertThat(criterion(decision, "QUALITY", "HIGH_RISK_RECALL").threshold()).isEqualByComparingTo("1");
    }
    private final CalibrationTaskSnapshotService calibration=mock(CalibrationTaskSnapshotService.class);
    private PipelinePublicationGateService.Decision calibratedDecision() {
        return new PipelinePublicationGateService(evaluations,json,executions,calibration)
                .evaluate("pipeline",3,"frozen-hash",release,quality,regression);
    }
    private void calibratedPolicy(boolean configured) {
        var reference=new EmpiricalConfidenceCalibration.Reference("source","source-run","model","a".repeat(64));
        when(release.fusion()).thenReturn(new io.github.opensabre.iqc.fusion.FusionPolicy("fusion",1,
                io.github.opensabre.iqc.fusion.FusionPolicy.Method.CONFIDENCE,Map.of(),Map.of(),"calibration",java.util.Set.of("model"),null,null,List.of(reference)));
        var defaults=PublicationGatePolicy.defaults();
        when(release.publicationGate()).thenReturn(new PublicationGatePolicy(defaults.schemaVersion(),defaults.minimumRiskRecall(),defaults.minimumOverallF1(),defaults.minimumRegressionPass(),defaults.minimumStructuredSuccess(),defaults.maximumP95Ms(),defaults.minimumSamples(),configured?
                new PublicationGatePolicy.Calibration(20,number("0.8"),number("0.2"),number("0.1"),number("0"),number("0")):null));
    }
    private void calibrationReport(String task,long scored,String coverage,String brier,String ece,String delta) {
        var base=report(task,25,25,25,true,1,"1","1","1","1",1L);
        var measured=new FindingEvaluationMetrics.ConfidenceDiagnostics("diagnostics","paired",25,scored,25-scored,number(coverage),number(brier),number(ece),List.of());
        var comparison=new NodeConfidenceMetrics.CalibrationComparison("iqc-calibration-paired-diagnostics-v1","a".repeat(64),25,number(coverage),measured,measured,number(delta),number(delta));
        var node=new NodeConfidenceMetrics.Node("model","LLM","1","QUALITY_HIT",measured,null,comparison);
        when(evaluations.report(task,task+"-execution")).thenReturn(new PipelineEvaluationService.Report(
                base.schemaVersion(),base.taskId(),base.executionId(),base.datasetHash(),base.expectedSamples(),base.availableSamples(),base.successfulSamples(),base.complete(),base.missingSampleIds(),base.sourceExecutionIds(),base.findingMetrics(),base.businessMetrics(),base.latencyMetrics(),base.regressionMetrics(),base.structuredMetrics(),null,null,new NodeConfidenceMetrics.Summary("nodes",List.of(node))));
    }
    @Test void calibratedPublicationChecksBothPurposesAtExactThresholds() {
        calibratedPolicy(true);calibrationReport("quality",20,"0.8","0.2","0.1","0");calibrationReport("regression",20,"0.8","0.2","0.1","0");
        var decision=calibratedDecision();assertThat(decision.passed()).isTrue();
        assertThat(decision.criteria().stream().filter(c->c.code().startsWith("CALIBRATION_"))).hasSize(12)
                .allMatch(c->c.status()==PipelinePublicationGateService.Status.PASSED);
        verify(calibration).requireHeldOut(eq(release),argThat(contexts->contexts.size()==2));
    }
    @Test void calibratedPublicationFailsUnconfiguredUnmeasuredAndEachRegressionThreshold() {
        calibratedPolicy(false);
        var missing=calibratedDecision();
        assertThat(criterion(missing,"QUALITY","CALIBRATION_POLICY").status()).isEqualTo(PipelinePublicationGateService.Status.FAILED);
        calibratedPolicy(true);calibrationReport("quality",20,"0.8","0.2","0.1","0");
        assertThat(criterion(calibratedDecision(),"REGRESSION/model","CALIBRATION_MEASURED").status()).isEqualTo(PipelinePublicationGateService.Status.FAILED);
        calibrationReport("regression",19,"0.79","0.21","0.11","0.01");
        var failed=calibratedDecision();assertThat(failed.passed()).isFalse();
        for(String code:List.of("CALIBRATION_PAIRED_SAMPLES","CALIBRATION_GOLD_COVERAGE","CALIBRATION_BRIER","CALIBRATION_ECE","CALIBRATION_BRIER_INCREASE","CALIBRATION_ECE_INCREASE"))
            assertThat(criterion(failed,"REGRESSION/model",code).status()).isEqualTo(PipelinePublicationGateService.Status.FAILED);
        calibrationReport("regression",0,"0",null,null,null);
        assertThat(criterion(calibratedDecision(),"REGRESSION/model","CALIBRATION_BRIER").actual()).isNull();
        calibrationReport("regression",25,"1","0","0","-0.1");
        assertThat(calibratedDecision().passed()).isTrue();
    }
    @Test void heldOutEvidenceFailurePreventsMetricReportsAndCannotBecomeFailedCriterion() {
        calibratedPolicy(true);
        doThrow(new IllegalStateException("overlapping calibration evidence")).when(calibration).requireHeldOut(any(),anyList());
        assertThatThrownBy(this::calibratedDecision).hasMessageContaining("overlapping");
        verify(evaluations,never()).report(anyString(),anyString());
    }

}
