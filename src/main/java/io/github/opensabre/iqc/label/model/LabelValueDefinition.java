package io.github.opensabre.iqc.label.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Type contract for one structured value emitted by a label. */
@Data @TableName("iqc_label_value_definition") @EqualsAndHashCode(callSuper = true)
public class LabelValueDefinition extends BasePo {
    private String labelId; private String valueCode; private String valueType;
    private String description; private Integer displayOrder; private String configJson;
}
