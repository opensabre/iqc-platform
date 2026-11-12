package io.github.opensabre.iqc.task;

/** Server-owned task snapshot joining independent engine and result-processing plans. */
public record TaskWizardSnapshot(String schemaVersion, TaskEnginePlanService.FrozenPlan enginePlan,
                                 TaskResultProcessingService.FrozenProcessing resultProcessing) {
    public static final String SCHEMA = "iqc-task-wizard-v1";
    public TaskWizardSnapshot {
        if (!SCHEMA.equals(schemaVersion) || enginePlan == null || resultProcessing == null)
            throw new IllegalArgumentException("任务创建快照无效");
    }
}
