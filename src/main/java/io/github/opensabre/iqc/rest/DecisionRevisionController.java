package io.github.opensabre.iqc.rest;
import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.decision.DecisionRevisionService;
import io.github.opensabre.iqc.decision.model.DecisionRevision;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/iqc/conversation-results/{id}/decision-revisions")
public class DecisionRevisionController {
    private final DecisionRevisionService service;
    public record Recalculate(String targetPipelineId,int targetVersion,String requestId,String reason) { }
    @PostMapping
    @ResourcePermission(code="iqc:decision:recalculate",name="重算结果政策",type="iqc",description="使用已保存事实和发布政策重算，不调用模型")
    @Audit(operationType=OperationType.CREATE,description="创建政策重算记录",module="IQC_DECISION")
    public DecisionRevision create(@PathVariable String id,@RequestBody Recalculate body) {
        return service.create(id,body.targetPipelineId(),body.targetVersion(),body.requestId(),body.reason());
    }
    @GetMapping
    @ResourcePermission(code="iqc:decision:history:view",name="查看政策重算历史",type="iqc",description="查看与原始机器结果分离的重算记录")
    public List<DecisionRevision> history(@PathVariable String id) { return service.history(id); }
}
