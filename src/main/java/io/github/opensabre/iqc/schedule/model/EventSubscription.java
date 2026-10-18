package io.github.opensabre.iqc.schedule.model;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper=true) @TableName("iqc_event_subscription")
public class EventSubscription extends BasePo {
    private String code;
    private String name;
    private String draftConfigJson;
    private Integer draftRevision;
    private String activeRevisionId;
    private String status;
    @TableField(updateStrategy=FieldStrategy.ALWAYS)
    private java.time.LocalDateTime lastScannedAtUtc;
    @TableField(updateStrategy=FieldStrategy.ALWAYS)
    private java.time.LocalDateTime retryNotBeforeUtc;
    @TableField(updateStrategy=FieldStrategy.ALWAYS)
    private String lastError;
    @TableField(updateStrategy=FieldStrategy.ALWAYS)
    private String ownerGroupId;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.Map<String,Object> privacy;

}
