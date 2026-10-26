package io.github.opensabre.iqc.task;

import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.selector.SelectorDefinition;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Optional;

/** Current-user entry: organization authorization is resolved before entering any bulk transaction. */
@Service @RequiredArgsConstructor
public class BulkTaskAccessService {
    @org.springframework.beans.factory.annotation.Value("${iqc.task.worker-enabled:true}")
    private boolean workerEnabled=true;
    private final ScheduledActorAccessService actors;
    private final IqcDataScope scope;
    private final InspectionTaskService tasks;
    private final BulkTaskMaterializationService materialization;
    private final BulkTaskLifecycleService lifecycle;

    public InspectionTask create(String pipelineId,int versionNo,SelectorDefinition selector,String name,
            int scanSize,int concurrency,String requestId) {
        if(!workerEnabled)throw io.github.opensabre.iqc.governance.IqcException.invalidState("分片批次需要启用持久任务worker");
        var bound=actors.capture();
        var actor=actors.resolveCurrent(bound,BulkTaskMaterializationService.GRANTS);
        return scope.asCurrentActor(actor,()->materialization.create(pipelineId,versionNo,selector,bound,name,scanSize,concurrency,requestId));
    }
    public Optional<InspectionTask> queueIfBulk(String id) {
        var identity=tasks.getDispatchIdentity(id);
        if(!BulkTaskMaterializationService.TYPE.equals(identity.getTaskType()))return Optional.empty();
        if(!workerEnabled)throw io.github.opensabre.iqc.governance.IqcException.invalidState("分片批次需要启用持久任务worker");
        var bound=actors.capture();
        var actor=actors.resolveCurrent(bound,BulkTaskMaterializationService.GRANTS);
        return Optional.of(scope.asCurrentActor(actor,()->lifecycle.queue(id)));
    }
}
