package io.github.opensabre.iqc.quality.model;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = true) @TableName("iqc_evaluation_dataset")
public class EvaluationDataset extends BasePo {
    private String name;
    private String datasetType;
    private String draftConfigJson;
    private Integer draftRevision;
    private Integer activeVersion;
    private String status;
    private String ownerGroupId;
}
