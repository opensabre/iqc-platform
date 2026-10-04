package io.github.opensabre.iqc.scheme;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.scheme.dao.InspectionSchemeMapper;
import io.github.opensabre.iqc.scheme.dao.InspectionSchemeVersionMapper;
import io.github.opensabre.iqc.scheme.model.InspectionScheme;
import io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InspectionSchemeServiceTest {
    private final InspectionSchemeMapper schemes = mock(InspectionSchemeMapper.class);
    private final InspectionSchemeVersionMapper versions = mock(InspectionSchemeVersionMapper.class);
    private final SchemeDependencyResolver dependencies = mock(SchemeDependencyResolver.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final InspectionSchemeService service = new InspectionSchemeService(schemes, versions, dependencies, scope, mapper);
    private InspectionScheme scheme;

    static SchemeDefinition definition() {
        return new SchemeDefinition(SchemeDefinition.SCHEMA,
                List.of(new SchemeDefinition.Item("greeting", "开场白", new SchemeDefinition.RuleReference("rule", 1),
                        SchemeDefinition.HitMeaning.COMPLIANCE)), null,
                new InspectionScoring.Policy("iqc-score-v2", InspectionScoring.Mode.DEDUCTION, 100, 60,
                        List.of(new InspectionScoring.Item("greeting", 10, false))));
    }

    @BeforeEach
    void setup() throws Exception {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "scheme-history"),
                InspectionSchemeVersion.class);
        scheme = new InspectionScheme();
        scheme.setId("scheme"); scheme.setCode("sales"); scheme.setName("电话销售");
        scheme.setBusinessScene("销售"); scheme.setStatus("ACTIVE"); scheme.setDraftRevision(2);
        scheme.setActivePublishedVersion(1); scheme.setCreatedBy("owner"); scheme.setOwnerGroupId("team");
        scheme.setDraftConfigJson(mapper.writeValueAsString(definition()));
        when(scope.canView("owner", "team")).thenReturn(true);
        when(schemes.selectById("scheme")).thenReturn(scheme);
        when(schemes.selectOne(any(Wrapper.class))).thenReturn(scheme);
    }

    @Test
    void editingDraftPreservesPublishedPointerAndDoesNotWriteVersions() {
        var revised = service.revise("scheme", 2,
                new InspectionSchemeService.DraftRequest("新标题", "sales", null, "销售", definition()));
        assertThat(revised.getDraftRevision()).isEqualTo(3);
        assertThat(revised.getActivePublishedVersion()).isEqualTo(1);
        verifyNoInteractions(versions, dependencies);
    }

    @Test
    void previewValidatesEveryApprovedAlternativeAndFreezesTheSelectedCode() throws Exception {
        var original = definition();
        var direct = new SchemeDefinition.Execution(SchemeDefinition.Route.RULE_ONLY, null, null, null, null);
        var candidate = new SchemeDefinition.Execution(SchemeDefinition.Route.LLM_THEN_RULE, null,
                new SchemeDefinition.RuleReference("candidate", 1), null, null);
        var variants = new SchemeExecutionVariants("standard", List.of(
                new SchemeExecutionVariants.Variant("standard", "标准", "全部项目", "本地规则", java.util.Map.of("greeting", direct)),
                new SchemeExecutionVariants.Variant("candidate", "候选核查", "全部项目", "增加模型调用", java.util.Map.of("greeting", candidate))));
        var authored = new SchemeDefinition(original.schemaVersion(), original.items(), original.agent(), original.scoring(), null, null, variants);
        scheme.setDraftConfigJson(mapper.writeValueAsString(authored));
        when(dependencies.previewRoutes(any())).thenAnswer(invocation -> {
            var expanded = invocation.getArgument(0, SchemeDefinition.class);
            return new SchemeDependencyResolver.Dependencies(List.of(mapper.valueToTree(expanded.items().getFirst().execution())), null, "RULE_ONLY");
        });
        var selected = service.preview("scheme", 2, "candidate");
        assertThat(selected.selectedVariantCode()).isEqualTo("candidate");
        assertThat(selected.definition().items().getFirst().execution()).isEqualTo(candidate);
        assertThat(selected.definition().executionVariants()).isEqualTo(variants);
        assertThat(selected.definition().scoring()).isEqualTo(original.scoring());
        assertThat(selected.variantDependencies().keySet()).containsExactly("standard", "candidate");
        assertThat(selected.variantDependencies().get("candidate")).isEqualTo(selected.dependencies());
        assertThat(selected.variantDependencies().get("standard")).isNotEqualTo(selected.dependencies());
        clearInvocations(dependencies);
        var recommended = selected.selectVariant(null);
        assertThat(recommended.selectedVariantCode()).isEqualTo("standard");
        assertThat(recommended.definition().items().getFirst().execution()).isEqualTo(direct);
        assertThat(recommended.dependencies()).isEqualTo(selected.variantDependencies().get("standard"));
        assertThat(recommended.definition().scoring()).isEqualTo(selected.definition().scoring());
        assertThat(recommended.variantDependencies()).isEqualTo(selected.variantDependencies());
        assertThat(recommended.selectVariant("candidate")).isEqualTo(selected);
        assertThat(selected.selectVariant("candidate")).isSameAs(selected);
        assertThatThrownBy(() -> selected.selectVariant("unknown")).hasMessageContaining("允许变体");
        assertThatThrownBy(() -> selected.selectVariant("")).hasMessageContaining("允许变体");
        verifyNoInteractions(dependencies);
        String frozenJson = mapper.writeValueAsString(selected);
        assertThat(mapper.writeValueAsString(mapper.readValue(frozenJson, InspectionSchemeService.ReleaseSnapshot.class)))
                .isEqualTo(frozenJson);
        assertThatThrownBy(() -> selected.variantDependencies().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new InspectionSchemeService.ReleaseSnapshot(selected.name(), selected.code(), null, selected.businessScene(),
                selected.definition(), selected.dependencies(), "candidate", java.util.Map.of("candidate", selected.dependencies())))
                .hasMessageContaining("不完整");
        assertThatThrownBy(() -> new InspectionSchemeService.ReleaseSnapshot(selected.name(), selected.code(), null, selected.businessScene(),
                selected.definition(), selected.variantDependencies().get("standard"), "candidate", selected.variantDependencies()))
                .hasMessageContaining("不一致");
        var historical = new InspectionSchemeService.ReleaseSnapshot(selected.name(), selected.code(), null, selected.businessScene(),
                selected.definition(), selected.dependencies(), "candidate");
        assertThat(mapper.readTree(mapper.writeValueAsString(historical)).has("variantDependencies")).isFalse();
        assertThat(historical.selectVariant("candidate")).isSameAs(historical);
        assertThatThrownBy(() -> historical.selectVariant(null)).hasMessageContaining("历史模板未冻结");
        clearInvocations(dependencies);
        service.preview("scheme", 2, "candidate");
        verify(dependencies, times(2)).previewRoutes(any());
        clearInvocations(dependencies);
        assertThat(service.preview("scheme", 2).selectedVariantCode()).isEqualTo("standard");
        assertThatThrownBy(() -> service.preview("scheme", 2, "unknown")).hasMessageContaining("允许变体");
        verify(dependencies, times(2)).previewRoutes(any());
        doThrow(io.github.opensabre.iqc.governance.IqcException.invalidState("候选依赖已停用"))
                .when(dependencies).previewRoutes(argThat(value -> value.items().getFirst().execution().equals(candidate)));
        assertThatThrownBy(() -> service.preview("scheme", 2)).hasMessageContaining("已停用");
        verifyNoInteractions(versions);
    }

    @Test
    void ordinaryReleaseRejectsVariantOverridesWithoutChangingItsSnapshot() throws Exception {
        var release = new InspectionSchemeService.ReleaseSnapshot("方案", "sales", null, "销售", definition(),
                new SchemeDependencyResolver.Dependencies(List.of(), null, "RULE_ONLY"));
        String json = mapper.writeValueAsString(release);
        assertThat(release.selectVariant(null)).isSameAs(release);
        assertThatThrownBy(() -> release.selectVariant("standard")).hasMessageContaining("未配置");
        assertThat(mapper.writeValueAsString(release)).isEqualTo(json);
        verifyNoInteractions(dependencies);
    }

    private InspectionSchemeVersion historicalVersion(int versionNo) throws Exception {
        var release = new InspectionSchemeService.ReleaseSnapshot("冻结名称", "sales", "发布时说明", "销售", definition(),
                new SchemeDependencyResolver.Dependencies(List.of(), null, "RULE_ONLY"));
        var version = new InspectionSchemeVersion();
        version.setSchemeId("scheme"); version.setVersionNo(versionNo); version.setSourceDraftRevision(2);
        version.setSourceTrialTaskId("trial-old"); version.setSnapshotJson(mapper.writeValueAsString(release));
        version.setContentHash(InspectionSchemeService.contentHash(version.getSnapshotJson()));
        return version;
    }

    @Test
    void disabledHistoryShowsFrozenStandardWithoutMutableDependencyLookup() throws Exception {
        scheme.setStatus("DISABLED"); scheme.setName("当前新名称");
        when(versions.selectList(any())).thenReturn(List.of(historicalVersion(1)));
        var history = service.history("scheme", null);
        assertThat(history.versions()).hasSize(1);
        var version = history.versions().getFirst();
        assertThat(version.snapshot().name()).isEqualTo("冻结名称");
        assertThat(version.sourceDraftRevision()).isEqualTo(2);
        assertThat(version.sourceTrialTaskId()).isEqualTo("trial-old");
        assertThat(history.nextBeforeVersion()).isNull();
        verifyNoInteractions(dependencies);
        verify(versions, never()).insert(any(InspectionSchemeVersion.class));
        assertThat(scheme.getActivePublishedVersion()).isEqualTo(1);
    }

    @Test
    void historyUsesBoundedExclusiveVersionCursor() throws Exception {
        var rows = new java.util.ArrayList<InspectionSchemeVersion>();
        for (int number = 25; number >= 5; number--) rows.add(historicalVersion(number));
        when(versions.selectList(any())).thenReturn(rows);
        var history = service.history("scheme", 26);
        assertThat(history.versions()).hasSize(20);
        assertThat(history.versions().getFirst().versionNo()).isEqualTo(25);
        assertThat(history.nextBeforeVersion()).isEqualTo(6);
        var query = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(versions).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("scheme_id", "version_no <", "ORDER BY version_no DESC", "LIMIT 21");
    }

    @Test
    void historyRejectsUnauthorizedAccessBeforeVersionQueryAndRejectsBadCursor() {
        when(scope.canView("owner", "team")).thenReturn(false);
        assertThatThrownBy(() -> service.history("scheme", null)).hasMessageContaining("无权");
        verifyNoInteractions(versions);
        when(scope.canView("owner", "team")).thenReturn(true);
        assertThatThrownBy(() -> service.history("scheme", 0)).hasMessageContaining("游标");
        verifyNoInteractions(versions);
    }

    @Test
    void archiveAndRestoreOnlyChangeSupersededVersionMetadata() throws Exception {
        scheme.setActivePublishedVersion(3);
        var version = historicalVersion(2);
        String snapshot = version.getSnapshotJson();
        when(versions.selectOne(any(Wrapper.class))).thenReturn(version);

        assertThat(service.changeVersionArchive("scheme", 2, 2, true)).isSameAs(version);
        assertThat(version.getArchived()).isTrue();
        assertThat(version.getSnapshotJson()).isEqualTo(snapshot);
        assertThat(scheme.getActivePublishedVersion()).isEqualTo(3);
        assertThat(scheme.getDraftRevision()).isEqualTo(2);
        verify(versions).updateById(version);
        verify(schemes, never()).updateById(any(InspectionScheme.class));

        clearInvocations(versions);
        assertThat(service.changeVersionArchive("scheme", 2, 2, false)).isSameAs(version);
        assertThat(version.getArchived()).isFalse();
        assertThat(version.getSnapshotJson()).isEqualTo(snapshot);
        assertThat(scheme.getActivePublishedVersion()).isEqualTo(3);
        verify(versions).updateById(version);
        verify(schemes, never()).updateById(any(InspectionScheme.class));
    }

    @Test
    void currentOrNewerVersionCannotBeArchivedAndStaleRevisionCannotChangeArchiveState() throws Exception {
        scheme.setActivePublishedVersion(3);
        assertThatThrownBy(() -> service.changeVersionArchive("scheme", 2, 3, true)).hasMessageContaining("已被新版本取代");
        assertThatThrownBy(() -> service.changeVersionArchive("scheme", 2, 4, true)).hasMessageContaining("已被新版本取代");
        assertThatThrownBy(() -> service.changeVersionArchive("scheme", 1, 2, true)).hasMessageContaining("刷新");
        verify(versions, never()).insert(any(InspectionSchemeVersion.class));
        verify(schemes, never()).updateById(any(InspectionScheme.class));
    }

    @Test
    void archivedVersionCannotSeedNewTasksOrDerivedDraftsButRemainsInExpertHistory() throws Exception {
        scheme.setActivePublishedVersion(3);
        var version = historicalVersion(2);
        version.setArchived(true);
        when(versions.selectOne(any(Wrapper.class))).thenReturn(version);
        assertThatThrownBy(() -> service.published("scheme", 2)).hasMessageContaining("已归档");
        assertThatThrownBy(() -> service.create(new InspectionSchemeService.DraftRequest(
                "派生方案", "derived", null, null, null, "scheme", 2))).hasMessageContaining("已归档");

        when(versions.selectList(any(Wrapper.class))).thenReturn(List.of(version));
        var history = service.history("scheme", null);
        assertThat(history.versions()).singleElement().extracting(InspectionSchemeService.ReleasedVersion::archived).isEqualTo(true);
        verifyNoInteractions(dependencies);
    }

    @Test
    void ordinaryPublishedHistoryQueryExcludesArchivedRows() throws Exception {
        scheme.setActivePublishedVersion(3);
        when(versions.selectList(any(Wrapper.class))).thenReturn(List.of(historicalVersion(3)));
        service.publishedHistory("scheme", null);
        var query = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        verify(versions).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("archived =", "scheme_id");
    }

    @Test
    void historyDoesNotPresentTamperedOrMalformedSnapshotsAsStandards() throws Exception {
        var version = historicalVersion(1);
        version.setSnapshotJson(version.getSnapshotJson().replace("冻结名称", "篡改名称"));
        when(versions.selectList(any())).thenReturn(List.of(version));
        assertThatThrownBy(() -> service.history("scheme", null)).hasMessageContaining("校验失败");
        version.setSnapshotJson("{}"); version.setContentHash(InspectionSchemeService.contentHash("{}"));
        assertThatThrownBy(() -> service.history("scheme", null)).hasMessageContaining("快照无效");
    }

    @Test
    void jointDraftCanBeSavedAndValidatedForFormalExecution() throws Exception {
        var original = definition();
        var joint = new SchemeDefinition(original.schemaVersion(), original.items(), null, original.scoring(), null,
                List.of(new io.github.opensabre.iqc.label.LabelResolutionService.LabelReference("l1", 1)));
        service.revise("scheme", 2, new InspectionSchemeService.DraftRequest("联合方案", "sales", null, "销售", joint));
        var saved = mapper.readValue(scheme.getDraftConfigJson(), SchemeDefinition.class);
        assertThat(saved.labels()).hasSize(1);
        assertThatCode(saved::requireExecutable).doesNotThrowAnyException();
    }

    @Test
    void applicabilityGateIsPreservedAndCanBeUsedByFormalExecution() throws Exception {
        var original = definition();
        var item = original.items().getFirst();
        var conditional = new SchemeDefinition(original.schemaVersion(), List.of(new SchemeDefinition.Item(item.itemCode(),
                item.name(), item.rule(), item.hitMeaning(), new SchemeDefinition.RuleReference("gate", 1))), null, original.scoring());
        service.revise("scheme", 2, new InspectionSchemeService.DraftRequest("条件方案", "sales", null, "销售", conditional));
        assertThat(mapper.readValue(scheme.getDraftConfigJson(), SchemeDefinition.class).items().getFirst().appliesWhen())
                .isEqualTo(new SchemeDefinition.RuleReference("gate", 1));
        assertThatCode(() -> mapper.readValue(scheme.getDraftConfigJson(), SchemeDefinition.class).requireExecutable())
                .doesNotThrowAnyException();
    }

    @Test
    void labelOnlyDraftHasNoDummyCheckOrScoreAndCanBeUsedByFormalExecution() throws Exception {
        var policy = new InspectionScoring.Policy("iqc-score-v2", InspectionScoring.Mode.DEDUCTION, 100, 60, List.of());
        var labels = List.of(new io.github.opensabre.iqc.label.LabelResolutionService.LabelReference("l1", 1));
        var labelOnly = new SchemeDefinition(SchemeDefinition.SCHEMA, List.of(), null, policy, null, labels);
        service.revise("scheme", 2, new InspectionSchemeService.DraftRequest("画像方案", "sales", null, "销售", labelOnly));
        var saved = mapper.readValue(scheme.getDraftConfigJson(), SchemeDefinition.class);
        assertThat(saved.items()).isEmpty();
        assertThat(saved.scoring().items()).isEmpty();
        assertThat(SchemeResultEvaluator.scoreItems(saved, List.of()).scoring().scoreStatus())
                .isEqualTo(InspectionScoring.ScoreStatus.NOT_APPLICABLE);
        assertThat(SchemeResultEvaluator.scoreItems(saved, List.of()).scoring().finalScore()).isNull();
        assertThatCode(saved::requireExecutable).doesNotThrowAnyException();
        assertThatThrownBy(() -> new SchemeDefinition(SchemeDefinition.SCHEMA, List.of(), null, policy))
                .hasMessageContaining("质检项或画像标签");
    }

    @Test
    void availabilityPreservesFrozenVersionsAndRetriesWithoutAnotherWrite() {
        String draft = scheme.getDraftConfigJson();
        assertThat(service.changeAvailability("scheme", 2, false).getStatus()).isEqualTo("DISABLED");
        assertThat(scheme.getDraftRevision()).isEqualTo(3);
        assertThat(service.changeAvailability("scheme", 2, false)).isSameAs(scheme);
        assertThat(scheme.getActivePublishedVersion()).isEqualTo(1);
        assertThat(scheme.getDraftConfigJson()).isEqualTo(draft);
        verify(schemes, times(1)).updateById(scheme);
        assertThatThrownBy(() -> service.published("scheme", 1)).hasMessageContaining("停用");
        assertThatThrownBy(() -> service.preview("scheme", 3)).hasMessageContaining("停用");
        assertThatThrownBy(() -> service.publishValidated("scheme", 3, "trial", "hash")).hasMessageContaining("停用");
        service.changeAvailability("scheme", 3, true);
        assertThat(scheme.getDraftRevision()).isEqualTo(4);
        assertThat(scheme.getStatus()).isEqualTo("ACTIVE");
        assertThat(scheme.getActivePublishedVersion()).isEqualTo(1);
        verifyNoInteractions(versions, dependencies);
    }

    @Test
    void staleAvailabilityChangesAndUnauthorizedRequestsCannotOverwriteNewState() {
        service.changeAvailability("scheme", 2, false);
        service.changeAvailability("scheme", 3, true);
        assertThatThrownBy(() -> service.changeAvailability("scheme", 2, false)).hasMessageContaining("刷新");
        when(scope.canView("owner", "team")).thenReturn(false);
        assertThatThrownBy(() -> service.changeAvailability("scheme", 4, false)).hasMessageContaining("无权");
        assertThat(scheme.getStatus()).isEqualTo("ACTIVE");
        verify(schemes, times(2)).updateById(scheme);
    }

    @Test
    void disabledTemplatesAreHiddenButTheirDraftsRemainEditable() {
        scheme.setStatus("DISABLED");
        when(scope.canViewAll()).thenReturn(true);
        when(schemes.selectList(any(Wrapper.class))).thenReturn(List.of(scheme));
        assertThat(service.templates()).isEmpty();
        service.revise("scheme", 2, new InspectionSchemeService.DraftRequest("修正标准", "sales", null, "销售", definition()));
        assertThat(scheme.getStatus()).isEqualTo("DISABLED");
        assertThat(scheme.getActivePublishedVersion()).isEqualTo(1);
        verifyNoInteractions(versions, dependencies);
    }

    @Test
    void staleEditAndPublishAreRejectedBeforeDependencyResolution() {
        assertThatThrownBy(() -> service.revise("scheme", 1,
                new InspectionSchemeService.DraftRequest("标题", "sales", null, "销售", definition())))
                .hasMessageContaining("刷新");
        assertThatThrownBy(() -> service.publishValidated("scheme", 1, "trial", "hash")).hasMessageContaining("刷新");
        verifyNoInteractions(versions, dependencies);
        verify(schemes, never()).updateById(any(InspectionScheme.class));
    }

    @Test
    void repeatedPublicationReturnsSameReleaseWithoutResolvingMutableDependencies() {
        var existing = new InspectionSchemeVersion(); existing.setVersionNo(2);
        when(versions.selectOne(any(Wrapper.class))).thenReturn(existing);
        assertThat(service.publishValidated("scheme", 2, "trial", "hash")).isSameAs(existing);
        verifyNoInteractions(dependencies);
        verify(versions, never()).insert(any(InspectionSchemeVersion.class));
    }

    @Test
    void publicationFreezesDefinitionDependenciesAndHash() throws Exception {
        when(dependencies.resolve(any())).thenReturn(new SchemeDependencyResolver.Dependencies(List.of(), null, "RULE_ONLY"));
        var hash = InspectionSchemeService.contentHash(mapper.writeValueAsString(service.preview("scheme", 2)));
        var release = service.publishValidated("scheme", 2, "trial", hash);
        assertThat(release.getSourceTrialTaskId()).isEqualTo("trial");
        assertThat(release.getVersionNo()).isEqualTo(2);
        assertThat(release.getSourceDraftRevision()).isEqualTo(2);
        assertThat(release.getContentHash()).matches("[0-9a-f]{64}");
        var json = mapper.readTree(release.getSnapshotJson());
        assertThat(json.path("definition").path("items").get(0).path("rule").path("versionNo").asInt()).isEqualTo(1);
        assertThat(scheme.getActivePublishedVersion()).isEqualTo(2);
        verify(versions).insert(release);
        verify(schemes).updateById(scheme);
    }

    @Test
    void inaccessibleSchemeCannotPublishOrReadRelease() {
        when(scope.canView("owner", "team")).thenReturn(false);
        assertThatThrownBy(() -> service.publishValidated("scheme", 2, "trial", "hash")).hasMessageContaining("无权");
        assertThatThrownBy(() -> service.published("scheme", 1)).hasMessageContaining("无权");
        verifyNoInteractions(versions, dependencies);
    }

    @Test
    void missingExactReleaseDoesNotFallBackToActiveVersion() {
        assertThatThrownBy(() -> service.published("scheme", 99)).hasMessageContaining("不存在");
        verifyNoInteractions(dependencies);
    }

    @Test
    void templateListShowsReleasedMetadataRatherThanDraftEdits() throws Exception {
        var snapshot = new InspectionSchemeService.ReleaseSnapshot("已发布标题", "sales", "已发布说明", "销售",
                definition(), new SchemeDependencyResolver.Dependencies(List.of(), null, "RULE_ONLY"));
        var release = new InspectionSchemeVersion();
        release.setVersionNo(1); release.setSnapshotJson(mapper.writeValueAsString(snapshot)); release.setContentHash("hash");
        scheme.setName("未发布标题");
        when(scope.canViewAll()).thenReturn(true);
        when(schemes.selectList(any(Wrapper.class))).thenReturn(List.of(scheme));
        when(versions.selectOne(any(Wrapper.class))).thenReturn(release);
        var template = service.templates().getFirst();
        assertThat(template.snapshot().name()).isEqualTo("已发布标题");
        assertThat(template.versionNo()).isEqualTo(1);
        verifyNoInteractions(dependencies);
    }

    @Test
    void previewDoesNotPublishOrAdvancePointer() {
        when(dependencies.resolve(any())).thenReturn(new SchemeDependencyResolver.Dependencies(List.of(), null, "RULE_ONLY"));
        assertThat(service.preview("scheme", 2).name()).isEqualTo("电话销售");
        assertThat(scheme.getActivePublishedVersion()).isEqualTo(1);
        verifyNoInteractions(versions);
        verify(schemes, never()).updateById(any(InspectionScheme.class));
    }

    @Test
    void routedPreviewDoesNotChangePublicationAndStaleHashCannotPublish() throws Exception {
        var old = definition(); var item = old.items().getFirst();
        var routed = new SchemeDefinition(old.schemaVersion(), List.of(new SchemeDefinition.Item(item.itemCode(), item.name(),
                item.rule(), item.hitMeaning(), null, null,
                new SchemeDefinition.Execution(SchemeDefinition.Route.RULE_ONLY, null, null, null, null))), null, old.scoring());
        scheme.setDraftConfigJson(mapper.writeValueAsString(routed));
        var plan = new SchemeDependencyResolver.RoutePlan("iqc-item-execution-plan-v1", List.of(), List.of());
        when(dependencies.previewRoutes(routed)).thenReturn(new SchemeDependencyResolver.Dependencies(List.of(), null, "RULE_ONLY", null, plan));
        assertThat(service.preview("scheme", 2).dependencies().itemExecutionPlan()).isEqualTo(plan);
        assertThat(scheme.getActivePublishedVersion()).isEqualTo(1);
        assertThatThrownBy(() -> service.publishValidated("scheme", 2, "trial", "hash")).hasMessageContaining("方案或依赖已变化");
        verify(dependencies, never()).resolve(any());
        verify(versions, never()).insert(any(InspectionSchemeVersion.class));
        verify(schemes, never()).updateById(any(InspectionScheme.class));
    }

    @Test
    void changedDependenciesAndMissingTrialCannotBypassPublicationGate() {
        when(dependencies.resolve(any())).thenReturn(new SchemeDependencyResolver.Dependencies(List.of(), null, "RULE_ONLY"));
        assertThatThrownBy(() -> service.publishValidated("scheme", 2, "trial", "stale-hash"))
                .hasMessageContaining("重新试跑");
        assertThatThrownBy(() -> service.publishValidated("scheme", 2, null, null)).hasMessageContaining("试跑记录");
        verify(versions, never()).insert(any(InspectionSchemeVersion.class));
        verify(schemes, never()).updateById(any(InspectionScheme.class));
    }

    @Test
    void invalidScoreReferenceAndDuplicateItemAreRejected() {
        var original = definition();
        assertThatThrownBy(() -> new SchemeDefinition(SchemeDefinition.SCHEMA,
                List.of(original.items().getFirst(), original.items().getFirst()), null, original.scoring()))
                .hasMessageContaining("重复");
        var wrong = new InspectionScoring.Policy("iqc-score-v2", InspectionScoring.Mode.DEDUCTION, 100, 60,
                List.of(new InspectionScoring.Item("missing", 10, false)));
        assertThatThrownBy(() -> new SchemeDefinition(SchemeDefinition.SCHEMA, original.items(), null, wrong))
                .hasMessageContaining("评分项目");
    }
}
