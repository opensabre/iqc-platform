package io.github.opensabre.iqc.quality.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Human review that preserves the original AI decision and stores the effective conclusion separately. */
@Data @TableName("iqc_result_review") @EqualsAndHashCode(callSuper = true)
public class ResultReview extends BasePo {
    private String resultId;
    /** MESSAGE retains result_id; BUSINESS and LABEL use their dedicated source-result columns. */
    private String targetType = "MESSAGE";
    private String businessResultId;
    private String labelResultId;
    private Integer reviewRevision;
    private String sourceHash;
    private String requestFingerprint;
    private String requestComment;
    private String decisionFingerprint;
    /** Separate original/reviewed projections, including decimal scores and effective item decisions. */
    private String reviewedResultJson;
    private String status;
    private String originalStatus;
    private Integer originalScore;
    private String originalRiskLevel;
    private String finalStatus;
    private Integer finalScore;
    private String finalRiskLevel;
    private String reviewComment;
    private String reviewerId;
    private java.time.LocalDateTime reviewedTime;
    private String ownerGroupId;
}
