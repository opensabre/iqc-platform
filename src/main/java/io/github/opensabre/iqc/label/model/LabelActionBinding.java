package io.github.opensabre.iqc.label.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.util.Date;

/** A label references one reusable action once; no implicit scoring parameters. */
@Data @TableName("iqc_label_action_binding")
public class LabelActionBinding {
    @TableId(type = IdType.ASSIGN_ID)
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long id;
    private String labelId;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long actionId;
    private Integer displayOrder;
    @TableField(fill = FieldFill.INSERT) private String createdBy;
    @TableField(fill = FieldFill.INSERT) private Date createdTime;
    @TableField(fill = FieldFill.INSERT_UPDATE) private String updatedBy;
    @TableField(fill = FieldFill.INSERT_UPDATE) private Date updatedTime;
}
