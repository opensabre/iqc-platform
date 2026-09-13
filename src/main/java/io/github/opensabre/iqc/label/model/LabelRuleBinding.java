package io.github.opensabre.iqc.label.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Version-pinned mapping from a business label to an executable rule. */
@Data @TableName("iqc_label_rule_binding") @EqualsAndHashCode(callSuper = true)
public class LabelRuleBinding extends BasePo {
    private String labelId; private String ruleId; private Integer ruleVersionNo;
    private String bindingRole; private Integer displayOrder;
}
