package io.github.opensabre.iqc.task.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("iqc_inspection_task")
@EqualsAndHashCode(callSuper = true)
public class InspectionTask extends BasePo {
    // Internal bulk coordination state; exposed only through a future authorized bounded projection.
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String bulkParentTaskId;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Integer bulkShardNo;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String bulkProgressJson;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String retryPolicyJson;
    private String retryPolicyHash;
    private Boolean automaticRetryEnabled;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private java.time.LocalDateTime retryNotBeforeUtc;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String retryError;
    private String eventSubscriptionId;
    private String eventSubscriptionRevisionId;
    private String triggerSource;
    private String triggerEventId;
    private String triggerPayloadHash;
    private String triggerRequestHash;
    private String triggerRequestJson;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private java.time.LocalDateTime triggerRetryNotBeforeUtc;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String triggerError;
    private String periodicScheduleId;
    private String periodicRevisionId;
    private java.time.LocalDateTime plannedAtUtc;
    private String conversationId;
    private String name;
    private String taskType;
    private String conversationIdsJson;
    private String selectionFilterJson;
    private Integer concurrencyLimit;
    private java.time.LocalDateTime scheduledTime;
    private String agentId;
    private String ruleSetId;
    private String ruleIdsJson;
    private String agentSnapshotJson;
    private String ruleSnapshotJson;
    private String labelScopeSnapshotJson;
    private Integer runCount;
    private java.math.BigDecimal confidenceThreshold;
    private Boolean autoExpandEnabled;
    private String autoExpandPrompt;
    private Long queuePriority;
    private Boolean pauseRequested;
    private Boolean cancelRequested;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String modelBudgetWaitCode;
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy=com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String modelBudgetWaitKey;
    private String status;
    private Integer totalMessages;
    private Integer processedMessages;
    private Integer failedMessages;
    private String currentExecutionId;
    private Integer attemptCount;
    private String ownerGroupId;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.Map<String,Object> taskPresentation;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.Map<String,Object> privacy;
}
