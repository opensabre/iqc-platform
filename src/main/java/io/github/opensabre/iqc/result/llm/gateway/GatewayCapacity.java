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
}
