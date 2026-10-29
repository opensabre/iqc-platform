package io.github.opensabre.iqc.pipelineasset.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper=true)
@TableName("iqc_pipeline_rollout_revision")
public class PipelineRolloutRevision extends BasePo {
    private String pipelineId;
    private Integer revisionNo;
    private String snapshotJson;
    private String contentHash;
}
