package io.github.opensabre.iqc.pipelineasset.model;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data
@EqualsAndHashCode(callSuper=true)
@TableName("iqc_pipeline_asset")
public class PipelineAsset extends BasePo {
    private String code;
    private String name;
    private String draftConfigJson;
    private Integer draftRevision;
    private Integer activePublishedVersion;
    private String status;
    private String ownerGroupId;
}
