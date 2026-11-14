package io.github.opensabre.iqc.task.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("iqc_inspection_task")
@EqualsAndHashCode(callSuper = true)
public class InspectionTask extends BasePo {
    private String conversationId;
    private String name;
    private String taskType;
    private String conversationIdsJson;
    private String selectionFilterJson;
    private Integer concurrencyLimit;
    private java.time.LocalDateTime scheduledTime;
    private String agentId;
    private String ruleSetId;
    private String ruleIdsJson;
    private String agentSnapshotJson;
    private String ruleSnapshotJson;
    /** Unified task references, resolved again when the first execution is queued. */
    private String unifiedConfigurationJson;
    /** Server-owned trigger plus captured IQC access bound; selection remains independent. */
    private String triggerConfigurationJson;
    private Boolean enabled;
    private String labelScopeSnapshotJson;
    private Integer runCount;
    private java.math.BigDecimal confidenceThreshold;
    private Boolean autoExpandEnabled;
    private String autoExpandPrompt;
    private Long queuePriority;
    private Boolean pauseRequested;
    private Boolean cancelRequested;
    private String status;
    private Integer totalMessages;
    private Integer processedMessages;
    private Integer failedMessages;
    private String currentExecutionId;
    private Integer attemptCount;
    private String ownerGroupId;
}
