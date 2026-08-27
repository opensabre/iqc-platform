package io.github.opensabre.iqc.modelprofile.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Safe model connection profile selected by Agent versions. */
@Data
@TableName("iqc_model_profile")
@EqualsAndHashCode(callSuper = true)
public class IqcModelProfile extends BasePo {
    private String name; private String code; private String description;
    private String provider; private String modelName; private String endpoint; private String secretRef;
    private Double temperature; private Integer timeoutSeconds; private Integer maxRetries;
    private String status; private Integer versionNo;
}
