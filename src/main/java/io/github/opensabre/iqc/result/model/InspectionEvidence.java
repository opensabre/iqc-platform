package io.github.opensabre.iqc.result.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Message-level evidence supporting a top-level rule decision. */
@Data
@TableName("iqc_inspection_evidence")
@EqualsAndHashCode(callSuper = true)
public class InspectionEvidence extends BasePo {
    private String ruleResultId;
    private String messageId;
    private Integer sequenceNo;
    private String internalDefinition;
    private String evidenceType;
    private String matchedText;
    private Integer startOffset;
    private Integer endOffset;
}
