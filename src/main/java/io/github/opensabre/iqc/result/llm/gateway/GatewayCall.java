package io.github.opensabre.iqc.result.llm.gateway;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("iqc_model_gateway_call")
public class GatewayCall {
    @TableId private String callId;
    private String modelKey;
    private String logicalRecordId;
    private String ruleId;
    private String owner;
    private String taskId;
    private String executionId;
    private String nodeId;
    private Integer runIndex;
    private String ownerGroupId;
    private String conversationId;
    private String status;
    private LocalDateTime reservedAtUtc;
    private LocalDateTime settledAtUtc;
    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    private String responseModelId;
    private String usageStatus;
    private String errorType;
    private LocalDateTime capacityReleasedAtUtc;
    private String capacityReleasedBy;
    private String capacityReleasedUserId;
    private String capacityReleaseReason;
    private String capacityReleaseEvidence;
}
