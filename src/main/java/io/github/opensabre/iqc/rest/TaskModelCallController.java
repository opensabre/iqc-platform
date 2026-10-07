package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.iqc.result.llm.gateway.GatewayCallService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/iqc/tasks/{id}/model-calls")
public class TaskModelCallController {
    private final GatewayCallService calls;
    @GetMapping
    @ResourcePermission(code="iqc:task:model-calls:view",name="查看任务模型调用",type="iqc",description="按任务数据范围查询真实执行轮次的模型调用")
    public GatewayCallService.CallPage page(@PathVariable String id,@RequestParam(required=false) String executionId,
            @RequestParam(required=false) String status,@RequestParam(defaultValue="1") long current,@RequestParam(defaultValue="20") long size){
        return calls.taskPageWithSummary(id,executionId,status,current,size);
    }
}
