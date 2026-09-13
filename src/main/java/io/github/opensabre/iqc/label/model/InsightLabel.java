package io.github.opensabre.iqc.label.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** Published business meaning whose detection is delegated to existing IQC rules. */
@Data @TableName("iqc_label") @EqualsAndHashCode(callSuper = true)
public class InsightLabel extends BasePo {
    private String groupId; private String name; private String code; private String description;
    private String targetRole; private BigDecimal weight; private String status; private Integer versionNo;
    private String sourceType; private String ownerGroupId;
}
