package io.github.opensabre.iqc.label.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Reusable business selection of categories, groups or labels. */
@Data @TableName("iqc_label_collection") @EqualsAndHashCode(callSuper = true)
public class LabelCollection extends BasePo {
    private String name; private String code; private String description; private String status;
    private Integer versionNo; private String ownerGroupId;
}
