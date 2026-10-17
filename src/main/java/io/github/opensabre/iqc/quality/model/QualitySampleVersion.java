package io.github.opensabre.iqc.quality.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Append-only review of one exact gold draft revision. */
@Data @EqualsAndHashCode(callSuper = true) @TableName("iqc_quality_sample_version")
public class QualitySampleVersion extends BasePo {
    private String sampleId;
    private Integer versionNo;
    private Integer sourceDraftRevision;
    private String decision;
    private String reviewerId;
    private String reason;
    private String snapshotJson;
    private String contentHash;
    /** HTTP-only display metadata; never persisted or used to rebuild canonical gold. */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.Map<String, Object> privacy;
}
