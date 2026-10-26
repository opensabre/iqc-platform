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
    private Integer attemptNo;
    private String status;
    /** First terminal transition only; historical executions without measured completion remain null. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.time.LocalDateTime completedAtUtc;
    private Integer processedMessages;
    private Integer failedMessages;
    private String errorMessage;
}
