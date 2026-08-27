package io.github.opensabre.iqc.result.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("iqc_inspection_result")
@EqualsAndHashCode(callSuper = true)
public class InspectionResult extends BasePo {
    private String taskId;
    private String executionId;
    private String conversationId;
    private String messageId;
    private String ruleId;
    private String speakerRole;
    private String resultStatus;
    private Integer score;
    private String riskLevel;
    private Integer deduction;
    private String reason;
    private String evidence;
    private String findingJson;
    private String evidenceJson;
    private String suggestionJson;
    private String ruleBreakdownJson;
}
