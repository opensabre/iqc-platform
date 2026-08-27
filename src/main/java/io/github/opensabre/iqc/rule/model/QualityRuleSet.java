package io.github.opensabre.iqc.rule.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Published, reusable ordered collection of quality rules. */
@Data
@TableName("iqc_quality_rule_set")
@EqualsAndHashCode(callSuper = true)
public class QualityRuleSet extends BasePo {
    private String name;
    private String code;
    private String description;
    private String ruleIdsJson;
    private String aggregationMode;
    private Integer versionNo;
    private String status;
}
