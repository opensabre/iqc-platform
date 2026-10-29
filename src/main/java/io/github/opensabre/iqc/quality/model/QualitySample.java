package io.github.opensabre.iqc.quality.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Governed quality sample derived from a reviewed result or entered manually. */
@Data @TableName("iqc_quality_sample") @EqualsAndHashCode(callSuper = true)
public class QualitySample extends BasePo {
    private String name;
    private String sampleType;
    private String sourceResultId;
    private String conversationId;
    private String messageId;
    private String contentSnapshot;
    private String expectedJson;
    private String tagsJson;
    private String status;
    private String ownerGroupId;
    private String goldDraftJson;
    private Integer goldDraftRevision = 0;
    private String goldStatus = "UNLABELLED";
    private Integer goldReviewVersion = 0;
    /** HTTP-only display metadata; never persisted or used to rebuild canonical gold. */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.Map<String, Object> privacy;
}
