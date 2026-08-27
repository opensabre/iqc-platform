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
    private Integer processedMessages;
    private Integer failedMessages;
    private String errorMessage;
}
