package io.github.opensabre.iqc.rule.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Immutable review and publication history for one rule set. */
@Data
@TableName("iqc_quality_rule_set_version")
@EqualsAndHashCode(callSuper = true)
public class QualityRuleSetVersion extends BasePo {
    private String ruleSetId;
    private Integer versionNo;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.Map<String, Object> privacy;
    private String name;
    private String code;
    private String description;
    private String ruleIdsJson;
    private String aggregationMode;
    private String status;
}
