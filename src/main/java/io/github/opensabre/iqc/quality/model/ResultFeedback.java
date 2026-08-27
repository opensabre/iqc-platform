package io.github.opensabre.iqc.quality.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Immutable user feedback attached to one AI inspection result. */
@Data @TableName("iqc_result_feedback") @EqualsAndHashCode(callSuper = true)
public class ResultFeedback extends BasePo {
    private String resultId;
    private String feedbackType;
    private String comment;
    private String evidenceJson;
    private String status;
    private String ownerGroupId;
}
