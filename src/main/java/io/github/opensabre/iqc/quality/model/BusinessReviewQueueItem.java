package io.github.opensabre.iqc.quality.model;

/** Lightweight worklist projection; full evidence and scoring snapshots are loaded only in result detail. */
public record BusinessReviewQueueItem(String reviewId, String resultId, String taskId, String taskName,
                                      String conversationId, Integer reviewRevision, String status,
                                      String requestComment, String createdBy, java.time.LocalDateTime createdTime,
                                      String taskStatus, boolean currentResult,String stage,
                                      @com.fasterxml.jackson.annotation.JsonIgnore String workflowMetadata) {
    @org.apache.ibatis.annotations.AutomapConstructor
    public BusinessReviewQueueItem {
        stage = workflowMetadata == null
                ? (stage == null ? "INITIAL" : java.util.Set.of("INITIAL", "SECOND_REVIEW", "ARBITRATION", "INVALID").contains(stage) ? stage : "INVALID")
                : stageFromMetadata(status, workflowMetadata);
        // The raw snapshot is only a mapper input: do not retain it in the DTO, JSON or record toString.
        workflowMetadata = null;
    }
    /** Existing projected-stage callers and JSON round trips never reinterpret a stage as JSON. */
    public BusinessReviewQueueItem(String reviewId,String resultId,String taskId,String taskName,String conversationId,Integer reviewRevision,
            String status,String requestComment,String createdBy,java.time.LocalDateTime createdTime,String taskStatus,boolean currentResult,String stage){
        this(reviewId,resultId,taskId,taskName,conversationId,reviewRevision,status,requestComment,createdBy,createdTime,taskStatus,currentResult,stage,null);
    }
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Decode the already scoped page in Java, without database-specific JSON functions or exposing raw snapshots. */
    private static String stageFromMetadata(String status, String metadata) {
        if (metadata == null) return "INITIAL";
        if (metadata.length() > 1_000_000) return "INVALID";
        try {
            var node = JSON.readTree(metadata);
            if (node == null || node.isNull()) return "INITIAL";
            if (!node.isObject()) return "INVALID";
            if ("COMPLETED".equals(status)) {
                node = node.get("workflow");
                if (node == null || node.isNull()) return "INITIAL";
            }
            if (!node.isObject() || !"iqc-review-workflow-v1".equals(node.path("schemaVersion").asText())) return "INVALID";
            String stage = node.path("stage").asText();
            return "SECOND_REVIEW".equals(stage) || "ARBITRATION".equals(stage) ? stage : "INVALID";
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return "INVALID";
        }
    }
    public BusinessReviewQueueItem(String reviewId,String resultId,String taskId,String taskName,String conversationId,Integer reviewRevision,
            String status,String requestComment,String createdBy,java.time.LocalDateTime createdTime,String taskStatus,boolean currentResult){
        this(reviewId,resultId,taskId,taskName,conversationId,reviewRevision,status,requestComment,createdBy,createdTime,taskStatus,currentResult,null);
    }
}
