package io.github.opensabre.iqc.rest;
import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.schedule.*;
import io.github.opensabre.iqc.schedule.model.*;
import io.github.opensabre.iqc.shared.IqcPage;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
@RestController @RequestMapping("/api/iqc/periodic-schedules")
public class PeriodicScheduleController {
    private final PeriodicScheduleService schedules;
    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    private final io.github.opensabre.iqc.privacy.ResultReadProjection projection;
    @org.springframework.beans.factory.annotation.Autowired
    public PeriodicScheduleController(PeriodicScheduleService schedules,
            io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,
            io.github.opensabre.iqc.privacy.ResultReadProjection projection){
        this.schedules=schedules;this.sensitive=sensitive;this.projection=projection;
    }
    public PeriodicScheduleController(PeriodicScheduleService schedules){this(schedules,null,null);}

    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access access(String resource){
        if(sensitive==null || projection==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("调度实例响应授权组件不可用");
        var access=sensitive.current(java.util.Set.of(resource));
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet && servlet.getResponse()!=null){
            servlet.getResponse().setHeader("Cache-Control","private, no-store");
            servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
        return access;
    }
    public record Create(String code,String name,PeriodicDefinition definition) { }
    public record Update(int expectedRevision,String name,PeriodicDefinition definition) { }
    public record Publish(int expectedRevision) { }
    public record Availability(boolean enabled) { }
    public record Preview(PeriodicDefinition definition,Instant from,int size) { }
    @GetMapping @ResourcePermission(code="iqc:schedule:view",name="查看周期目录",type="iqc",description="查看授权周期定义")
    @Audit(operationType=OperationType.QUERY,description="读取调度目录",module="IQC_PRIVACY",request=false,response=false)
    public IqcPage<PeriodicSchedule> page(@RequestParam(defaultValue="1")long current,@RequestParam(defaultValue="20")long size){var access=access("iqc:schedule:view");var page=schedules.page(current,size);return new IqcPage<>(page.records().stream().map(row -> projection.periodicSchedule(row,access,false)).toList(),page.current(),page.size(),page.total());}
    @GetMapping("/{id}") @ResourcePermission(code="iqc:schedule:detail:view",name="查看周期定义",type="iqc",description="查看周期草稿")
    @Audit(operationType=OperationType.QUERY,description="读取调度草稿",module="IQC_PRIVACY",request=false,response=false,key="#id")
    public PeriodicSchedule get(@PathVariable String id){var access=access("iqc:schedule:detail:view");return projection.periodicSchedule(schedules.get(id),access,true);}
    @PostMapping @ResourcePermission(code="iqc:schedule:create",name="创建周期定义",type="iqc",description="创建负责人周期草稿")
    @Audit(operationType=OperationType.CREATE,description="创建周期定义",module="IQC_SCHEDULE",request=false,response=false)
    public PeriodicSchedule create(@RequestBody Create body){var access=access("iqc:schedule:create");return projection.periodicSchedule(schedules.create(body.code(),body.name(),body.definition()),access,false);}
    @PutMapping("/{id}") @ResourcePermission(code="iqc:schedule:update",name="修订周期定义",type="iqc",description="按修订更新负责人周期草稿")
    @Audit(operationType=OperationType.UPDATE,description="修订周期定义",module="IQC_SCHEDULE",request=false,response=false,key="#id")
    public PeriodicSchedule update(@PathVariable String id,@RequestBody Update body){var access=access("iqc:schedule:update");return projection.periodicSchedule(schedules.update(id,body.expectedRevision(),body.name(),body.definition()),access,false);}
    @PostMapping("/{id}/publish") @ResourcePermission(code="iqc:schedule:publish",name="发布周期修订",type="iqc",description="冻结周期及资产来源")
    @Audit(operationType=OperationType.UPDATE,description="发布周期修订",module="IQC_SCHEDULE",request=false,response=false,key="#id")
    public PeriodicScheduleRevision publish(@PathVariable String id,@RequestBody Publish body){var access=access("iqc:schedule:publish");return projection.periodicRevision(schedules.publish(id,body.expectedRevision()),access,false);}
    @PostMapping("/{id}/availability") @ResourcePermission(code="iqc:schedule:availability",name="启停周期调度",type="iqc",description="启停当前已发布周期")
    @Audit(operationType=OperationType.UPDATE,description="启停周期调度",module="IQC_SCHEDULE",request=false,response=false,key="#id")
    public PeriodicSchedule availability(@PathVariable String id,@RequestBody Availability body){var access=access("iqc:schedule:availability");return projection.periodicSchedule(schedules.availability(id,body.enabled()),access,false);}
    @GetMapping("/{id}/versions") @ResourcePermission(code="iqc:schedule:versions:view",name="查看周期发布历史",type="iqc",description="查看不可变周期修订")
    @Audit(operationType=OperationType.QUERY,description="读取调度版本历史",module="IQC_PRIVACY",request=false,response=false,key="#id")
    public List<PeriodicScheduleRevision> versions(@PathVariable String id){var access=access("iqc:schedule:versions:view");return schedules.history(id).stream().map(row -> projection.periodicRevision(row,access,true)).toList();}
    @GetMapping("/{id}/instances") @ResourcePermission(code="iqc:schedule:instances:view",name="查看周期实例",type="iqc",description="查看授权原任务实例与轮次")
    @Audit(operationType=OperationType.QUERY,description="读取调度任务实例",module="IQC_PRIVACY",request=false,response=false,key="#id")
    public IqcPage<InspectionTask> instances(@PathVariable String id,@RequestParam(defaultValue="1")long current,@RequestParam(defaultValue="20")long size){
        var access=access("iqc:schedule:instances:view");
        var page=schedules.instances(id,current,size);
        return new IqcPage<>(page.records().stream().map(row -> projection.taskRead(row,access,false)).toList(),page.current(),page.size(),page.total());
    }
    @PostMapping("/preview") @ResourcePermission(code="iqc:schedule:preview",name="预览周期计划时刻",type="iqc",description="按Cron时区及生效范围预览")
    public List<Instant> preview(@RequestBody Preview body){access("iqc:schedule:preview");return schedules.preview(body.definition(),body.from(),body.size());}
}
