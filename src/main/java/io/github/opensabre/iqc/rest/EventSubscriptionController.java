package io.github.opensabre.iqc.rest;
import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.schedule.*;
import io.github.opensabre.iqc.schedule.model.*;
import io.github.opensabre.iqc.shared.IqcPage;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/iqc/event-subscriptions")
public class EventSubscriptionController {
    private final EventSubscriptionService schedules;
    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    private final io.github.opensabre.iqc.privacy.ResultReadProjection projection;
    @org.springframework.beans.factory.annotation.Autowired
    public EventSubscriptionController(EventSubscriptionService schedules,
            io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,
            io.github.opensabre.iqc.privacy.ResultReadProjection projection){
        this.schedules=schedules;this.sensitive=sensitive;this.projection=projection;
    }
    public EventSubscriptionController(EventSubscriptionService schedules){this(schedules,null,null);}

    public record Create(String code,String name,EventSubscriptionDefinition definition) { }
    public record Update(int expectedRevision,String name,EventSubscriptionDefinition definition) { }
    public record Publish(int expectedRevision) { }
    public record Availability(boolean enabled) { }
    @GetMapping @ResourcePermission(code="iqc:subscription:view",name="查看事件订阅目录",type="iqc",description="查看授权事件订阅")
    public IqcPage<EventSubscription> page(@RequestParam(defaultValue="1")long current,@RequestParam(defaultValue="20")long size){return schedules.page(current,size);}
    @GetMapping("/{id}") @ResourcePermission(code="iqc:subscription:detail:view",name="查看事件订阅",type="iqc",description="查看事件订阅草稿")
    public EventSubscription get(@PathVariable String id){return schedules.get(id);}
    @PostMapping @ResourcePermission(code="iqc:subscription:create",name="创建事件订阅",type="iqc",description="创建负责人事件订阅草稿")
    @Audit(operationType=OperationType.CREATE,description="创建事件订阅",module="IQC_SUBSCRIPTION",request=false,response=false)
    public EventSubscription create(@RequestBody Create body){return schedules.create(body.code(),body.name(),body.definition());}
    @PutMapping("/{id}") @ResourcePermission(code="iqc:subscription:update",name="修订事件订阅",type="iqc",description="按修订更新负责人事件订阅草稿")
    @Audit(operationType=OperationType.UPDATE,description="修订事件订阅",module="IQC_SUBSCRIPTION",request=false,response=false,key="#id")
    public EventSubscription update(@PathVariable String id,@RequestBody Update body){return schedules.update(id,body.expectedRevision(),body.name(),body.definition());}
    @PostMapping("/{id}/publish") @ResourcePermission(code="iqc:subscription:publish",name="发布事件订阅修订",type="iqc",description="冻结事件订阅及资产来源")
    @Audit(operationType=OperationType.UPDATE,description="发布事件订阅修订",module="IQC_SUBSCRIPTION",request=false,response=false,key="#id")
    public EventSubscriptionRevision publish(@PathVariable String id,@RequestBody Publish body){return schedules.publish(id,body.expectedRevision());}
    @PostMapping("/{id}/availability") @ResourcePermission(code="iqc:subscription:availability",name="启停事件订阅",type="iqc",description="启停当前已发布事件订阅")
    @Audit(operationType=OperationType.UPDATE,description="启停事件订阅",module="IQC_SUBSCRIPTION",request=false,response=false,key="#id")
    public EventSubscription availability(@PathVariable String id,@RequestBody Availability body){return schedules.availability(id,body.enabled());}
    @GetMapping("/{id}/versions") @ResourcePermission(code="iqc:subscription:versions:view",name="查看事件订阅历史",type="iqc",description="查看不可变事件订阅修订")
    public List<EventSubscriptionRevision> versions(@PathVariable String id){return schedules.history(id);}
    @GetMapping("/{id}/instances") @ResourcePermission(code="iqc:subscription:instances:view",name="查看事件订阅实例",type="iqc",description="查看授权原任务实例与轮次")
    @Audit(operationType=OperationType.QUERY,description="读取调度任务实例",module="IQC_PRIVACY",request=false,response=false,key="#id")
    public IqcPage<InspectionTask> instances(@PathVariable String id,@RequestParam(defaultValue="1")long current,@RequestParam(defaultValue="20")long size){
        if(sensitive==null || projection==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("调度实例响应授权组件不可用");
        var access=sensitive.current(java.util.Set.of("iqc:subscription:instances:view"));
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet && servlet.getResponse()!=null){
            servlet.getResponse().setHeader("Cache-Control","private, no-store");
            servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
        var page=schedules.instances(id,current,size);
        return new IqcPage<>(page.records().stream().map(row -> projection.taskRead(row,access,false)).toList(),page.current(),page.size(),page.total());
    }
}
