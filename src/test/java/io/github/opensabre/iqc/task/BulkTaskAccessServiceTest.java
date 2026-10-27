package io.github.opensabre.iqc.task;

import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Set;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Confirms authorization is resolved before reading stored bulk owner evidence or queueing. */
class BulkTaskAccessServiceTest {
    private final ScheduledActorAccessService actors = mock(ScheduledActorAccessService.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final InspectionTaskService tasks = mock(InspectionTaskService.class);
    private final BulkTaskMaterializationService materialization = mock(BulkTaskMaterializationService.class);
    private final BulkTaskLifecycleService lifecycle = mock(BulkTaskLifecycleService.class);
    private final BulkTaskAccessService service = new BulkTaskAccessService(actors, scope, tasks, materialization, lifecycle);
    private final ScheduledActorAccessService.CreationBound current =
            new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1", "user", "owner", true, "g");
    private final ScheduledActorAccessService.Resolved resolved =
            new ScheduledActorAccessService.Resolved("user", "owner", true, "g", Set.of("ADMIN"),
                    BulkTaskMaterializationService.GRANTS, Instant.now());

    private void bulk(String owner) {
        var task = new InspectionTask(); task.setTaskType("PIPELINE_BULK"); task.setCreatedBy(owner);
        when(tasks.getDispatchIdentity("parent")).thenReturn(task);
        when(actors.capture()).thenReturn(current);
    }

    @Test
    void ordinaryTaskDoesNotBorrowBulkAuthorizationPath() {
        var task = new InspectionTask(); task.setTaskType("PIPELINE");
        when(tasks.getDispatchIdentity("ordinary")).thenReturn(task);
        assertThat(service.queueIfBulk("ordinary")).isEmpty();
        verifyNoInteractions(actors, scope, materialization, lifecycle);
    }

    @Test
    void rejectedCurrentGrantsCannotReadOriginalOwnerOrQueue() {
        bulk("owner");
        var failure = new IllegalStateException("current grants revoked");
        when(actors.resolveCurrent(current, BulkTaskMaterializationService.GRANTS)).thenThrow(failure);
        assertThatThrownBy(() -> service.queueIfBulk("parent")).isSameAs(failure);
        verifyNoInteractions(lifecycle, scope, materialization);
    }

    @Test
    void differentCreatorCannotReadOriginalOwnerEvenWithGlobalCurrentScope() {
        bulk("foreign");
        when(actors.resolveCurrent(current, BulkTaskMaterializationService.GRANTS)).thenReturn(resolved);
        assertThatThrownBy(() -> service.queueIfBulk("parent")).hasMessageContaining("原批次负责人");
        verifyNoInteractions(lifecycle, scope, materialization);
    }

    @Test
    void authorizedOwnerUsesSavedBoundOnlyAfterCurrentGrantResolution() {
        bulk("owner");
        var original = new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1", "user", "owner", false, "g");
        var queued = new InspectionTask(); queued.setId("parent"); queued.setStatus("QUEUED");
        when(actors.resolveCurrent(current, BulkTaskMaterializationService.GRANTS)).thenReturn(resolved);
        when(lifecycle.ownerForCurrent("parent")).thenReturn(original);
        when(scope.asCurrentActorBound(eq(resolved), eq(original), any())).thenAnswer(call -> ((Supplier<?>)call.getArgument(2)).get());
        when(lifecycle.queue("parent")).thenReturn(queued);
        assertThat(service.queueIfBulk("parent")).containsSame(queued);
        var order = inOrder(tasks, actors, lifecycle, scope);
        order.verify(tasks).getDispatchIdentity("parent");
        order.verify(actors).capture();
        order.verify(actors).resolveCurrent(current, BulkTaskMaterializationService.GRANTS);
        order.verify(lifecycle).ownerForCurrent("parent");
        order.verify(scope).asCurrentActorBound(eq(resolved), eq(original), any());
        order.verify(lifecycle).queue("parent");
        verifyNoInteractions(materialization);
    }
}
