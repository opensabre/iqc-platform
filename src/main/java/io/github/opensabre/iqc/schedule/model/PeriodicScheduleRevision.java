package io.github.opensabre.iqc.schedule.model;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper=true) @TableName("iqc_periodic_schedule_revision")
public class PeriodicScheduleRevision extends BasePo {
    private String scheduleId;
    private Integer versionNo;
    private Integer sourceDraftRevision;
    private String snapshotJson;
    private String contentHash;
    @com.baomidou.mybatisplus.annotation.TableField(exist=false)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.util.Map<String,Object> privacy;

}
