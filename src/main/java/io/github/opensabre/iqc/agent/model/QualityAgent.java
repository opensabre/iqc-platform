package io.github.opensabre.iqc.agent.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("iqc_quality_agent")
@EqualsAndHashCode(callSuper = true)
public class QualityAgent extends BasePo {
    private String name;
    private String code;
    private String description;
    private String status;
    private String configJson;
    private Integer versionNo;
}
