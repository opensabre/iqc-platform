package io.github.opensabre.iqc.task;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.common.core.util.UserContextHolder;
import io.github.opensabre.iqc.result.InspectionExecutionService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import static io.github.opensabre.iqc.task.UnifiedTaskScheduleService.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Trigger lifecycle and authorization tests over the existing queue contract. */
class UnifiedTaskScheduleServiceTest {
    private final TaskWizardService wizard = mock(TaskWizardService.class);
    private final InspectionTaskService tasks = mock(InspectionTaskService.class);
    private final InspectionTaskMapper mapper = mock(InspectionTaskMapper.class);
    private final InspectionExecutionService executions = mock(InspectionExecutionService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final IqcDataScope scope = new IqcDataScope(null);
    private final UnifiedTaskScheduleService service = new UnifiedTaskScheduleService(wizard, tasks, mapper, executions, scope, json);
    private final UserContextHolder context = UserContextHolder.getInstance();
    @BeforeEach void setup() {
        context.setContext(Map.of(context.KEY_USERNAME, "alice"));
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "schedule"), InspectionTask.class);
    }
    @AfterEach void cleanup() { context.clear(); }
    private InspectionTask create(Trigger trigger) {
        var task = new InspectionTask(); task.setId("task"); task.setCreatedBy("alice"); task.setStatus("CREATED");
        when(wizard.createUnified(any(), any(), any(), any())).thenReturn(task);
        when(mapper.selectOne(any())).thenReturn(task); when(tasks.get("task")).thenReturn(task);
        return service.create("name", null, null, 1, trigger);
    }
    @Test void validatesIndependentTriggerFieldsAndCron() {
        assertThatThrownBy(() -> new Trigger(TriggerType.PERIODIC, null, "invalid", true)).hasMessageContaining("Cron");
        assertThatThrownBy(() -> new Trigger(TriggerType.SCHEDULED, null, null, true)).hasMessageContaining("执行时间");
        assertThatThrownBy(() -> new Trigger(TriggerType.IMMEDIATE, LocalDateTime.now(), null, true)).hasMessageContaining("立即");
        assertThatThrownBy(() -> service.create("name", null, null, 1,
                new Trigger(TriggerType.SCHEDULED, LocalDateTime.now().minusDays(1), null, true))).hasMessageContaining("晚于");
        verifyNoInteractions(wizard, executions);
    }
    @Test void immediateQueuesBeforeReturningAndStartsOnlyWhenCallerRequestsAfterCommit() {
        var task = new InspectionTask(); task.setId("task"); task.setStatus("QUEUED"); task.setCurrentExecutionId("execution");
        when(executions.queue("task")).thenReturn(task);
        var queued = create(new Trigger(TriggerType.IMMEDIATE, null, null, true));
        assertThat(queued.getScheduledTime()).isNull(); verify(executions).queue("task");
        verify(executions, never()).executeAsync(any(), any());
        service.start(queued); verify(executions).executeAsync("task", "execution");
    }
    @Test void periodicRunsResetAttemptPointerAndFreezeWithinCapturedScopeWithoutOverlapping() {
        var task = create(new Trigger(TriggerType.PERIODIC, null, "0 * * * * *", true));
        var due = task.getScheduledTime(); task.setStatus("SUCCEEDED"); task.setCurrentExecutionId("previous-run");
        context.clear(); assertThat(scope.owner()).isEqualTo("system");
        when(executions.queue("task")).thenAnswer(call -> {
            assertThat(task.getCurrentExecutionId()).isNull(); assertThat(scope.owner()).isEqualTo("alice");
            assertThat(scope.canViewAll()).isFalse();
            task.setCurrentExecutionId("next-run"); task.setStatus("QUEUED"); return task;
        });
        assertThat(service.queueDue("task", due.plusSeconds(120))).isSameAs(task);
        assertThat(task.getScheduledTime()).isAfter(due.plusSeconds(120));
        assertThat(scope.owner()).isEqualTo("system");
        assertThat(service.queueDue("task", due.plusDays(1))).isNull(); verify(executions).queue("task");
    }
    @Test void disabledSchedulesDoNotQueueAndEnableRefreshesAuthorization() throws Exception {
        var task = create(new Trigger(TriggerType.PERIODIC, null, "0 * * * * *", false));
        assertThat(service.queueDue("task", task.getScheduledTime())).isNull(); verifyNoInteractions(executions);
        context.setContext(Map.of(context.KEY_USERNAME, "bob"));
        service.setEnabled("task", true);
        assertThat(task.getEnabled()).isTrue();
        assertThat(json.readTree(task.getTriggerConfigurationJson()).path("scope").path("owner").asText()).isEqualTo("bob");
        task.setStatus("RUNNING"); service.setEnabled("task", false);
        assertThat(task.getStatus()).isEqualTo("RUNNING"); assertThat(task.getEnabled()).isFalse();
    }
    @Test void oneShotRunsOnceAndCannotBeReenabledAfterTriggering() {
        var task = create(new Trigger(TriggerType.SCHEDULED, LocalDateTime.now().plusDays(1), null, true));
        when(executions.queue("task")).thenAnswer(call -> {task.setCurrentExecutionId("once"); task.setStatus("QUEUED"); return task;});
        service.queueDue("task", task.getScheduledTime());
        assertThat(task.getScheduledTime()).isNull(); task.setStatus("SUCCEEDED");
        assertThat(service.queueDue("task", LocalDateTime.now().plusDays(10))).isNull();
        assertThatThrownBy(() -> service.setEnabled("task", true)).hasMessageContaining("一次性");
        verify(executions).queue("task");
    }
    @Test void failedQueueRestoresScopeAndFailureHandlerDoesNotTouchAnAlreadyClaimedRun() {
        var task = create(new Trigger(TriggerType.PERIODIC, null, "0 * * * * *", true));
        var due = task.getScheduledTime(); context.clear();
        when(executions.queue("task")).thenThrow(new IllegalStateException("freeze rejected"));
        assertThatThrownBy(() -> service.queueDue("task", due)).hasMessageContaining("freeze rejected");
        assertThat(scope.owner()).isEqualTo("system");
        // The database transaction rolls back the definition change on failure.
        task.setStatus("SCHEDULED"); task.setScheduledTime(due);
        service.recordDispatchFailure("task", due, due);
        assertThat(task.getStatus()).isEqualTo("FAILED"); assertThat(task.getScheduledTime()).isAfter(due);
        task.setStatus("RUNNING"); service.recordDispatchFailure("task", task.getScheduledTime(), due.plusDays(1));
        assertThat(task.getStatus()).isEqualTo("RUNNING");
    }
}
