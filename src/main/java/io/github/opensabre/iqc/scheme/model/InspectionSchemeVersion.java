package io.github.opensabre.iqc.scheme.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Released business standard and full dependency snapshot; archive status is mutable lifecycle metadata only. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iqc_inspection_scheme_version")
public class InspectionSchemeVersion extends BasePo {
    private String schemeId;
    private Integer versionNo;
    private Integer sourceDraftRevision;
    private String sourceTrialTaskId;
    private String snapshotJson;
    private String contentHash;
    private Boolean archived;
}
