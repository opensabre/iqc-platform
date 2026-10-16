package io.github.opensabre.iqc.result.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
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
    /** Display-only field populated by the result query; it is not stored on the result table. */
    @TableField(exist = false)
    private String sourceFileName;
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
    /** Transient detector diagnostics are persisted only inside immutable Pipeline node traces. */
    @TableField(exist = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String detectorMetricsJson;
    /** Response-only disclosure status; never persisted with the canonical result. */
    @TableField(exist = false)
    private java.util.Map<String, Object> privacy;
}
