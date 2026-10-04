package io.github.opensabre.iqc.scheme;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentVersionMapper;
import io.github.opensabre.iqc.agent.model.QualityAgent;
import io.github.opensabre.iqc.agent.model.QualityAgentVersion;
import io.github.opensabre.iqc.rule.dao.QualityRuleMapper;
import io.github.opensabre.iqc.rule.dao.QualityRuleVersionMapper;
import io.github.opensabre.iqc.rule.model.QualityRule;
import io.github.opensabre.iqc.rule.model.QualityRuleVersion;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.opensabre.iqc.scheme.SchemeDefinition.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Route compilation is a read-only preview, never an implicit execution capability. */
class SchemeRouteDependencyPlanTest {
    private com.fasterxml.jackson.databind.node.ObjectNode frozenTask(SchemeDefinition definition) throws Exception {
        var dependencies = resolver.previewRoutes(definition);
        var root = mapper.createObjectNode();
        var marker = root.putObject("schemeSnapshot").put("kind", "DRAFT_TRIAL").put("schemeId", "scheme");
        var release = marker.putObject("release");
        release.set("definition", mapper.valueToTree(definition.forTaskSnapshot()));
        release.set("dependencies", mapper.valueToTree(dependencies));
        root.set("rules", mapper.valueToTree(dependencies.rules()));
        marker.put("contentHash", InspectionSchemeService.contentHash(release.toString()));
        return (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(root.toString());
    }

    private String frozenAgent(com.fasterxml.jackson.databind.JsonNode root) {
        return root.at("/schemeSnapshot/release/dependencies/agent").toString();
    }

    @Test
    void frozenPlanValidatesWithoutReadingChangedCurrentAssets() throws Exception {
        var definition = definition(List.of(item("a", "semantic", Route.RULE_THEN_LLM, "local", InputScope.CONVERSATION)), true);
        var root = frozenTask(definition);
        published.get("semantic").setExpression("当前资产已改变");
        clearInvocations(rules, versions, agents, agentVersions, schemes);
        assertThatCode(() -> SchemeDependencyResolver.validateRouteTaskProjection(root, frozenAgent(root), mapper)).doesNotThrowAnyException();
        verifyNoInteractions(rules, versions, agents, agentVersions, schemes);
        assertThatThrownBy(definition::requireSupportedRoutes).hasMessageContaining("旧检测投影");
    }

    @Test
    void rejectsContextConsumerOrExtraPlanFieldTampering() throws Exception {
        var definition = definition(List.of(item("a", "semantic", Route.RULE_THEN_LLM, "local", null)), true);
        for (String change : List.of("scope", "consumer", "upstream", "extra")) {
            var root = frozenTask(definition);
            var plan = (com.fasterxml.jackson.databind.node.ObjectNode) root.at("/schemeSnapshot/release/dependencies/itemExecutionPlan");
            switch (change) {
                case "scope" -> ((com.fasterxml.jackson.databind.node.ObjectNode) plan.path("contexts").get(1)).put("inputScope", "CONVERSATION");
                case "consumer" -> ((com.fasterxml.jackson.databind.node.ObjectNode) plan.path("items").get(0)).put("finalContextKey", "ctx-forged");
                case "upstream" -> ((com.fasterxml.jackson.databind.node.ObjectNode) plan.path("contexts").get(1)).put("upstreamContextKey", "ctx-forged");
                default -> plan.put("extraInstruction", "bypass");
            }
            assertThatThrownBy(() -> SchemeDependencyResolver.validateRouteTaskProjection(root, frozenAgent(root), mapper))
                    .hasMessageContaining("执行计划与冻结输入不一致");
        }
    }

    @Test
    void rejectsRuleProjectionMissingDuplicateStageAndAgentMismatch() throws Exception {
        var definition = definition(List.of(item("a", "semantic", Route.RULE_THEN_LLM, "local", null)), true);
        var root = frozenTask(definition);
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("rules").get(0)).put("expression", "forged");
        assertThatThrownBy(() -> SchemeDependencyResolver.validateRouteTaskProjection(root, frozenAgent(root), mapper))
                .hasMessageContaining("检测依赖与冻结方案不一致");
        for (boolean duplicate : List.of(false, true)) {
            var altered = frozenTask(definition);
            var rules = (com.fasterxml.jackson.databind.node.ArrayNode) altered.at("/schemeSnapshot/release/dependencies/rules");
            if (duplicate) rules.add(rules.get(0).deepCopy()); else rules.remove(1);
            altered.set("rules", rules.deepCopy());
            assertThatThrownBy(() -> SchemeDependencyResolver.validateRouteTaskProjection(altered, frozenAgent(altered), mapper))
                    .hasMessageContaining(duplicate ? "重复" : "缺少检测阶段");
        }
        var valid = frozenTask(definition);
        assertThatThrownBy(() -> SchemeDependencyResolver.validateRouteTaskProjection(valid, "{}", mapper))
                .hasMessageContaining("智能体版本或任务快照不一致");
    }

