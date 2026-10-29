package io.github.opensabre.iqc.rest;
import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.iqc.schedule.TriggerAttemptService;
import io.github.opensabre.iqc.shared.IqcPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/iqc/trigger-attempts")
public class TriggerAttemptController {
    private final TriggerAttemptService attempts;
    @GetMapping @ResourcePermission(code="iqc:trigger:attempts:view",name="查看触发尝试历史",type="iqc",description="查看授权父对象的不可变触发尝试事实")
    public IqcPage<TriggerAttemptService.View> page(@RequestParam TriggerAttemptService.Kind kind,@RequestParam String parentId,
            @RequestParam(defaultValue="1")long current,@RequestParam(defaultValue="20")long size){return attempts.page(kind,parentId,current,size);}
}
