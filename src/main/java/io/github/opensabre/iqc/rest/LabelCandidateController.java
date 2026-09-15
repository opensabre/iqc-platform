package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.label.LabelCandidateService;
import io.github.opensabre.iqc.label.model.LabelCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/iqc/label-candidates")
@RequiredArgsConstructor
public class LabelCandidateController {
    private final LabelCandidateService service;

    @GetMapping
    @ResourcePermission(code="iqc:label-candidate:view", name="查看候选标签", type="iqc", description="查询 AI 候选标签池")
    public List<LabelCandidate> list(@RequestParam(required=false) String status) { return service.list(status); }

    @GetMapping("/{id}/similarities")
    @ResourcePermission(code="iqc:label-candidate:view", name="查看候选相似标签", type="iqc", description="查询候选标签的相似正式标签")
    public List<LabelCandidateService.Similarity> similarities(@PathVariable String id) { return service.similarities(id); }

    @PostMapping("/{id}/reject")
    @ResourcePermission(code="iqc:label-candidate:review", name="审核候选标签", type="iqc", description="拒绝 AI 候选标签")
    @Audit(operationType=OperationType.UPDATE, description="拒绝 IQC 候选标签", module="IQC_LABEL")
    public LabelCandidate reject(@PathVariable String id, @RequestBody ReviewRequest request) { return service.reject(id, request.comment()); }

    @PostMapping("/{id}/merge")
    @ResourcePermission(code="iqc:label-candidate:review", name="审核候选标签", type="iqc", description="将候选合并到现有标签")
    @Audit(operationType=OperationType.UPDATE, description="合并 IQC 候选标签", module="IQC_LABEL")
    public LabelCandidate merge(@PathVariable String id, @RequestBody ReviewRequest request) { return service.merge(id, request.labelId(), request.comment()); }

    @PostMapping("/{id}/approve")
    @ResourcePermission(code="iqc:label-candidate:review", name="审核候选标签", type="iqc", description="批准候选为标签草稿")
    @Audit(operationType=OperationType.UPDATE, description="批准 IQC 候选标签为草稿", module="IQC_LABEL")
    public LabelCandidate approve(@PathVariable String id, @RequestBody ReviewRequest request) {
        return service.approveAsDraft(id, request.groupId(), request.targetRole(), request.weight(), request.comment());
    }

    public record ReviewRequest(String labelId, String groupId, String targetRole, BigDecimal weight, String comment) { }
}
