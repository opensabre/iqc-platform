package io.github.opensabre.iqc.task.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("iqc_task_item")
@EqualsAndHashCode(callSuper = true)
public class TaskItem extends BasePo {
    private String taskId;
    private String executionId;
    private String conversationId;
    private String messageId;
    private Integer sequenceNo;
    private String status;
    private String resultId;
    private Integer attemptCount;
    private String errorMessage;
}
