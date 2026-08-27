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
    private String status;
    private Integer totalMessages;
    private Integer processedMessages;
    private Integer failedMessages;
    private String currentExecutionId;
    private Integer attemptCount;
    private String ownerGroupId;
}
