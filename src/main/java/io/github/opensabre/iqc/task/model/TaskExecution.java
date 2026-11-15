package io.github.opensabre.iqc.task.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("iqc_task_execution")
@EqualsAndHashCode(callSuper = true)
public class TaskExecution extends BasePo {
    private String taskId;
    /** First execution ID of one logical run; retries and resumes share this value. */
    private String runId;
    private Integer attemptNo;
    private String status;
    private Integer processedMessages;
    private Integer failedMessages;
    private String errorMessage;
    /** Immutable wizard configuration for this attempt; null for historical executions. */
    private String configurationSnapshotJson;
    /** Resolved selector, runtime seed and conversation IDs used by this attempt. */
    private String selectionSnapshotJson;
}
