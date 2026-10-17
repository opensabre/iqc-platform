package io.github.opensabre.iqc.quality.model;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = true) @TableName("iqc_evaluation_dataset_version")
public class EvaluationDatasetVersion extends BasePo {
    private String datasetId;
    private Integer versionNo;
    private Integer sourceDraftRevision;
    private Integer sampleCount;
    private String snapshotJson;
    private String contentHash;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.Map<String,Object> privacy;
}
