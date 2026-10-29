package io.github.opensabre.iqc.result.llm.gateway;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
@Data @TableName("iqc_model_gateway_capacity")
public class GatewayCapacity {
    @TableId private String modelKey;
    private Long occupied;
    private Integer concurrencyLimit;
    private Boolean policyManaged;
    private Long policyVersion;
    @TableField(updateStrategy=FieldStrategy.ALWAYS)
    private Long tokenBudgetLimit;
    private Long tokenBudgetCommitted;
    @TableField(updateStrategy=FieldStrategy.ALWAYS)
    private java.math.BigDecimal costBudgetLimit;
    private java.math.BigDecimal costBudgetCommitted;
    private String budgetCurrency;
    private Integer budgetContextWindow;
    private String budgetDeploymentRef;
    private String budgetEvidenceRef;
    private String budgetOutputParameter;
    @TableField(updateStrategy=FieldStrategy.ALWAYS)
    private String budgetPricingHash;
    private Boolean budgetBreached;
}
