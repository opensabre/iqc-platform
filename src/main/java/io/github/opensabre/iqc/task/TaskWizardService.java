package io.github.opensabre.iqc.task;

import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.processing.TaskResultProcessingConfiguration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates prototype tasks through the existing task store after freezing all referenced configurations. */
@Service
public class TaskWizardService {
    private final TaskEnginePlanService engines;
    private final TaskResultProcessingService processing;
    private final InspectionTaskService tasks;
    private final UnifiedLabelPlanService unified;
    @org.springframework.beans.factory.annotation.Autowired
    public TaskWizardService(TaskEnginePlanService engines, TaskResultProcessingService processing, InspectionTaskService tasks,
                             UnifiedLabelPlanService unified) {
        this.engines = engines; this.processing = processing; this.tasks = tasks; this.unified = unified;
    }
    /** Creates a unified tag MVP using existing selectors, task storage, workers and checkpoints. */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public InspectionTask createUnified(String name, TaskDataSelection selection, UnifiedLabelPlanService.Configuration configuration,
                                        Integer concurrency) {
        if (name == null || name.isBlank() || name.trim().length() > 80 || selection == null || selection.sampleSeed() != null)
            throw IqcException.invalidArgument("请填写任务名称及有效的数据选择");
        return tasks.createWithProcessing(name.trim(), selection, concurrency, unified.freeze(configuration), configuration);
    }
    /** All validation and persistence share one transaction; dynamic selection never queries candidate rows here. */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public InspectionTask create(String name, TaskDataSelection selection, TaskProcessingConfiguration configuration,
                                 TaskResultProcessingConfiguration results, Integer concurrency) {
        if (name == null || name.isBlank() || name.trim().length() > 80)
            throw IqcException.invalidArgument("任务名称须为 1 到 80 个字符");
        if (selection == null || selection.sampleSeed() != null || configuration == null || results == null)
            throw IqcException.invalidArgument("数据筛选、任务类型和结果处理均须配置，不能提交预览种子");
        var plan = engines.freeze(configuration);
        var frozenResults = processing.freeze(results, plan);
        return tasks.createWithProcessing(name.trim(), selection, concurrency,
                new TaskWizardSnapshot(TaskWizardSnapshot.SCHEMA, plan, frozenResults));
    }
}
