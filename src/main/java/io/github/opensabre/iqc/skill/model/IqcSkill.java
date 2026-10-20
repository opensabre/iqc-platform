package io.github.opensabre.iqc.skill.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Reusable Skill definition selected by Agent versions. */
@Data
@TableName("iqc_skill")
@EqualsAndHashCode(callSuper = true)
public class IqcSkill extends BasePo {
    private String name;
    private String code;
    private String description;
    private String instructions;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String status;
    private Integer versionNo;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.Map<String,Object> privacy;
}
