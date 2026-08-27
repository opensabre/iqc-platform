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
    private String name;
    private String code;
    private String description;
    private String ruleIdsJson;
    private String aggregationMode;
    private String status;
}
