package io.github.opensabre.iqc.label.model;

import lombok.Data;

import java.time.LocalDateTime;

/** Scoped worklist row; frozen display names are added after the paginated database query. */
@Data
public class LabelReviewQueueItem {
    private String reviewId;
    private String labelResultId;
    private String taskId;
    private String taskName;
    private String conversationId;
    private String labelId;
    private Integer labelVersionNo;
    private String labelName;
    private String valueCode;
    private String valueDescription;
    private Integer reviewRevision;
    private String status;
    private String requestComment;
    private String createdBy;
    private LocalDateTime createdTime;
    private boolean currentResult;
}
