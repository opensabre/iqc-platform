package io.github.opensabre.iqc.agent.model;

import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Read-only IAC contract projection; not an IQC persistence entity. */
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityAgent extends BasePo {
    private String name;
    private String code;
    private String description;
    private String status;
    private String configJson;
    private Integer versionNo;
}