    @Test
    void rejectsChangedProtocolAndConsistentReplacementWithOldDigest() throws Exception {
        var definition = definition(List.of(item("a", "local", Route.RULE_ONLY, null, null)), false);
        var root = frozenTask(definition);
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.at("/schemeSnapshot/release/definition")).put("schemaVersion", SCHEMA);
        assertThatThrownBy(() -> SchemeDependencyResolver.validateRouteTaskProjection(root, null, mapper)).hasMessageContaining("协议与配置不一致");
        var before = frozenTask(definition); String originalHash = before.path("schemeSnapshot").path("contentHash").asText();
        published.get("local").setExpression("新的冻结参数");
        var replaced = frozenTask(definition);
        ((com.fasterxml.jackson.databind.node.ObjectNode) replaced.path("schemeSnapshot")).put("contentHash", originalHash);
        assertThatThrownBy(() -> SchemeDependencyResolver.validateRouteTaskProjection(replaced, null, mapper)).hasMessageContaining("摘要不一致");
    }

    @Test
    void legacyTaskCannotAcquireUnreferencedRoutePlan() {
        var root = mapper.createObjectNode();
        root.putObject("schemeSnapshot").putObject("release").putObject("dependencies").putObject("itemExecutionPlan");
        assertThatThrownBy(() -> SchemeDependencyResolver.validateRouteTaskProjection(root, null, mapper)).hasMessageContaining("无逐项路线");
        assertThatCode(() -> SchemeDependencyResolver.validateRouteTaskProjection(mapper.createObjectNode(), null, mapper)).doesNotThrowAnyException();
    }
    private final ObjectMapper mapper = new ObjectMapper();
    private final QualityRuleMapper rules = mock(QualityRuleMapper.class);
    private final QualityRuleVersionMapper versions = mock(QualityRuleVersionMapper.class);
    private final QualityAgentMapper agents = mock(QualityAgentMapper.class);
    private final QualityAgentVersionMapper agentVersions = mock(QualityAgentVersionMapper.class);
    private final io.github.opensabre.iqc.scheme.dao.InspectionSchemeMapper schemes = mock(io.github.opensabre.iqc.scheme.dao.InspectionSchemeMapper.class);
    private final Map<String, QualityRuleVersion> published = new LinkedHashMap<>();
    private final io.github.opensabre.iqc.label.LabelResolutionService labels = mock(io.github.opensabre.iqc.label.LabelResolutionService.class);
    private final SchemeDependencyResolver resolver = new SchemeDependencyResolver(rules, versions, agents, agentVersions, mapper,
            mock(io.github.opensabre.iqc.agent.AgentAssetReferenceValidator.class), schemes, labels);

    @BeforeEach
    void publishedAssets() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "iqc-route-test"),
                QualityRuleVersion.class);
        for (String id : List.of("local", "other", "semantic", "candidate")) {
            var current = new QualityRule(); current.setStatus("PUBLISHED"); when(rules.selectById(id)).thenReturn(current);
            var version = new QualityRuleVersion(); version.setRuleId(id); version.setVersionNo(1); version.setStatus("PUBLISHED");
            version.setRuleType(id.equals("local") || id.equals("other") ? "REGEX" : "LLM");
            version.setExpression("贷款"); version.setTargetRole("customer"); published.put(id, version);
        }
        when(versions.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            var query = (AbstractWrapper<?, ?, ?>) invocation.getArgument(0); query.getSqlSegment();
            return query.getParamNameValuePairs().values().stream().filter(published::containsKey)
                    .findFirst().map(published::get).orElse(null);
        });
        var current = new QualityAgent(); current.setStatus("PUBLISHED"); when(agents.selectById("agent")).thenReturn(current);
        var version = new QualityAgentVersion(); version.setVersionNo(1); version.setStatus("PUBLISHED");
        version.setConfigJson("{\"schemaVersion\":\"3.0\",\"systemPrompt\":\"质检\",\"primaryModelProfileId\":\"model\",\"assetSnapshots\":{\"primaryModel\":{\"id\":\"model\"}}}");
        when(agentVersions.selectOne(any(Wrapper.class))).thenReturn(version);
    }

    private RuleReference ref(String id) { return new RuleReference(id, 1); }
    private Item item(String code, String id, Route route, String stage, InputScope scope) {
        var execution = new Execution(route, route == Route.RULE_THEN_LLM ? ref(stage) : null,
                route == Route.LLM_THEN_RULE ? ref(stage) : null, null, route == Route.RULE_THEN_LLM ? true : null);
        return new Item(code, code, ref(id), HitMeaning.VIOLATION, null, scope, execution);
    }
    private SchemeDefinition definition(List<Item> items, boolean agent) {
        return new SchemeDefinition(SCHEMA, items, agent ? new AgentReference("agent", 1) : null,
                new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION, InspectionScoring.Mode.DEDUCTION, 100, 60, List.of()));
    }

    @Test
    void resolvesAllFourRoutesForFrozenFormalExecution() throws Exception {
        var definition = definition(List.of(item("a", "local", Route.RULE_ONLY, null, null),
                item("b", "semantic", Route.LLM_ONLY, null, InputScope.CONVERSATION),
                item("c", "semantic", Route.RULE_THEN_LLM, "local", null),
                item("d", "local", Route.LLM_THEN_RULE, "candidate", null)), true);
        var dependencies = resolver.previewRoutes(definition);
        assertThat(dependencies.rules()).hasSize(3);
        assertThat(dependencies.itemExecutionPlan().items()).hasSize(4);
        assertThat(dependencies.itemExecutionPlan().contexts()).hasSize(6);
        assertThat(dependencies.itemExecutionPlan().contexts()).extracting(SchemeDependencyResolver.DetectionContext::phase)
                .containsExactlyInAnyOrder("DIRECT", "DIRECT", "PREFILTER", "REVIEW", "CANDIDATE", "VERIFY");
        assertThat(mapper.readValue(mapper.writeValueAsString(dependencies), SchemeDependencyResolver.Dependencies.class)).isEqualTo(dependencies);
        verify(versions, times(3)).selectOne(any(Wrapper.class));
        assertThat(resolver.resolve(definition)).isEqualTo(dependencies);
    }

    @Test
    void sharesIdenticalContextsButSeparatesScopeAndUpstreamAndParameters() {
        var items = List.of(item("a", "semantic", Route.LLM_ONLY, null, InputScope.MESSAGE),
                item("b", "semantic", Route.LLM_ONLY, null, InputScope.MESSAGE),
                item("c", "semantic", Route.LLM_ONLY, null, InputScope.CONVERSATION),
                item("d", "semantic", Route.RULE_THEN_LLM, "local", null),
                item("e", "semantic", Route.RULE_THEN_LLM, "other", null));
        var first = resolver.previewRoutes(definition(items, true)).itemExecutionPlan();
        assertThat(first.contexts()).hasSize(6);
        assertThat(first.items().get(0).finalContextKey()).isEqualTo(first.items().get(1).finalContextKey())
                .isNotEqualTo(first.items().get(2).finalContextKey());
        assertThat(first.items().get(3).finalContextKey()).isNotEqualTo(first.items().get(4).finalContextKey());
        assertThat(resolver.previewRoutes(definition(items, true)).itemExecutionPlan()).isEqualTo(first);
        published.get("semantic").setExpression("变更参数");
        var changed = resolver.previewRoutes(definition(items, true)).itemExecutionPlan();
        assertThat(changed.items().getFirst().finalContextKey()).isNotEqualTo(first.items().getFirst().finalContextKey());
        published.get("semantic").setTargetRole("agent");
        var differentRole = resolver.previewRoutes(definition(items, true)).itemExecutionPlan();
        assertThat(differentRole.items().getFirst().finalContextKey()).isNotEqualTo(changed.items().getFirst().finalContextKey());
    }

    @Test
    void rejectsMismatchedFinalAndIntermediateDetectorTypes() {
        assertThatThrownBy(() -> resolver.previewRoutes(definition(List.of(item("a", "local", Route.LLM_ONLY, null, null)), false)))
                .hasMessageContaining("最终检测规则类型");
        assertThatThrownBy(() -> resolver.previewRoutes(definition(List.of(item("a", "semantic", Route.RULE_ONLY, null, null)), true)))
                .hasMessageContaining("最终检测规则类型");
        assertThatThrownBy(() -> resolver.previewRoutes(definition(List.of(item("a", "semantic", Route.RULE_THEN_LLM, "candidate", null)), true)))
                .hasMessageContaining("中间阶段规则类型");
        assertThatThrownBy(() -> resolver.previewRoutes(definition(List.of(item("a", "local", Route.LLM_THEN_RULE, "other", null)), false)))
                .hasMessageContaining("中间阶段规则类型");
    }

    @Test
    void missingAgentAndMissingStageVersionRejectPreview() {
        var definition = definition(List.of(item("a", "local", Route.LLM_THEN_RULE, "candidate", null)), false);
        assertThatThrownBy(() -> resolver.previewRoutes(definition)).hasMessageContaining("智能体版本");
        published.remove("candidate");
        assertThatThrownBy(() -> resolver.previewRoutes(definition)).hasMessageContaining("规则版本未发布");
    }

    @Test
    void implicitItemsAreIncludedAndLegacyDependenciesDoNotAcquirePlanField() throws Exception {
        var explicit = item("a", "local", Route.RULE_ONLY, null, null);
        var old = new Item("b", "旧检查", ref("semantic"), HitMeaning.COMPLIANCE);
        var dependencies = resolver.previewRoutes(definition(List.of(explicit, old), true));
        assertThat(dependencies.itemExecutionPlan().items()).extracting(SchemeDependencyResolver.ItemRoute::route)
                .containsExactly(Route.RULE_ONLY, Route.LLM_ONLY);
        var legacy = resolver.resolve(definition(List.of(new Item("old", "旧规则", ref("local"), HitMeaning.VIOLATION)), false));
        assertThat(mapper.writeValueAsString(legacy)).doesNotContain("itemExecutionPlan");
        var base = definition(List.of(explicit), false);
        var joint = new SchemeDefinition(SCHEMA, base.items(), null, base.scoring(), null,
                List.of(new io.github.opensabre.iqc.label.LabelResolutionService.LabelReference("label", 1)));
        var binding = new io.github.opensabre.iqc.label.model.LabelRuleBinding();
        binding.setRuleId("local"); binding.setRuleVersionNo(1);
        var labelValue = new io.github.opensabre.iqc.label.model.LabelValueDefinition();
        labelValue.setValueCode("owns"); labelValue.setValueType("BOOLEAN");
        labelValue.setConfigJson("{\"onRuleHit\":{\"local\":false}}");
        var frozenLabel = new io.github.opensabre.iqc.label.LabelResolutionService.LabelSnapshot("label", 1,
                "住房情况", "house", null, null, null, null, null, false, "customer", null,
                List.of(binding), List.of(labelValue));
        var frozenLabels = new io.github.opensabre.iqc.label.LabelResolutionService.ResolvedSelection(
                "2.0", List.of(frozenLabel), List.of("local"));
        when(labels.resolveVersions(joint.labels())).thenReturn(frozenLabels);
        var jointDependencies = resolver.previewRoutes(joint);
        assertThat(jointDependencies.labels()).isEqualTo(frozenLabels);
        assertThat(jointDependencies.itemExecutionPlan().labelContextKeys()).hasSize(1);
        assertThat(jointDependencies.itemExecutionPlan().items().getFirst().finalContextKey())
                .isEqualTo(jointDependencies.itemExecutionPlan().labelContextKeys().getFirst());
        assertThatCode(() -> SchemeDependencyResolver.validateRouteTaskProjection(frozenTask(joint), null, mapper))
                .doesNotThrowAnyException();
        var conditional = new SchemeDefinition(SCHEMA, List.of(new Item("a", "条件", ref("local"), HitMeaning.VIOLATION,
                ref("other"), null, explicit.execution())), null, base.scoring());
        var conditionalPlan = resolver.previewRoutes(conditional).itemExecutionPlan();
        assertThat(conditionalPlan.schemaVersion()).isEqualTo("iqc-item-execution-plan-v2");
        assertThat(conditionalPlan.contexts()).extracting(SchemeDependencyResolver.DetectionContext::phase)
                .containsExactlyInAnyOrder("APPLICABILITY", "DIRECT");
        assertThat(conditionalPlan.items().getFirst().applicabilityContextKey())
                .isEqualTo(conditionalPlan.contexts().stream().filter(value -> "APPLICABILITY".equals(value.phase()))
                        .findFirst().orElseThrow().contextKey());
        conditional.requireTrialExecutable();
        var frozen = frozenTask(conditional);
        assertThatCode(() -> SchemeDependencyResolver.validateRouteTaskProjection(frozen, null, mapper)).doesNotThrowAnyException();
        var forged = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(frozen.toString());
        ((com.fasterxml.jackson.databind.node.ObjectNode) forged.at("/schemeSnapshot/release/dependencies/itemExecutionPlan/items").get(0))
                .put("applicabilityContextKey", "ctx-forged");
        assertThatThrownBy(() -> SchemeDependencyResolver.validateRouteTaskProjection(forged, null, mapper))
                .hasMessageContaining("执行计划与冻结输入不一致");
    }
}
