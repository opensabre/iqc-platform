package io.github.opensabre.iqc.label.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** AI-proposed label that remains isolated from the published taxonomy until reviewed. */
@Data
@TableName("iqc_label_candidate")
@EqualsAndHashCode(callSuper = true)
public class LabelCandidate extends BasePo {
    private String taskId;
    private String conversationId;
    private String categoryId;
    private String groupId;
    private String suggestedName;
    private String suggestedCode;
    private String description;
    private String valueJson;
    private String evidenceJson;
    private BigDecimal confidence;
    private String modelSnapshotJson;
    private String status;
    private String mergedLabelId;
    private String createdLabelId;
    private String reviewComment;
    private String ownerGroupId;
}
