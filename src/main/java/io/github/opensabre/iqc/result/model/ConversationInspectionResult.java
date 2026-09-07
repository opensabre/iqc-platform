package io.github.opensabre.iqc.result.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** One final quality decision for a conversation in a task execution. */
@Data
@TableName("iqc_inspection_conversation_result")
@EqualsAndHashCode(callSuper = true)
public class ConversationInspectionResult extends BasePo {
    private String taskId;
    private String executionId;
    private String conversationId;
    private String aggregationMode;
    private String resultStatus;
    private Integer score;
    private String riskLevel;
    private Integer deduction;
    private String reason;
}
