package io.github.opensabre.iqc.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.label.LabelResolutionService;
import io.github.opensabre.iqc.label.dao.InsightLabelMapper;
import io.github.opensabre.iqc.label.model.LabelRuleBinding;
import io.github.opensabre.iqc.rule.dao.QualityRuleVersionMapper;
import io.github.opensabre.iqc.rule.model.QualityRuleVersion;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.processing.EngineExecutionResultSet.EngineType;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UnifiedLabelPlanServiceTest {
    final LabelResolutionService resolver = mock(LabelResolutionService.class);
    final InsightLabelMapper labels = mock(InsightLabelMapper.class);
    final QualityRuleVersionMapper versions = mock(QualityRuleVersionMapper.class);
    final io.github.opensabre.iqc.label.LabelActionService actions = mock(io.github.opensabre.iqc.label.LabelActionService.class);
    final io.github.opensabre.iqc.rule.dao.QualityRuleMapper rules = mock(io.github.opensabre.iqc.rule.dao.QualityRuleMapper.class);
    final UnifiedLabelPlanService service = new UnifiedLabelPlanService(resolver, labels, versions,
            mock(QualityAgentMapper.class), mock(IqcDataScope.class), new ObjectMapper(), actions, rules);
    LabelRuleBinding binding(String rule) {
        var binding = new LabelRuleBinding(); binding.setLabelId("10"); binding.setRuleId(rule); binding.setRuleVersionNo(2); return binding;
    }
    QualityRuleVersion version(String rule, String type) {
        var version = new QualityRuleVersion(); version.setRuleId(rule); version.setVersionNo(2); version.setStatus("PUBLISHED");
        version.setRuleType(type); version.setExpression("退款"); return version;
    }
    void resolve(LabelRuleBinding... bindings) {
        for (var binding : bindings) {
            var current = new io.github.opensabre.iqc.rule.model.QualityRule();
            current.setId(binding.getRuleId()); current.setStatus("PUBLISHED");
            when(rules.selectById(binding.getRuleId())).thenReturn(current);
        }
        when(resolver.resolve(any())).thenReturn(new LabelResolutionService.ResolvedSelection("1.0", List.of(
                new LabelResolutionService.LabelSnapshot("10", 1, "退款", "REFUND", "g", "group", "G", "c", "cat", false,
                        "customer", java.math.BigDecimal.ONE, List.of(bindings), List.of())), List.of("r1", "r2")));
    }
    @Test void freezesExactBoundVersionAndOneStagePerEngineInRequestedOrder() {
        resolve(binding("r1")); when(versions.selectOne(any())).thenReturn(version("r1", "REGEX"));
        var snapshot = service.freeze(new UnifiedLabelPlanService.Configuration(List.of(EngineType.DLS, EngineType.REGEX), "SELECTED", List.of("10"), null));
        assertThat(snapshot.resultProcessing()).isNull();
        assertThat(snapshot.enginePlan().engines()).extracting(TaskEnginePlanService.FrozenEngine::type).containsExactly(EngineType.DLS, EngineType.REGEX);
        assertThat(snapshot.enginePlan().engines().getFirst().configuration().path("tags")).isEmpty();
        var frozen = snapshot.enginePlan().engines().get(1).configuration().path("tags").get(0);
        assertThat(frozen.path("ruleVersionNo").asInt()).isEqualTo(2);
        assertThat(frozen.path("ruleId").asText()).isEqualTo("r1");
        var mutable = (com.fasterxml.jackson.databind.node.ObjectNode)snapshot.unifiedLabels(); mutable.removeAll();
        assertThat(snapshot.unifiedLabels().path("labels")).hasSize(1);
    }
    @Test void refusesExistingSameTypeBindingsWithoutChoosingOne() {
        resolve(binding("r1"), binding("r2"));
        when(versions.selectOne(any())).thenReturn(version("r1", "REGEX"), version("r2", "REGEX"));
        assertThatThrownBy(() -> service.freeze(new UnifiedLabelPlanService.Configuration(List.of(EngineType.REGEX), "SELECTED", List.of("10"), null)))
                .hasMessageContaining("同类型多规则");
    }
    @Test void rejectsDuplicateStagesAndMixedAllSelection() {
        assertThatThrownBy(() -> new UnifiedLabelPlanService.Configuration(List.of(EngineType.LLM, EngineType.LLM), "SELECTED", List.of("10"), "a"))
                .hasMessageContaining("不重复");
        assertThatThrownBy(() -> new UnifiedLabelPlanService.Configuration(List.of(EngineType.REGEX), "ALL", List.of("10"), null))
                .hasMessageContaining("全选不能混用");
    }
    @Test void refusesUnpublishedBoundVersion() {
        resolve(binding("r1")); var v = version("r1", "REGEX"); v.setStatus("DRAFT"); when(versions.selectOne(any())).thenReturn(v);
        assertThatThrownBy(() -> service.freeze(new UnifiedLabelPlanService.Configuration(List.of(EngineType.REGEX), "SELECTED", List.of("10"), null)))
                .hasMessageContaining("规则版本不可用");
    }
    @Test void actionMetadataIsFrozenAndHandedToPendingProcessorWithoutDeducting() throws Exception {
        resolve(binding("r1"));when(versions.selectOne(any())).thenReturn(version("r1","REGEX"));
        when(actions.snapshots("10")).thenReturn(List.of(new io.github.opensabre.iqc.label.LabelActionService.Snapshot("b","9007199254740993",1,"DEDUCT_SCORE","违规动作",null,"ENABLED")));
        var frozen=service.freeze(new UnifiedLabelPlanService.Configuration(List.of(EngineType.REGEX),"SELECTED",List.of("10"),null));
        assertThat(frozen.unifiedLabels().at("/labels/0/actions/0/type").asText()).isEqualTo("DEDUCT_SCORE");
        when(actions.snapshots("10")).thenReturn(List.of());
        assertThat(frozen.unifiedLabels().at("/labels/0/actions/0/actionId").asText()).isEqualTo("9007199254740993");
    }

    @Test void writingFreezeLocksInStableOrderAndRetainsExactVersionsAndStageOrder() {
        resolve(binding("z"), binding("a"));
        when(versions.selectOne(any())).thenReturn(version("z", "REGEX"), version("a", "DLS"));
        for (var id : List.of("z", "a")) {
            var current = new io.github.opensabre.iqc.rule.model.QualityRule();
            current.setId(id); current.setStatus("PUBLISHED"); current.setVersionNo(99);
            when(rules.lockById(id)).thenReturn(current);
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            var frozen = service.freeze(new UnifiedLabelPlanService.Configuration(
                    List.of(EngineType.DLS, EngineType.REGEX), "SELECTED", List.of("10"), null));
            var order = inOrder(rules); order.verify(rules).lockById("a"); order.verify(rules).lockById("z");
            verify(rules, never()).selectById(any(String.class));
            assertThat(frozen.enginePlan().engines()).extracting(TaskEnginePlanService.FrozenEngine::type)
                    .containsExactly(EngineType.DLS, EngineType.REGEX);
            assertThat(frozen.enginePlan().engines().get(1).configuration().at("/tags/0/ruleVersionNo").asInt()).isEqualTo(2);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void readOnlyPreviewDoesNotRequestWriteLocks() {
        resolve(binding("r1")); when(versions.selectOne(any())).thenReturn(version("r1", "REGEX"));
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try {
            service.freeze(new UnifiedLabelPlanService.Configuration(List.of(EngineType.REGEX), "SELECTED", List.of("10"), null));
            verify(rules).selectById("r1"); verify(rules, never()).lockById(any(String.class));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
            org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        }
    }

    @Test void publishedHistoricalVersionCannotReviveDisabledCurrentRule() {
        resolve(binding("r1"));
        var disabled = new io.github.opensabre.iqc.rule.model.QualityRule(); disabled.setStatus("DISABLED");
        when(rules.selectById("r1")).thenReturn(disabled);
        assertThatThrownBy(() -> service.freeze(new UnifiedLabelPlanService.Configuration(
                List.of(EngineType.REGEX), "SELECTED", List.of("10"), null))).hasMessageContaining("停用");
        verifyNoInteractions(versions, actions);
    }

    @Test void currentDraftDoesNotReplacePinnedPublishedVersion() {
        resolve(binding("r1"));
        var current = new io.github.opensabre.iqc.rule.model.QualityRule();
        current.setId("r1"); current.setStatus("DRAFT"); current.setVersionNo(3);
        when(rules.selectById("r1")).thenReturn(current);
        when(versions.selectOne(any())).thenReturn(version("r1", "REGEX"));
        var frozen = service.freeze(new UnifiedLabelPlanService.Configuration(
                List.of(EngineType.REGEX), "SELECTED", List.of("10"), null));
        assertThat(frozen.enginePlan().engines().getFirst().configuration().at("/tags/0/ruleVersionNo").asInt()).isEqualTo(2);
    }

}
