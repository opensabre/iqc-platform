package io.github.opensabre.iqc.finding.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iqc_finding_catalog")
public class FindingCatalog extends BasePo {
    private String code;
    private String name;
    private String category;
    private String draftConfigJson;
    private Integer draftRevision;
    private Integer activePublishedVersion;
    private String status;
    private String ownerGroupId;
}
