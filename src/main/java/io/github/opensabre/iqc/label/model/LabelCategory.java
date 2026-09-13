package io.github.opensabre.iqc.label.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Top-level business dimension in the insight label taxonomy. */
@Data @TableName("iqc_label_category") @EqualsAndHashCode(callSuper = true)
public class LabelCategory extends BasePo {
    private String name; private String code; private String prompt; private Integer maxChildCount;
    private Boolean allowAutoExpand; private String status; private Integer versionNo; private String ownerGroupId;
}
