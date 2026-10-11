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
}
