package io.github.opensabre.iqc.quality.model;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper = true) @TableName("iqc_evaluation_sample_result")
public class EvaluationSampleResult extends BasePo {
    private String taskId;
    private String executionId;
    private String datasetId;
    private Integer datasetVersion;
    private String datasetHash;
    private String sampleId;
    private Integer sampleVersion;
    private String goldHash;
    private String conversationId;
    private String status;
    private String actualJson;
    private String actualHash;
    private Long durationMs;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.Map<String,Object> privacy;
}
