package io.github.opensabre.iqc.decision.model;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;
/** Append-only machine policy revision; never a human review or detector execution. */
@Data
@EqualsAndHashCode(callSuper=true)
@TableName("iqc_decision_revision")
public class DecisionRevision extends BasePo {
    private String sourceResultId;
    private String taskId;
    private String sourceHash;
    private String requestFingerprint;
    private String targetPolicyJson;
    private String projectionJson;
    private String reason;
    private String ownerGroupId;
}
