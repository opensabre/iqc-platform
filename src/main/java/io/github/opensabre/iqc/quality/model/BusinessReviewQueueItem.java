package io.github.opensabre.iqc.quality.model;

/** Lightweight worklist projection; full evidence and scoring snapshots are loaded only in result detail. */
public record BusinessReviewQueueItem(String reviewId, String resultId, String taskId, String taskName,
                                      String conversationId, Integer reviewRevision, String status,
                                      String requestComment, String createdBy, java.time.LocalDateTime createdTime,
                                      String taskStatus, boolean currentResult) { }
