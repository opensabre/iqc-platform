package io.github.opensabre.iqc.rule.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("iqc_quality_rule_version")
@EqualsAndHashCode(callSuper = true)
public class QualityRuleVersion extends BasePo {
    private String ruleId;
    private Integer versionNo;
    private String name;
    private String code;
    private String category;
    private String ruleType;
    private String targetRole;
    private String expression;
    private String description;
    private Integer deduction;
    private String riskLevel;
    private Boolean veto;
    private String status;
}
