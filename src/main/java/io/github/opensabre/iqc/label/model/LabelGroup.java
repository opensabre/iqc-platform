package io.github.opensabre.iqc.label.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Second-level grouping under one label category. */
@Data @TableName("iqc_label_group") @EqualsAndHashCode(callSuper = true)
public class LabelGroup extends BasePo {
    private String categoryId; private String name; private String code; private String description;
    private Integer maxChildCount; private Boolean allowAutoExpand; private String status;
    private Integer versionNo; private String ownerGroupId;
}
