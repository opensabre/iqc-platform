package io.github.opensabre.iqc.scheme.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Editable draft and published pointer; editing a draft never disables an existing release. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iqc_inspection_scheme")
public class InspectionScheme extends BasePo {
    private String name;
    private String code;
    private String description;
    private String businessScene;
    private String draftConfigJson;
    private Integer draftRevision;
    private Integer activePublishedVersion;
    private String status;
    private String ownerGroupId;
    /** Immutable immediate parent release for a scheme derived from published standards. */
    private String sourceSchemeId;
    private String sourceSchemeName;
    private String sourceSchemeCode;
    private Integer sourceVersionNo;
    private String sourceContentHash;
}
