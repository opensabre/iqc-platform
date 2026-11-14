package io.github.opensabre.iqc.task;

/** Server-owned task snapshot joining independent engine and result-processing plans. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record TaskWizardSnapshot(String schemaVersion, TaskEnginePlanService.FrozenPlan enginePlan,
                                 TaskResultProcessingService.FrozenProcessing resultProcessing,
                                 com.fasterxml.jackson.databind.JsonNode unifiedLabels) {
    public TaskWizardSnapshot(String schemaVersion, TaskEnginePlanService.FrozenPlan enginePlan,
                              TaskResultProcessingService.FrozenProcessing resultProcessing) {
        this(schemaVersion, enginePlan, resultProcessing, null);
    }
    @Override public com.fasterxml.jackson.databind.JsonNode unifiedLabels() {
        return unifiedLabels == null ? null : unifiedLabels.deepCopy();
    }
    public static final String SCHEMA = "iqc-task-wizard-v1";
    public TaskWizardSnapshot {
        if (!SCHEMA.equals(schemaVersion) || enginePlan == null || (resultProcessing == null) == (unifiedLabels == null))
            throw new IllegalArgumentException("任务创建快照无效");
        if (unifiedLabels != null) unifiedLabels = unifiedLabels.deepCopy();
    }
}
