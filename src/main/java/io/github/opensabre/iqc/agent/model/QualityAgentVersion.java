package io.github.opensabre.iqc.agent.model;

import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Read-only IAC contract projection; not an IQC persistence entity. */
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityAgentVersion extends BasePo {
    private String agentId;
    private Integer versionNo;
    private String name;
    private String code;
    private String description;
    private String configJson;
    private String status;
}
