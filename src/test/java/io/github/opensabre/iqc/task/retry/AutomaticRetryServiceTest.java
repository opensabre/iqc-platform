package io.github.opensabre.iqc.task.retry;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.result.llm.gateway.GatewayCallService;
import io.github.opensabre.iqc.schedule.*;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.*;
import io.github.opensabre.iqc.task.model.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Policy and fresh-authorization decisions; persistence and remote identity are isolated dependencies. */
class AutomaticRetryServiceTest {
    private final InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
    private final TaskExecutionMapper executions = mock(TaskExecutionMapper.class);
    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final GatewayCallService calls = mock(GatewayCallService.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final InspectionExecutionService queue = mock(InspectionExecutionService.class);
    private final TriggerAttemptService attempts = mock(TriggerAttemptService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private AutomaticRetryService retries;
    private InspectionTask task;
    private TaskExecution failed;
    private AutomaticRetryService.Envelope envelope;
    private Instant now;
    private ScheduledActorAccessService.CreationBound bound;
    private static final Set<String> GRANTS = Set.of(AutomaticRetryService.CONFIGURE, "iqc:pipeline:task:create", "iqc:task:execute");

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T service) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(service);
        return provider;
    }

    @BeforeEach
    void fixture() throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        io.github.opensabre.common.core.util.UserContextHolder.getInstance().clear();
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "retry-test");
        TableInfoHelper.initTableInfo(assistant, InspectionTask.class);
        task = new InspectionTask(); task.setId("task"); task.setCreatedBy("alice"); task.setTaskType("PIPELINE");
        task.setStatus("FAILED"); task.setCurrentExecutionId("failed-eid"); task.setAttemptCount(1);
        task.setAutomaticRetryEnabled(true); task.setRuleSnapshotJson("{\"productionInputs\":{\"conversations\":[{\"conversationId\":\"conversation\"}]}}");
        now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        failed = new TaskExecution(); failed.setId("failed-eid"); failed.setTaskId("task");
        failed.setAttemptNo(1); failed.setStatus("FAILED"); failed.setUpdatedTime(Date.from(now.minusSeconds(60)));
        bound = new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1", "user-alice", "alice", false, "group");
        freeze(new AutomaticRetryPolicy("iqc-automatic-retry-v1", 3, 10, 2, 40, null));
        when(tasks.selectOne(any())).thenReturn(task); when(tasks.updateById(any(InspectionTask.class))).thenReturn(1);
        when(executions.selectById("failed-eid")).thenReturn(failed);
        when(scope.owner()).thenReturn("alice"); when(scope.groupId()).thenReturn("group");
        var conversation = new Conversation(); conversation.setCreatedBy("alice"); conversation.setOwnerGroupId("group");
        when(conversations.selectById("conversation")).thenReturn(conversation);
        when(scope.canView("alice", "group")).thenReturn(true);
        when(queue.queueRetrySystem("task", "failed-eid", 1)).thenReturn(new InspectionTask());
        retries = new AutomaticRetryService(tasks, executions, conversations, null, scope, json, calls,
                provider(null), provider(queue), provider(attempts));
    }

    @AfterEach
    void clearIdentity() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        io.github.opensabre.common.core.util.UserContextHolder.getInstance().clear();
    }

    private void freeze(AutomaticRetryPolicy policy) throws Exception {
        envelope = new AutomaticRetryService.Envelope("iqc-automatic-retry-envelope-v1", "task", policy, bound, GRANTS);
        task.setRetryPolicyJson(json.writeValueAsString(envelope));
        task.setRetryPolicyHash(SelectorAssetService.hash(task.getRetryPolicyJson()));
    }
    private AutomaticRetryService.Frame frame() {
        var failure = failed.getUpdatedTime().toInstant();
        return new AutomaticRetryService.Frame("task", "failed-eid", 1, failure, task.getRetryPolicyHash(), envelope, failure.plusSeconds(10));
    }
    private ScheduledActorAccessService.Resolved actor(String mutation) {
        return new ScheduledActorAccessService.Resolved(mutation.equals("identity") ? "replacement" : "user-alice",
                mutation.equals("owner") ? "bob" : "alice", mutation.equals("expanded"),
                mutation.equals("group") ? "other-group" : "group", Set.of(),
                mutation.equals("grant") ? Set.of("iqc:task:execute") : GRANTS,
                mutation.equals("stale") ? Instant.now().minusSeconds(60) : mutation.equals("future") ? Instant.now().plusSeconds(60) : Instant.now());
    }
    private TriggerAttemptService.Handle handle(AutomaticRetryService.Frame frame) {
        return new TriggerAttemptService.Handle("a".repeat(32), new TriggerAttemptService.Context(TriggerAttemptService.Kind.TASK,
                "task", "failed-eid", frame.policyHash(), frame.retryAt(), bound), "b".repeat(64));
    }

    @Test void eligibleFailureProducesOriginalGenerationObservation() {
        assertThat(retries.peek("task", now)).isEqualTo(frame());
        verifyNoInteractions(queue, attempts);
    }
    @Test void backoffIsDerivedFromFailureTimestamp() {
        failed.setUpdatedTime(Date.from(now));
        assertThat(retries.peek("task", now)).isNull();
        assertThat(task.getRetryError()).isEqualTo("WAITING_BACKOFF");
        assertThat(task.getRetryNotBeforeUtc()).isEqualTo(LocalDateTime.ofInstant(now.plusSeconds(10), ZoneOffset.UTC));
    }
    @Test void allAttemptsIncludingManualRoundsConsumeBudget() {
        task.setAttemptCount(3);
        assertThat(retries.peek("task", now)).isNull();
        assertThat(task.getAutomaticRetryEnabled()).isFalse();
        assertThat(task.getRetryError()).isEqualTo("MAXIMUM_ATTEMPTS_REACHED");
    }
    @Test void expiredPolicyDisablesRetry() throws Exception {
        freeze(new AutomaticRetryPolicy("iqc-automatic-retry-v1", 3, 10, 2, 40, now));
        assertThat(retries.peek("task", now)).isNull();
        assertThat(task.getAutomaticRetryEnabled()).isFalse();
        assertThat(task.getRetryError()).isEqualTo("EFFECTIVE_UNTIL_REACHED");
    }
    @Test void nextRetryMustBeStrictlyBeforeExpiry() throws Exception {
        failed.setUpdatedTime(Date.from(now));
        freeze(new AutomaticRetryPolicy("iqc-automatic-retry-v1", 3, 10, 2, 40, now.plusSeconds(10)));
        assertThat(retries.peek("task", now)).isNull();
        assertThat(task.getRetryError()).isEqualTo("EFFECTIVE_UNTIL_BEFORE_NEXT_RETRY");
    }
    @Test void persistedToolExposureStopsAutomaticReplay() {
        when(calls.hasToolExposure("task")).thenReturn(true);
        assertThat(retries.peek("task", now)).isNull();
        assertThat(task.getAutomaticRetryEnabled()).isFalse();
        assertThat(task.getRetryError()).isEqualTo("TOOL_SIDE_EFFECT_REQUIRES_REVIEW");
        verifyNoInteractions(queue, attempts);
    }
    @Test void exposureAppearingAfterObservationStillStopsQueue() {
        var observation = frame();
        when(calls.hasToolExposure("task")).thenReturn(true);
        assertThat(retries.fire(observation, actor("valid"), handle(observation))).isFalse();
        assertThat(task.getAutomaticRetryEnabled()).isFalse();
        verifyNoInteractions(queue);
        verify(attempts).committed(handle(observation), 0);
    }
    @ParameterizedTest
    @ValueSource(strings = {"disabled", "pause", "cancel"})
    void controlChangesAfterObservationPreventQueue(String change) {
        var observation = frame();
        if (change.equals("disabled")) task.setAutomaticRetryEnabled(false);
        if (change.equals("pause")) task.setPauseRequested(true);
        if (change.equals("cancel")) task.setCancelRequested(true);
        assertThat(retries.fire(observation, actor("valid"), handle(observation))).isFalse();
        verifyNoInteractions(queue);
        verify(attempts).committed(handle(observation), 0);
    }
    @ParameterizedTest
    @ValueSource(strings = {"identity", "owner", "expanded", "group", "grant", "stale", "future"})
    void invalidFreshAuthorizationNeverQueues(String mutation) {
        var observation = frame();
        assertThatThrownBy(() -> retries.fire(observation, actor(mutation), handle(observation)))
                .hasMessageContaining("重试授权证据无效或过期");
        verifyNoInteractions(queue, attempts);
    }
    @Test void inputVisibilityRevokedAfterObservationNeverQueues() {
        when(scope.canView("alice", "group")).thenReturn(false);
        var observation = frame();
        assertThatThrownBy(() -> retries.fire(observation, actor("valid"), handle(observation)))
                .hasMessageContaining("原输入来源不再可见");
        verifyNoInteractions(queue, attempts);
    }
    @Test void oldObservationRecordsNoChangeWithoutQueueing() {
        var observation = frame(); task.setCurrentExecutionId("replacement-eid");
        assertThat(retries.fire(observation, actor("valid"), handle(observation))).isFalse();
        verifyNoInteractions(queue);
        verify(attempts).committed(handle(observation), 0);
    }
    @Test void validFreshScopeQueuesExactObservedGeneration() {
        var observation = frame();
        assertThat(retries.fire(observation, actor("valid"), handle(observation))).isTrue();
        verify(queue).queueRetrySystem("task", "failed-eid", 1);
        verify(attempts).committed(handle(observation), 1);
    }
}
