package io.github.opensabre.iqc.action;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

/** One immutable action source plus independent durable execution state. */
@Data @EqualsAndHashCode(callSuper=true) @TableName("iqc_action_execution")
public class ActionExecution extends BasePo {
    private String idempotencyKey;
    private String taskId;
    private String executionId;
    private String conversationId;
    private String sourceResultId;
    private String decisionReference;
    private String actionPolicyHash;
    private String actionId;
    private String actionType;
    private String owner;
    private String ownerGroupId;
    private String inputSnapshotJson;
    private String inputSnapshotHash;
    private String policySnapshotJson;
    private String status;
    private Integer attemptCount;
    private Long version;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private LocalDateTime nextAttemptAtUtc;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private String claimToken;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private LocalDateTime claimedAtUtc;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private LocalDateTime settledAtUtc;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private String outcome;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private String evidenceReference;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private String resultReference;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private String lastErrorCode;
}
