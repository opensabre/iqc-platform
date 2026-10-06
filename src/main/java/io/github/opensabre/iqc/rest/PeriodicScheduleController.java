package io.github.opensabre.iqc.rest;
import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.schedule.*;
import io.github.opensabre.iqc.schedule.model.*;
import io.github.opensabre.iqc.shared.IqcPage;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
@RestController @RequiredArgsConstructor @RequestMapping("/api/iqc/periodic-schedules")
public class PeriodicScheduleController {
    private final PeriodicScheduleService schedules;
    public record Create(String code,String name,PeriodicDefinition definition) { }
    public record Update(int expectedRevision,String name,PeriodicDefinition definition) { }
    public record Publish(int expectedRevision) { }
    public record Availability(boolean enabled) { }
    public record Preview(PeriodicDefinition definition,Instant from,int size) { }
    @GetMapping @ResourcePermission(code="iqc:schedule:view",name="查看周期目录",type="iqc",description="查看授权周期定义")
    public IqcPage<PeriodicSchedule> page(@RequestParam(defaultValue="1")long current,@RequestParam(defaultValue="20")long size){return schedules.page(current,size);}
    @GetMapping("/{id}") @ResourcePermission(code="iqc:schedule:detail:view",name="查看周期定义",type="iqc",description="查看周期草稿")
    public PeriodicSchedule get(@PathVariable String id){return schedules.get(id);}
    @PostMapping @ResourcePermission(code="iqc:schedule:create",name="创建周期定义",type="iqc",description="创建负责人周期草稿")
    @Audit(operationType=OperationType.CREATE,description="创建周期定义",module="IQC_SCHEDULE")
    public PeriodicSchedule create(@RequestBody Create body){return schedules.create(body.code(),body.name(),body.definition());}
    @PutMapping("/{id}") @ResourcePermission(code="iqc:schedule:update",name="修订周期定义",type="iqc",description="按修订更新负责人周期草稿")
    @Audit(operationType=OperationType.UPDATE,description="修订周期定义",module="IQC_SCHEDULE")
    public PeriodicSchedule update(@PathVariable String id,@RequestBody Update body){return schedules.update(id,body.expectedRevision(),body.name(),body.definition());}
    @PostMapping("/{id}/publish") @ResourcePermission(code="iqc:schedule:publish",name="发布周期修订",type="iqc",description="冻结周期及资产来源")
    @Audit(operationType=OperationType.UPDATE,description="发布周期修订",module="IQC_SCHEDULE",response=true,key="#id")
    public PeriodicScheduleRevision publish(@PathVariable String id,@RequestBody Publish body){return schedules.publish(id,body.expectedRevision());}
    @PostMapping("/{id}/availability") @ResourcePermission(code="iqc:schedule:availability",name="启停周期调度",type="iqc",description="启停当前已发布周期")
    @Audit(operationType=OperationType.UPDATE,description="启停周期调度",module="IQC_SCHEDULE")
    public PeriodicSchedule availability(@PathVariable String id,@RequestBody Availability body){return schedules.availability(id,body.enabled());}
    @GetMapping("/{id}/versions") @ResourcePermission(code="iqc:schedule:versions:view",name="查看周期发布历史",type="iqc",description="查看不可变周期修订")
    public List<PeriodicScheduleRevision> versions(@PathVariable String id){return schedules.history(id);}
    @GetMapping("/{id}/instances") @ResourcePermission(code="iqc:schedule:instances:view",name="查看周期实例",type="iqc",description="查看授权原任务实例与轮次")
    public IqcPage<InspectionTask> instances(@PathVariable String id,@RequestParam(defaultValue="1")long current,@RequestParam(defaultValue="20")long size){return schedules.instances(id,current,size);}
    @PostMapping("/preview") @ResourcePermission(code="iqc:schedule:preview",name="预览周期计划时刻",type="iqc",description="按Cron时区及生效范围预览")
    public List<Instant> preview(@RequestBody Preview body){return schedules.preview(body.definition(),body.from(),body.size());}
}
