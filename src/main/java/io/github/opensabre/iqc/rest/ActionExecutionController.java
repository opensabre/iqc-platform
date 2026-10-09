package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.iqc.action.ActionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/iqc/conversation-results/{id}/actions")
public class ActionExecutionController {
    private final ActionQueryService queries;
    @GetMapping
    @ResourcePermission(code="iqc:action:history:view",name="查看结果动作记录",type="iqc",description="查看授权会话结果的动作状态，不返回冻结输入或策略配置")
    public List<ActionQueryService.View> history(@PathVariable String id){return queries.history(id);}
}
