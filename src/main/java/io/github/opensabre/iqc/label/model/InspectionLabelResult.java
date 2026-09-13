package io.github.opensabre.iqc.label.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** Insight projection derived from a canonical rule result without re-running detection. */
@Data @TableName("iqc_inspection_label_result") @EqualsAndHashCode(callSuper = true)
public class InspectionLabelResult extends BasePo {
    private String conversationResultId; private String labelId; private Integer labelVersionNo;
    private String valueCode; private String valueJson; private BigDecimal confidence;
    private String sourceRuleResultId; private String generationSource;
}
