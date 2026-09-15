package io.github.opensabre.iqc.label.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** One typed taxonomy reference inside a label collection. */
@Data @TableName("iqc_label_collection_member") @EqualsAndHashCode(callSuper = true)
public class LabelCollectionMember extends BasePo {
    private String collectionId; private String memberType; private String memberId; private Integer displayOrder;
}
