package io.github.opensabre.iqc.result.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** One top-level rule decision inside a conversation result. */
@Data
@TableName("iqc_inspection_rule_result")
@EqualsAndHashCode(callSuper = true)
public class RuleInspectionResult extends BasePo {
    private String conversationResultId;
    private String ruleId;
    private Integer ruleVersionNo;
    private String ruleType;
    private String evaluationScope;
    private String resultStatus;
    private Integer score;
    private String riskLevel;
    private Integer deduction;
    private String reason;
}
