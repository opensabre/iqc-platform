package io.github.opensabre.iqc.label.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

/** Reusable action metadata only; execution policy remains owned by the result processor. */
@Data @TableName("iqc_label_action_definition")
public class LabelActionDefinition {
    @TableId(type = IdType.ASSIGN_ID)
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long id;
    private String type;
    private String name;
    private String description;
    private String status;
    private Integer versionNo;
    private String ownerGroupId;
    @TableField(fill = FieldFill.INSERT) private String createdBy;
    @TableField(fill = FieldFill.INSERT) private Date createdTime;
    @TableField(fill = FieldFill.INSERT_UPDATE) private String updatedBy;
    @TableField(fill = FieldFill.INSERT_UPDATE) private Date updatedTime;
}
