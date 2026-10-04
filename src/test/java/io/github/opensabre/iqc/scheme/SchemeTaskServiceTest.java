package io.github.opensabre.iqc.scheme;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Covers retry safety without introducing a second task or idempotency persistence model. */
class SchemeTaskServiceTest {
    private final InspectionSchemeService schemes = mock(InspectionSchemeService.class);
    private final InspectionTaskService tasks = mock(InspectionTaskService.class);
    private final InspectionTaskMapper taskMapper = mock(InspectionTaskMapper.class);
    private final SchemeDependencyResolver dependencies = mock(SchemeDependencyResolver.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final SchemeTaskService service = new SchemeTaskService(schemes, tasks, taskMapper, dependencies, scope, mapper);
    private final String requestId = "stable-request-123456";
    private InspectionSchemeVersion version;
    private InspectionTask created;

    @BeforeEach
    void setup() throws Exception {
        when(scope.owner()).thenReturn("owner");
        when(scope.canView("owner", null)).thenReturn(true);
        version = new InspectionSchemeVersion();
        var release = new InspectionSchemeService.ReleaseSnapshot("方案", "sales", null, "销售", InspectionSchemeServiceTest.definition(),
                new SchemeDependencyResolver.Dependencies(List.of(), null, "RULE_ONLY"));
        version.setSnapshotJson(mapper.writeValueAsString(release));
        version.setContentHash(InspectionSchemeService.contentHash(version.getSnapshotJson()));
        when(schemes.published("s1", 1)).thenReturn(version);
        when(tasks.createFromScheme(eq("任务"), eq(List.of("c1")), eq(1), eq(version), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    created = new InspectionTask(); created.setId(invocation.getArgument(4)); created.setCreatedBy("owner");
                    var root = mapper.createObjectNode();
                    root.putObject("schemeSnapshot").put("requestFingerprint", (String) invocation.getArgument(5));
                    created.setRuleSnapshotJson(root.toString());
                    return created;
                });
    }

    private InspectionTask create() { return service.create("s1", 1, "任务", List.of("c1"), 1, requestId); }

    @Test
    void ordinaryReleaseKeepsItsHistoricalFingerprintAndRejectsVariantOverrides() throws Exception {
        var first = create();
        String historical = InspectionSchemeService.contentHash("{\"schemeId\":\"s1\",\"versionNo\":1,\"name\":\"任务\",\"conversationIds\":[\"c1\"],\"concurrency\":1}");
        assertThat(mapper.readTree(first.getRuleSnapshotJson()).at("/schemeSnapshot/requestFingerprint").asText()).isEqualTo(historical);
        when(taskMapper.selectById(first.getId())).thenReturn(first);
        assertThatThrownBy(() -> service.create("s1", 1, "任务", List.of("c1"), 1, requestId, "standard"))
                .hasMessageContaining("未配置");
        verify(tasks, times(1)).createFromScheme(any(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    void variantsNormalizeBeforeFingerprintAndRetryUsesFrozenRelease() throws Exception {
        var release = variantRelease();
        version.setSnapshotJson(mapper.writeValueAsString(release));
        version.setContentHash(InspectionSchemeService.contentHash(version.getSnapshotJson()));
        when(tasks.createFromScheme(eq("任务"), eq(List.of("c1")), eq(1), eq(version), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> variantTask(release, invocation.getArgument(4), invocation.getArgument(5), invocation.getArgument(6)));
        var first = create();
        when(taskMapper.selectById(first.getId())).thenReturn(first);
        clearInvocations(schemes, dependencies, tasks);
        when(schemes.published("s1", 1)).thenThrow(IqcException.invalidState("模板已停用"));
        assertThat(service.create("s1", 1, "任务", List.of("c1"), 1, requestId, "standard")).isSameAs(first);
        assertThat(create()).isSameAs(first);
        assertThatThrownBy(() -> service.create("s1", 1, "任务", List.of("c1"), 1, requestId, "alternate"))
                .hasMessageContaining("不同任务配置");
        assertThatThrownBy(() -> service.create("s1", 1, "任务", List.of("c1"), 1, requestId, "unknown"))
                .hasMessageContaining("允许变体");
        verifyNoInteractions(schemes, dependencies, tasks);
    }

    @Test
    void unknownVariantIsRejectedButFrozenRoutesCanCreateBatchAndScheduledTasks() throws Exception {
        var release = variantRelease();
        version.setSnapshotJson(mapper.writeValueAsString(release));
        version.setContentHash(InspectionSchemeService.contentHash(version.getSnapshotJson()));
        assertThatThrownBy(() -> service.create("s1", 1, "任务", List.of("c1"), 1, requestId, "unknown"))
                .hasMessageContaining("允许变体");
        verifyNoInteractions(dependencies, tasks);
        doAnswer(invocation -> { invocation.getArgument(0, InspectionSchemeService.ReleaseSnapshot.class).definition().requireExecutable(); return null; })
                .when(dependencies).validateReleasedDependencies(any());
        var routeTask = new InspectionTask(); routeTask.setId("route-task"); routeTask.setCreatedBy("owner");
        when(tasks.createFromScheme(eq("任务"), eq(List.of("c1")), eq(1), eq(version), anyString(), anyString(), eq("standard")))
                .thenReturn(routeTask);
        var due = java.time.LocalDateTime.now().plusHours(1);
        when(tasks.createScheduledFromScheme(eq("任务"), isNull(), eq(due), eq(1), eq(version), anyString(), anyString(), eq("alternate")))
                .thenReturn(routeTask);
        assertThat(service.create("s1", 1, "任务", List.of("c1"), 1, requestId, "standard")).isSameAs(routeTask);
        assertThat(service.createScheduled("s1", 1, "任务", null, due, 1, requestId, "alternate")).isSameAs(routeTask);
        verify(tasks).createFromScheme(eq("任务"), eq(List.of("c1")), eq(1), eq(version), anyString(), anyString(), eq("standard"));
        verify(tasks).createScheduledFromScheme(eq("任务"), isNull(), eq(due), eq(1), eq(version), anyString(), anyString(), eq("alternate"));
    }

    @Test
    void scheduledVariantRetriesNormalizeCodeAndBindOriginalSchedule() throws Exception {
        var release = variantRelease();
        version.setSnapshotJson(mapper.writeValueAsString(release));
        version.setContentHash(InspectionSchemeService.contentHash(version.getSnapshotJson()));
        var due = java.time.LocalDateTime.of(2030, 1, 1, 10, 0);
        when(tasks.createScheduledFromScheme(eq("任务"), isNull(), eq(due), eq(1), eq(version), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> variantTask(release, invocation.getArgument(5), invocation.getArgument(6), invocation.getArgument(7)));
        var first = service.createScheduled("s1", 1, "任务", null, due, 1, requestId);
        when(taskMapper.selectById(first.getId())).thenReturn(first);
        clearInvocations(schemes, dependencies, tasks);
        assertThat(service.createScheduled("s1", 1, "任务", null, due, 1, requestId, "standard")).isSameAs(first);
        assertThatThrownBy(() -> service.createScheduled("s1", 1, "任务", null, due.plusHours(1), 1, requestId, "standard"))
                .hasMessageContaining("不同任务配置");
        assertThatThrownBy(() -> service.create("s1", 1, "任务", List.of("c1"), 1, requestId, "standard"))
                .hasMessageContaining("不同任务配置");
        verifyNoInteractions(schemes, dependencies, tasks);
    }

    @Test
    void corruptedReleaseIsRejectedBeforeDependencyLookupsOrTaskWrites() {
        version.setContentHash("tampered");
        assertThatThrownBy(this::create).hasMessageContaining("校验失败");
        verifyNoInteractions(dependencies, tasks);
    }

    @Test
    void malformedExistingReleaseCannotProduceANullPointerOrNewTask() throws Exception {
        var first = create();
        var root = mapper.readTree(first.getRuleSnapshotJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("schemeSnapshot")).putObject("release");
        first.setRuleSnapshotJson(root.toString());
        when(taskMapper.selectById(first.getId())).thenReturn(first);
        clearInvocations(schemes, dependencies, tasks);
        assertThatThrownBy(this::create).isInstanceOf(IqcException.class).hasMessageContaining("缺少方案定义");
        verifyNoInteractions(schemes, dependencies, tasks);
    }

    private InspectionTask variantTask(InspectionSchemeService.ReleaseSnapshot release, String id, String fingerprint, String code) throws Exception {
        var selected = release.selectVariant(code).forTaskSnapshot();
        var task = new InspectionTask(); task.setId(id); task.setCreatedBy("owner");
        var root = mapper.createObjectNode(); var scheme = root.putObject("schemeSnapshot");
        var json = mapper.readTree(mapper.writeValueAsString(selected));
        scheme.set("release", json); scheme.put("selectedVariantCode", code);
        scheme.put("requestFingerprint", fingerprint); scheme.put("contentHash", InspectionSchemeService.contentHash(json.toString()));
        root.set("rules", json.at("/dependencies/rules")); task.setRuleSnapshotJson(root.toString());
        return task;
    }

    private InspectionSchemeService.ReleaseSnapshot variantRelease() {
        var base = InspectionSchemeServiceTest.definition();
        var execution = new SchemeDefinition.Execution(SchemeDefinition.Route.RULE_ONLY, null, null, null, null);
        var variants = new SchemeExecutionVariants("standard", List.of(
                new SchemeExecutionVariants.Variant("standard", "标准", "完整", "本地", java.util.Map.of("greeting", execution)),
                new SchemeExecutionVariants.Variant("alternate", "备选", "完整", "本地", java.util.Map.of("greeting", execution))));
        var expanded = variants.expand(base, "standard");
        var definition = new SchemeDefinition(base.schemaVersion(), expanded.items(), null, base.scoring(), null, null, variants);
        var rules = mock(io.github.opensabre.iqc.rule.dao.QualityRuleMapper.class);
        var versions = mock(io.github.opensabre.iqc.rule.dao.QualityRuleVersionMapper.class);
        var rule = new io.github.opensabre.iqc.rule.model.QualityRule(); rule.setStatus("PUBLISHED");
        when(rules.selectById("rule")).thenReturn(rule);
        var detector = new io.github.opensabre.iqc.rule.model.QualityRuleVersion();
        detector.setRuleId("rule"); detector.setVersionNo(1); detector.setStatus("PUBLISHED");
        detector.setRuleType("KEYWORD"); detector.setExpression("你好"); detector.setTargetRole("all");
        when(versions.selectOne(any())).thenReturn(detector);
        var resolver = new SchemeDependencyResolver(rules, versions,
                mock(io.github.opensabre.iqc.agent.dao.QualityAgentMapper.class), mock(io.github.opensabre.iqc.agent.dao.QualityAgentVersionMapper.class),
                mapper, mock(io.github.opensabre.iqc.agent.AgentAssetReferenceValidator.class),
                mock(io.github.opensabre.iqc.scheme.dao.InspectionSchemeMapper.class), mock(io.github.opensabre.iqc.label.LabelResolutionService.class));
        var checked = resolver.previewRoutes(expanded);
        return new InspectionSchemeService.ReleaseSnapshot("方案", "sales", null, "销售", definition, checked, "standard",
                java.util.Map.of("standard", checked, "alternate", checked));
    }

    @Test
    void retryReturnsSameTaskWithoutResolvingOrCreatingAgain() {
        var first = create();
        assertThat(first.getId()).hasSize(63).startsWith("st-");
        when(taskMapper.selectById(first.getId())).thenReturn(first);
        assertThat(create()).isSameAs(first);
        verify(dependencies, times(1)).validateReleasedDependencies(any());
        verify(tasks, times(1)).createFromScheme(any(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    void sameKeyWithChangedDataIsRejected() {
        var first = create(); when(taskMapper.selectById(first.getId())).thenReturn(first);
        assertThatThrownBy(() -> service.create("s1", 1, "任务", List.of("c2"), 1, requestId)).hasMessageContaining("不同任务配置");
        verify(tasks, times(1)).createFromScheme(any(), any(), any(), any(), anyString(), anyString());
    }

    @Test
    void disabledDependencyPreventsNewTask() {
        doThrow(IqcException.invalidState("规则已停用")).when(dependencies).validateReleasedDependencies(any());
        assertThatThrownBy(this::create).hasMessageContaining("停用");
        verifyNoInteractions(tasks);
    }

    @Test
    void racingInsertReturnsExistingTaskAfterInnerTransactionRollback() {
        var first = create();
        when(taskMapper.selectById(first.getId())).thenReturn(null, first);
        when(tasks.createFromScheme(any(), any(), any(), any(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("concurrent insert"));
        assertThat(create()).isSameAs(first);
    }

    @Test
    void inaccessibleExistingTaskAndInvalidKeyAreRejected() {
        assertThatThrownBy(() -> service.create("s1", 1, "任务", List.of("c1"), 1, "short")).hasMessageContaining("标识");
        var first = create(); when(taskMapper.selectById(first.getId())).thenReturn(first);
        when(scope.canView("owner", null)).thenReturn(false);
        assertThatThrownBy(this::create).hasMessageContaining("无权");
    }
}
