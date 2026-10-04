package io.github.opensabre.iqc.scheme;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentVersionMapper;
import io.github.opensabre.iqc.rule.dao.QualityRuleMapper;
import io.github.opensabre.iqc.rule.dao.QualityRuleVersionMapper;
import io.github.opensabre.iqc.rule.model.QualityRule;
import io.github.opensabre.iqc.rule.model.QualityRuleVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SchemeDependencyResolverTest {
    @Test
    void routeDefinitionsResolveAndMalformedRouteTaskCannotStart() {
        var old = InspectionSchemeServiceTest.definition(); var item = old.items().getFirst();
        var routed = new SchemeDefinition(old.schemaVersion(), java.util.List.of(new SchemeDefinition.Item(
                item.itemCode(), item.name(), item.rule(), item.hitMeaning(), null, null,
                new SchemeDefinition.Execution(SchemeDefinition.Route.RULE_ONLY, null, null, null, null))),
                null, old.scoring());
        assertThat(resolver.resolve(routed).itemExecutionPlan()).isNotNull();
        clearInvocations(rules, versions, agents, agentVersions, schemes);
        var json = new ObjectMapper(); var root = json.createObjectNode();
        root.putObject("schemeSnapshot").put("kind", "DRAFT_TRIAL").put("schemeId", "scheme")
                .putObject("release").set("definition", json.valueToTree(routed.forTaskSnapshot()));
        assertThatThrownBy(() -> resolver.validateTaskDependencies(root)).hasMessageContaining("检测依赖");
        verifyNoInteractions(rules, versions, agents, agentVersions, schemes);
    }
    @Test
    void scopedDependencyFreezesItsInputAndRejectsTamperedProjection() throws Exception {
        version.setRuleType("LLM");
        var agent = new io.github.opensabre.iqc.agent.model.QualityAgent(); agent.setStatus("PUBLISHED");
        when(agents.selectById("agent")).thenReturn(agent);
        var agentVersion = new io.github.opensabre.iqc.agent.model.QualityAgentVersion();
        agentVersion.setVersionNo(1); agentVersion.setStatus("PUBLISHED");
        agentVersion.setConfigJson("{\"schemaVersion\":\"3.0\",\"systemPrompt\":\"质检\",\"primaryModelProfileId\":\"model\",\"assetSnapshots\":{\"primaryModel\":{\"id\":\"model\"}}}");
        when(agentVersions.selectOne(any(Wrapper.class))).thenReturn(agentVersion);
        var original = InspectionSchemeServiceTest.definition(); var item = original.items().getFirst();
        var definition = new SchemeDefinition(original.schemaVersion(), java.util.List.of(new SchemeDefinition.Item(
                item.itemCode(), item.name(), item.rule(), item.hitMeaning(), null, SchemeDefinition.InputScope.CONVERSATION)),
                new SchemeDefinition.AgentReference("agent", 1), original.scoring());
        var resolved = resolver.resolve(definition);
        assertThat(resolved.rules().getFirst().path("inspectionScope").asText()).isEqualTo("CONVERSATION");
        assertThat(resolved.executionMode()).isEqualTo("INDEPENDENT");
        var scheme = new io.github.opensabre.iqc.scheme.model.InspectionScheme(); scheme.setStatus("ACTIVE");
        when(schemes.selectById("scheme")).thenReturn(scheme);
        var json = new ObjectMapper(); var root = json.createObjectNode();
        var marker = root.putObject("schemeSnapshot").put("schemeId", "scheme").put("kind", "DRAFT_TRIAL");
        var release = marker.putObject("release");
        release.set("definition", json.valueToTree(definition.forTaskSnapshot()));
        release.set("dependencies", json.valueToTree(resolved)); root.set("rules", json.valueToTree(resolved.rules()));
        resolver.validateTaskDependencies(root);
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("rules").get(0)).put("inspectionScope", "MESSAGE");
        assertThatThrownBy(() -> resolver.validateTaskDependencies(root)).hasMessageContaining("输入范围与方案不一致");
    }
    @Test
    void scopedInputRejectsNonLlmAndConflictingSharedContexts() {
        var original = InspectionSchemeServiceTest.definition();
        var item = original.items().getFirst();
        var scopedItem = new SchemeDefinition.Item(item.itemCode(), item.name(), item.rule(), item.hitMeaning(), null,
                SchemeDefinition.InputScope.CONVERSATION);
        var scoped = new SchemeDefinition(original.schemaVersion(), java.util.List.of(scopedItem), null, original.scoring());
        assertThatThrownBy(() -> resolver.resolve(scoped)).hasMessageContaining("仅用于 LLM");
        version.setRuleType("LLM");
        var conflicting = new SchemeDefinition(original.schemaVersion(), java.util.List.of(scopedItem,
                new SchemeDefinition.Item("another", "另一项", item.rule(), item.hitMeaning())), null, original.scoring());
        assertThatThrownBy(() -> resolver.resolve(conflicting)).hasMessageContaining("输入范围不一致");
        assertThatThrownBy(() -> resolver.resolve(scoped)).hasMessageContaining("智能体版本");
    }
    private final QualityRuleMapper rules = mock(QualityRuleMapper.class);
    private final QualityRuleVersionMapper versions = mock(QualityRuleVersionMapper.class);
    private final QualityAgentMapper agents = mock(QualityAgentMapper.class);
    private final QualityAgentVersionMapper agentVersions = mock(QualityAgentVersionMapper.class);
    private final io.github.opensabre.iqc.agent.AgentAssetReferenceValidator assets = mock(io.github.opensabre.iqc.agent.AgentAssetReferenceValidator.class);
    private final io.github.opensabre.iqc.scheme.dao.InspectionSchemeMapper schemes = mock(io.github.opensabre.iqc.scheme.dao.InspectionSchemeMapper.class);
    private final io.github.opensabre.iqc.label.LabelResolutionService labels = mock(io.github.opensabre.iqc.label.LabelResolutionService.class);
    private final SchemeDependencyResolver resolver = new SchemeDependencyResolver(rules, versions, agents, agentVersions, new ObjectMapper(), assets, schemes, labels);
    private QualityRule current;
    private QualityRuleVersion version;

    @Test
    void jointReferencesFreezeLabelProtocolAndDeduplicateSharedRuleVersions() {
        var definition = jointDefinition();
        var binding = new io.github.opensabre.iqc.label.model.LabelRuleBinding();
        binding.setRuleId("rule"); binding.setRuleVersionNo(1);
        var value = new io.github.opensabre.iqc.label.model.LabelValueDefinition();
        value.setValueCode("interested"); value.setValueType("BOOLEAN"); value.setDescription("是否有购买意向");
        value.setConfigJson("{\"defaultValue\":true,\"onRuleHit\":{\"rule\":false}}");
        var label = new io.github.opensabre.iqc.label.LabelResolutionService.LabelSnapshot("l1", 1, "意向", "intent",
                null, null, null, null, null, false, "customer", null, java.util.List.of(binding), java.util.List.of(value));
        var selection = new io.github.opensabre.iqc.label.LabelResolutionService.ResolvedSelection("2.0", java.util.List.of(label), java.util.List.of("rule"));
        when(labels.resolveVersions(definition.labels())).thenReturn(selection);
        var resolved = resolver.resolve(definition);
        assertThat(resolved.labels()).isEqualTo(selection);
        assertThat(resolved.rules()).hasSize(1);
        var target = resolved.rules().getFirst().path("labelFactTargets").get(0);
        assertThat(target.path("labelId").asText()).isEqualTo("l1");
        assertThat(target.path("labelVersionNo").asInt()).isEqualTo(1);
        assertThat(target.path("valueCode").asText()).isEqualTo("interested");
        assertThat(target.path("onRuleHitValue").asBoolean()).isFalse();
        assertThat(target.path("subjectRole").asText()).isEqualTo("customer");
        assertThat(target.toString()).doesNotContain("defaultValue", "configJson");
        value.setDescription("changed mutable description");
        assertThat(target.path("description").asText()).isEqualTo("是否有购买意向");
        verify(versions, times(1)).selectOne(any(Wrapper.class));
        binding.setRuleVersionNo(2);
        assertThatThrownBy(() -> resolver.resolve(definition)).hasMessageContaining("不同版本");
    }

    @Test
    void labelOnlyDraftStillResolvesItsDetectorWithoutInventingBusinessChecks() {
        var mapped = value("owns", "BOOLEAN");
        mapped.setConfigJson("{\"onRuleHit\":{\"rule\":false}}");
        when(labels.resolveVersions(any())).thenReturn(selection("customer", "rule", java.util.List.of(mapped)));
        var policy = new io.github.opensabre.iqc.scoring.InspectionScoring.Policy("iqc-score-v2",
                io.github.opensabre.iqc.scoring.InspectionScoring.Mode.DEDUCTION, 100, 60, java.util.List.of());
        var definition = new SchemeDefinition(SchemeDefinition.SCHEMA, java.util.List.of(), null, policy, null,
                java.util.List.of(new io.github.opensabre.iqc.label.LabelResolutionService.LabelReference("l1", 1)));

        var resolved = resolver.resolve(definition);
        assertThat(resolved.executionMode()).isEqualTo("RULE_ONLY");
        assertThat(resolved.rules()).hasSize(1);
        assertThat(resolved.rules().getFirst().path("labelFactTargets").get(0).path("onRuleHitValue").booleanValue()).isFalse();
        verify(versions).selectOne(any(Wrapper.class));
    }

    @Test
    void existingUserRoleCanBeFrozenAsCustomerLabelSubject() {
        var mapped = value("owns", "BOOLEAN");
        mapped.setConfigJson("{\"onRuleHit\":{\"rule\":false}}");
        when(labels.resolveVersions(any())).thenReturn(selection("user", "rule", java.util.List.of(mapped)));
        version.setTargetRole("user");

        var target = resolver.resolve(jointDefinition()).rules().getFirst().path("labelFactTargets").get(0);
        assertThat(target.path("subjectRole").asText()).isEqualTo("user");
        assertThat(target.path("onRuleHitValue").booleanValue()).isFalse();
    }

    @Test
    void releasedJointDependenciesDoNotReopenMutableLabelDefinitions() {
        var definition = jointDefinition();
        var mappedValue = value("owns", "BOOLEAN"); mappedValue.setConfigJson("{\"onRuleHit\":{\"rule\":true}}");
        var frozen = selection("customer", "rule", java.util.List.of(mappedValue));
        var release = new InspectionSchemeService.ReleaseSnapshot("已发布", "sales", null, "销售", definition,
                new SchemeDependencyResolver.Dependencies(java.util.List.of(), null, "RULE_ONLY", frozen));
        when(labels.resolveVersions(any())).thenThrow(io.github.opensabre.iqc.governance.IqcException.invalidState("标签已改为草稿"));

        resolver.validateReleasedDependencies(release);
        verifyNoInteractions(labels);
        verify(versions).selectOne(any(Wrapper.class));
        current.setStatus("DISABLED");
        assertThatThrownBy(() -> resolver.validateReleasedDependencies(release)).hasMessageContaining("停用");
    }

    @Test
    void boundedJointDraftTrialAndPublishedTaskPassFrozenDependencyChecks() {
        var mapper = new ObjectMapper();
        var mapped = value("owns", "BOOLEAN"); mapped.setConfigJson("{\"onRuleHit\":{\"rule\":true}}");
        var frozen = selection("customer", "rule", java.util.List.of(mapped));
        var release = new InspectionSchemeService.ReleaseSnapshot("试跑", "sales", null, "销售", jointDefinition(),
                new SchemeDependencyResolver.Dependencies(java.util.List.of(), null, "RULE_ONLY", frozen));
        var scheme = new io.github.opensabre.iqc.scheme.model.InspectionScheme(); scheme.setStatus("ACTIVE");
        when(schemes.selectById("scheme")).thenReturn(scheme);
        var root = mapper.createObjectNode();
        var marker = root.putObject("schemeSnapshot").put("kind", "DRAFT_TRIAL").put("schemeId", "scheme");
        marker.set("release", mapper.valueToTree(release));

        assertThatCode(() -> resolver.validateTaskDependencies(root)).doesNotThrowAnyException();
        marker.put("kind", "PUBLISHED");
        assertThatCode(() -> resolver.validateTaskDependencies(root)).doesNotThrowAnyException();
    }

    @Test
    void releasedJointDependenciesRequireMatchingFrozenLabelVersion() {
        var definition = jointDefinition();
        var frozen = selection("customer", "rule", java.util.List.of(value("owns", "BOOLEAN")));
        var mismatched = new io.github.opensabre.iqc.label.LabelResolutionService.LabelSnapshot("l1", 2, "有房", "house", null,
                null, null, null, null, false, "customer", null, frozen.labels().getFirst().bindings(), frozen.labels().getFirst().values());
        var release = new InspectionSchemeService.ReleaseSnapshot("已发布", "sales", null, "销售", definition,
                new SchemeDependencyResolver.Dependencies(java.util.List.of(), null, "RULE_ONLY",
                        new io.github.opensabre.iqc.label.LabelResolutionService.ResolvedSelection("2.0", java.util.List.of(mismatched), java.util.List.of("rule"))));
        assertThatThrownBy(() -> resolver.validateReleasedDependencies(release)).hasMessageContaining("版本不一致");
        verifyNoInteractions(labels, rules, versions);
    }

    @Test
    void jointDefinitionRoundTripsAndUsesItsFrozenLabelContract() throws Exception {
        var mapper = new ObjectMapper();
        var definition = jointDefinition();
        assertThat(mapper.readValue(mapper.writeValueAsString(definition), SchemeDefinition.class)).isEqualTo(definition);
        assertThatCode(definition::requireExecutable).doesNotThrowAnyException();
        var old = mapper.valueToTree(InspectionSchemeServiceTest.definition());
        assertThat(old.has("labels")).isFalse();
        assertThat(mapper.valueToTree(new SchemeDependencyResolver.Dependencies(java.util.List.of(), null, "RULE_ONLY")).has("labels")).isFalse();
    }

    @Test
    void jointTaskProjectionRejectsMissingLabelsChangedRulesAndMissingTargets() throws Exception {
        var mapper = new ObjectMapper();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("""
                {"schemeSnapshot":{"release":{"definition":{"labels":[{"id":"l1","versionNo":1}]},
                  "dependencies":{"labels":{"schemaVersion":"2.0","labels":[{"id":"l1","versionNo":1,
                    "targetRole":"user","bindings":[{"ruleId":"r1","ruleVersionNo":2}],
                    "values":[{"valueCode":"owns"}]}]},
                    "rules":[{"id":"r1","versionNo":2,"labelFactTargets":[{"labelId":"l1",
                      "labelVersionNo":1,"valueCode":"owns","subjectRole":"user"}]}]}}},
                 "rules":[{"id":"r1","versionNo":2,"labelFactTargets":[{"labelId":"l1",
                   "labelVersionNo":1,"valueCode":"owns","subjectRole":"user"}]}]}
                """);
        String frozen = root.path("schemeSnapshot").path("release").path("dependencies").path("labels").toString();
        assertThatCode(() -> SchemeDependencyResolver.validateJointTaskProjection(root, frozen, mapper)).doesNotThrowAnyException();
        var frozenLabel = (com.fasterxml.jackson.databind.node.ObjectNode) root.at("/schemeSnapshot/release/dependencies/labels/labels/0");
        frozenLabel.put("weight", new java.math.BigDecimal("1.00"));
        String weightedScope = root.at("/schemeSnapshot/release/dependencies/labels").toString().replace("\"weight\":1.00", "\"weight\":1.0");
        assertThatCode(() -> SchemeDependencyResolver.validateJointTaskProjection(root, weightedScope, mapper)).doesNotThrowAnyException();
        assertThatThrownBy(() -> SchemeDependencyResolver.validateJointTaskProjection(root,
                weightedScope.replace("\"weight\":1.0", "\"weight\":2.0"), mapper)).hasMessageContaining("标签快照与方案不一致");
        frozenLabel.remove("weight");
        var extra = mapper.readTree("{\"labelId\":\"other\",\"labelVersionNo\":1,\"valueCode\":\"x\",\"subjectRole\":\"user\"}");
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.withArray("rules").get(0).path("labelFactTargets")).add(extra);
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("schemeSnapshot").path("release").path("dependencies")
                .path("rules").get(0).path("labelFactTargets")).add(extra);
        assertThatThrownBy(() -> SchemeDependencyResolver.validateJointTaskProjection(root, frozen, mapper))
                .hasMessageContaining("未引用");
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.withArray("rules").get(0).path("labelFactTargets")).remove(1);
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("schemeSnapshot").path("release").path("dependencies")
                .path("rules").get(0).path("labelFactTargets")).remove(1);
        assertThatThrownBy(() -> SchemeDependencyResolver.validateJointTaskProjection(root, null, mapper))
                .hasMessageContaining("标签快照");
        assertThatThrownBy(() -> SchemeDependencyResolver.validateJointTaskProjection(root, "{}", mapper))
                .hasMessageContaining("标签快照");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.withArray("rules").get(0)).put("versionNo", 3);
        assertThatThrownBy(() -> SchemeDependencyResolver.validateJointTaskProjection(root, frozen, mapper))
                .hasMessageContaining("检测依赖");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.withArray("rules").get(0)).put("versionNo", 2);
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.withArray("rules").get(0)).remove("labelFactTargets");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("schemeSnapshot").path("release")
                .path("dependencies").path("rules").get(0)).remove("labelFactTargets");
        assertThatThrownBy(() -> SchemeDependencyResolver.validateJointTaskProjection(root, frozen, mapper))
                .hasMessageContaining("目标覆盖");
    }

    @Test
    void onlyBoundDetectorsReceiveFrozenTargets() {
        var definition = jointDefinition();
        var mappedValue = value("owns", "BOOLEAN"); mappedValue.setConfigJson("{\"onRuleHit\":{\"label-rule\":true}}");
        when(labels.resolveVersions(any())).thenReturn(selection("customer", "label-rule", java.util.List.of(mappedValue)));
        var labelRule = new QualityRule(); labelRule.setId("label-rule"); labelRule.setStatus("PUBLISHED");
        when(rules.selectById("label-rule")).thenReturn(labelRule);
        var labelVersion = new QualityRuleVersion(); labelVersion.setRuleId("label-rule"); labelVersion.setVersionNo(1);
        labelVersion.setRuleType("REGEX"); labelVersion.setExpression("房子");
        when(versions.selectOne(any(Wrapper.class))).thenReturn(version, labelVersion);
        var resolved = resolver.resolve(definition);
        assertThat(resolved.rules()).hasSize(2);
        assertThat(resolved.rules().getFirst().has("labelFactTargets")).isFalse();
        var target = resolved.rules().get(1).path("labelFactTargets").get(0);
        assertThat(target.path("ruleId").asText()).isEqualTo("label-rule");
        assertThat(target.path("ruleVersionNo").asInt()).isEqualTo(1);
        assertThat(target.path("onRuleHitValue").asBoolean()).isTrue();
        assertThat(resolved.rules().get(1).path("deduction").asInt()).isZero();
    }

    @Test
    void ambiguousSubjectAndMissingValueDefinitionsAreRejectedBeforePublication() {
        when(labels.resolveVersions(any())).thenReturn(selection("all", "rule", java.util.List.of(value("owns", "BOOLEAN"))));
        assertThatThrownBy(() -> resolver.resolve(jointDefinition())).hasMessageContaining("明确识别客户或坐席");
        when(labels.resolveVersions(any())).thenReturn(selection("customer", "rule", java.util.List.of()));
        assertThatThrownBy(() -> resolver.resolve(jointDefinition())).hasMessageContaining("1 到 10");
    }

    @Test
    void duplicateCodesAndUnsupportedTypesDoNotBecomeModelTargets() {
        when(labels.resolveVersions(any())).thenReturn(selection("customer", "rule", java.util.List.of(value("owns", "BOOLEAN"), value("owns", "BOOLEAN"))));
        assertThatThrownBy(() -> resolver.resolve(jointDefinition())).hasMessageContaining("编码无效或重复");
        when(labels.resolveVersions(any())).thenReturn(selection("customer", "rule", java.util.List.of(value("job", "ENUM"))));
        assertThatThrownBy(() -> resolver.resolve(jointDefinition())).hasMessageContaining("类型不受支持");
    }

    @Test
    void deterministicLabelBindingsRequireOneExplicitTypedLocatedHitMapping() {
        when(labels.resolveVersions(any())).thenReturn(selection("customer", "rule", java.util.List.of(value("owns", "BOOLEAN"))));
        assertThatThrownBy(() -> resolver.resolve(jointDefinition())).hasMessageContaining("仅映射一个命中值");
        var bad = value("owns", "BOOLEAN"); bad.setConfigJson("{\"onRuleHit\":{\"rule\":\"true\"}}");
        when(labels.resolveVersions(any())).thenReturn(selection("customer", "rule", java.util.List.of(bad)));
        assertThatThrownBy(() -> resolver.resolve(jointDefinition())).hasMessageContaining("类型与定义不一致");
        var first = value("yes", "BOOLEAN"); first.setConfigJson("{\"onRuleHit\":{\"rule\":true}}");
        var second = value("no", "BOOLEAN"); second.setConfigJson("{\"onRuleHit\":{\"rule\":false}}");
        when(labels.resolveVersions(any())).thenReturn(selection("customer", "rule", java.util.List.of(first, second)));
        assertThatThrownBy(() -> resolver.resolve(jointDefinition())).hasMessageContaining("仅映射一个命中值");
    }

    @Test
    void deterministicLabelBindingRejectsUnlocatedOrOtherSpeakerRules() {
        var mapped = value("owns", "BOOLEAN"); mapped.setConfigJson("{\"onRuleHit\":{\"rule\":true}}");
        when(labels.resolveVersions(any())).thenReturn(selection("customer", "rule", java.util.List.of(mapped)));
        version.setRuleType("EQUALS");
        assertThatThrownBy(() -> resolver.resolve(jointDefinition())).hasMessageContaining("可定位原文");
        version.setRuleType("REGEX"); version.setTargetRole("agent");
        assertThatThrownBy(() -> resolver.resolve(jointDefinition())).hasMessageContaining("说话人范围不一致");
    }

    private io.github.opensabre.iqc.label.model.LabelValueDefinition value(String code, String type) {
        var value = new io.github.opensabre.iqc.label.model.LabelValueDefinition(); value.setValueCode(code); value.setValueType(type); return value;
    }

    private io.github.opensabre.iqc.label.LabelResolutionService.ResolvedSelection selection(String role, String ruleId,
            java.util.List<io.github.opensabre.iqc.label.model.LabelValueDefinition> values) {
        var binding = new io.github.opensabre.iqc.label.model.LabelRuleBinding(); binding.setRuleId(ruleId); binding.setRuleVersionNo(1);
        var label = new io.github.opensabre.iqc.label.LabelResolutionService.LabelSnapshot("l1", 1, "有房", "house", null,
                null, null, null, null, false, role, null, java.util.List.of(binding), values);
        return new io.github.opensabre.iqc.label.LabelResolutionService.ResolvedSelection("2.0", java.util.List.of(label), java.util.List.of(ruleId));
    }

    private SchemeDefinition jointDefinition() {
        var original = InspectionSchemeServiceTest.definition();
        return new SchemeDefinition(original.schemaVersion(), original.items(), null, original.scoring(), null,
                java.util.List.of(new io.github.opensabre.iqc.label.LabelResolutionService.LabelReference("l1", 1)));
    }

    @BeforeEach
    void setup() {
        current = new QualityRule(); current.setId("rule"); current.setStatus("DRAFT"); current.setExpression("changed draft");
        version = new QualityRuleVersion(); version.setId("version-row"); version.setRuleId("rule");
        version.setVersionNo(1); version.setStatus("PUBLISHED"); version.setRuleType("REGEX");
        version.setExpression("hello"); version.setDeduction(30); version.setVeto(true);
        when(rules.selectById("rule")).thenReturn(current);
        when(versions.selectOne(any(Wrapper.class))).thenReturn(version);
    }

    @Test
    void applicabilityRuleIsFrozenOnceAndHonorsItsKillSwitch() {
        var original = InspectionSchemeServiceTest.definition();
        var item = original.items().getFirst();
        var gate = new SchemeDefinition.RuleReference("gate", 1);
        var conditional = new SchemeDefinition(original.schemaVersion(), java.util.List.of(
                new SchemeDefinition.Item(item.itemCode(), item.name(), item.rule(), item.hitMeaning(), gate),
                new SchemeDefinition.Item("extra", "另一检查", item.rule(), item.hitMeaning(), gate)), null, original.scoring());
        var gateRule = new QualityRule(); gateRule.setId("gate"); gateRule.setStatus("ACTIVE");
        when(rules.selectById("gate")).thenReturn(gateRule);
        var gateVersion = new QualityRuleVersion(); gateVersion.setRuleId("gate"); gateVersion.setId("gate-version");
        gateVersion.setVersionNo(1); gateVersion.setRuleType("REGEX"); gateVersion.setExpression("product");
        gateVersion.setStatus("PUBLISHED"); gateVersion.setDeduction(80); gateVersion.setVeto(true);
        when(versions.selectOne(any(Wrapper.class))).thenReturn(version, gateVersion);
        var resolved = resolver.resolve(conditional);
        assertThat(resolved.rules()).hasSize(2);
        assertThat(resolved.rules().get(1).path("id").asText()).isEqualTo("gate");
        assertThat(resolved.rules().get(1).path("expression").asText()).isEqualTo("product");
        assertThat(resolved.rules().get(1).path("deduction").asInt()).isZero();
        assertThat(resolved.rules().get(1).path("veto").asBoolean()).isFalse();
        gateRule.setStatus("DISABLED");
        assertThatThrownBy(() -> resolver.resolve(conditional)).hasMessageContaining("停用");
    }

    @Test
    void applicabilityCannotHideConflictingVersionsOfACheckDependency() {
        var original = InspectionSchemeServiceTest.definition();
        var item = original.items().getFirst();
        assertThatThrownBy(() -> new SchemeDefinition(original.schemaVersion(), java.util.List.of(
                new SchemeDefinition.Item(item.itemCode(), item.name(), item.rule(), item.hitMeaning(),
                        new SchemeDefinition.RuleReference("gate", 1)),
                new SchemeDefinition.Item("extra", "另一检查", new SchemeDefinition.RuleReference("gate", 2), item.hitMeaning())),
                null, original.scoring())).hasMessageContaining("不同版本");
    }

    @Test
    void mutableDraftDoesNotReplacePublishedDependencyOrItsIdentity() {
        var resolved = resolver.resolve(InspectionSchemeServiceTest.definition());
        assertThat(resolved.executionMode()).isEqualTo("RULE_ONLY");
        assertThat(resolved.agent()).isNull();
        var snapshot = resolved.rules().getFirst();
        assertThat(snapshot.path("id").asText()).isEqualTo("rule");
        assertThat(snapshot.path("expression").asText()).isEqualTo("hello");
        assertThat(snapshot.path("deduction").asInt()).isZero();
        assertThat(snapshot.path("veto").asBoolean()).isFalse();
        assertThat(snapshot.has("labelFactTargets")).isFalse();
        assertThat(version.getDeduction()).isEqualTo(30);
        verifyNoInteractions(agents, agentVersions);
    }

    @Test
    void localAlternativeRetainsSharedCapabilityButFreezesNoExecutedAgent() throws Exception {
        var mapper = new ObjectMapper();
        var base = InspectionSchemeServiceTest.definition();
        var direct = new SchemeDefinition.Execution(SchemeDefinition.Route.RULE_ONLY, null, null, null, null);
        var assisted = new SchemeDefinition.Execution(SchemeDefinition.Route.LLM_THEN_RULE, null,
                new SchemeDefinition.RuleReference("candidate", 1), SchemeDefinition.InputScope.CONVERSATION, null);
        var variants = new SchemeExecutionVariants("local", java.util.List.of(
                new SchemeExecutionVariants.Variant("local", "本地", "完整", "不调用模型", java.util.Map.of("greeting", direct)),
                new SchemeExecutionVariants.Variant("assisted", "辅助", "完整", "调用模型", java.util.Map.of("greeting", assisted))));
        var expanded = variants.expand(base, "local");
        var definition = new SchemeDefinition(base.schemaVersion(), expanded.items(), new SchemeDefinition.AgentReference("agent", 1),
                base.scoring(), null, null, variants);
        var checked = resolver.previewRoutes(definition);
        assertThat(checked.agent()).isNull(); assertThat(checked.executionMode()).isEqualTo("RULE_ONLY");
        assertThat(checked.rules()).hasSize(1);
        verifyNoInteractions(agents, agentVersions, assets);
        var release = new InspectionSchemeService.ReleaseSnapshot("本地", "local", null, "销售", definition.forTaskSnapshot(), checked, "local");
        var root = mapper.createObjectNode(); var scheme = root.putObject("schemeSnapshot");
        var json = mapper.readTree(mapper.writeValueAsString(release));
        scheme.set("release", json); scheme.put("selectedVariantCode", "local");
        scheme.put("contentHash", InspectionSchemeService.contentHash(json.toString())); root.set("rules", json.at("/dependencies/rules"));
        SchemeDependencyResolver.validateRouteTaskProjection(root, null, mapper);
        assertThatThrownBy(() -> SchemeDependencyResolver.validateRouteTaskProjection(root, "{\"id\":\"agent\"}", mapper))
                .hasMessageContaining("纯规则逐项路线不能携带智能体");
    }

    @Test
    void unusedAgentIsStillRejectedWithoutAnApprovedLlmAlternative() {
        var base = InspectionSchemeServiceTest.definition();
        var capability = new SchemeDefinition.AgentReference("agent", 1);
        var ordinary = new SchemeDefinition(base.schemaVersion(), base.items(), capability, base.scoring());
        assertThatThrownBy(() -> resolver.resolve(ordinary)).hasMessageContaining("纯规则方案无需绑定");
        var direct = new SchemeDefinition.Execution(SchemeDefinition.Route.RULE_ONLY, null, null, null, null);
        var variants = new SchemeExecutionVariants("local", java.util.List.of(
                new SchemeExecutionVariants.Variant("local", "本地", "完整", "不调用模型", java.util.Map.of("greeting", direct))));
        var expanded = variants.expand(ordinary, "local");
        assertThatThrownBy(() -> resolver.previewRoutes(expanded)).hasMessageContaining("纯规则方案无需绑定");
        var approved = new SchemeDefinition(base.schemaVersion(), expanded.items(), capability, base.scoring(), null, null, variants);
        assertThatThrownBy(() -> resolver.previewRoutes(approved)).hasMessageContaining("纯规则方案无需绑定");
        verifyNoInteractions(agents, agentVersions, assets);
    }

    @Test
    void absentPublishedDependencyFailsInsteadOfUsingCurrentDraft() {
        when(versions.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> resolver.resolve(InspectionSchemeServiceTest.definition())).hasMessageContaining("未发布");
    }

    @Test
    void disabledDependencyCannotBePublishedInANewScheme() {
        current.setStatus("DISABLED");
        assertThatThrownBy(() -> resolver.resolve(InspectionSchemeServiceTest.definition())).hasMessageContaining("停用");
        verifyNoInteractions(versions);
    }

    @Test
    void llmDependencyRequiresAnExplicitAgent() {
        version.setRuleType("LLM"); version.setExpression("判断是否完成开场白");
        assertThatThrownBy(() -> resolver.resolve(InspectionSchemeServiceTest.definition())).hasMessageContaining("智能体版本");
    }

    @Test
    void taskBoundaryCheckLeavesFrozenConfigurationUntouched() {
        var mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.putObject("schemeSnapshot").put("schemeId", "scheme").putObject("release").set("definition", mapper.valueToTree(InspectionSchemeServiceTest.definition()));
        var scheme = new io.github.opensabre.iqc.scheme.model.InspectionScheme(); scheme.setStatus("ACTIVE");
        when(schemes.selectById("scheme")).thenReturn(scheme);
        root.putArray("rules").addObject().put("expression", "frozen-expression");
        String before = root.toString();
        resolver.validateTaskDependencies(root);
        assertThat(root.toString()).isEqualTo(before);
        current.setStatus("DISABLED");
        assertThatThrownBy(() -> resolver.validateTaskDependencies(root)).hasMessageContaining("停用");
        assertThat(root.toString()).isEqualTo(before);
    }

    @Test
    void disabledOrMissingSchemeBlocksWorkersBeforeResolvingDependencies() {
        var mapper = new ObjectMapper(); var root = mapper.createObjectNode();
        var marker = root.putObject("schemeSnapshot");
        marker.putObject("release").set("definition", mapper.valueToTree(InspectionSchemeServiceTest.definition()));
        assertThatThrownBy(() -> resolver.validateTaskDependencies(root)).hasMessageContaining("缺少业务方案标识");
        marker.put("schemeId", "scheme");
        assertThatThrownBy(() -> resolver.validateTaskDependencies(root)).hasMessageContaining("不存在或已停用");
        var scheme = new io.github.opensabre.iqc.scheme.model.InspectionScheme(); scheme.setStatus("DISABLED");
        when(schemes.selectById("scheme")).thenReturn(scheme);
        assertThatThrownBy(() -> resolver.validateTaskDependencies(root)).hasMessageContaining("不能启动或恢复");
        verifyNoInteractions(rules, versions, agents, agentVersions, assets);
        scheme.setStatus("ACTIVE"); resolver.validateTaskDependencies(root);
        assertThat(root.path("schemeSnapshot").path("release").path("definition")).isEqualTo(mapper.valueToTree(InspectionSchemeServiceTest.definition()));
    }

    @Test
    void legacyTasksSkipNewPolicyButMalformedSchemeMarkerFailsClosed() {
        var mapper = new ObjectMapper();
        resolver.validateTaskDependencies(mapper.createArrayNode());
        resolver.validateTaskDependencies(mapper.createObjectNode().put("legacy", true));
        verifyNoInteractions(rules, versions, agents, agentVersions, assets);
        assertThatThrownBy(() -> resolver.validateTaskDependencies(mapper.createObjectNode().putNull("schemeSnapshot")))
                .hasMessageContaining("禁止回退");
    }

    @Test
    void llmSchemeChecksAssetAvailabilityWithoutReplacingFrozenConfiguration() {
        version.setRuleType("LLM"); version.setExpression("判断开场白");
        var currentAgent = new io.github.opensabre.iqc.agent.model.QualityAgent(); currentAgent.setStatus("DRAFT");
        when(agents.selectById("a1")).thenReturn(currentAgent);
        var releasedAgent = new io.github.opensabre.iqc.agent.model.QualityAgentVersion();
        releasedAgent.setConfigJson("""
                {"schemaVersion":"3.0","systemPrompt":"检查","primaryModelProfileId":"m1","assetSnapshots":{"primaryModel":{"id":"m1"}}}
                """);
        when(agentVersions.selectOne(any(Wrapper.class))).thenReturn(releasedAgent);
        var original = InspectionSchemeServiceTest.definition();
        var definition = new SchemeDefinition(original.schemaVersion(), original.items(), new SchemeDefinition.AgentReference("a1", 1), original.scoring());
        var result = resolver.resolve(definition);
        assertThat(result.executionMode()).isEqualTo("INDEPENDENT");
        assertThat(result.agent().path("configJson").asText()).isEqualTo(releasedAgent.getConfigJson());
        verify(assets).validate(any());
        doThrow(io.github.opensabre.iqc.governance.IqcException.invalidState("模型已停用")).when(assets).validate(any());
        assertThatThrownBy(() -> resolver.resolve(definition)).hasMessageContaining("停用");
    }
}
