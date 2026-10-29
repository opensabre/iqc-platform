package io.github.opensabre.iqc.schedule.model;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper=true) @TableName("iqc_trigger_attempt_event")
public class TriggerAttemptEvent extends BasePo {
    private String attemptId;
    private String phase;
    private String triggerKind;
    private String parentId;
    private String revisionId;
    private String evidenceHash;
    private String ownerUserId;
    private String contextHash;
    private String contextJson;
    private java.time.LocalDateTime plannedAtUtc;
    private java.time.LocalDateTime recordedAtUtc;
    private String outcome;
    private Integer processedUnits;
    private String errorType;
    private String failureStage;
    private String errorCode;
}
