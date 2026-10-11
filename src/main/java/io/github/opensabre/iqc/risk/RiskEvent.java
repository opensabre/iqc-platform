package io.github.opensabre.iqc.risk;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Independent business handling state; evidence remains in the original production result. */
@Data @TableName("iqc_risk_event") @EqualsAndHashCode(callSuper=true)
public class RiskEvent extends BasePo {
    private String sourceActionExecutionId;
    private String sourceResultId;
    private String sourceInputHash;
    private String category;
    private String title;
    private String description;
    private String riskLevel;
    private String status;
    private Long version;
}
