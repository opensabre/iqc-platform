package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.evaluation.GoldSampleService;
import io.github.opensabre.iqc.quality.model.QualitySample;
import io.github.opensabre.iqc.quality.model.QualitySampleVersion;
import io.github.opensabre.iqc.shared.IqcPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/iqc/quality-operations/samples/{sampleId}/gold")
public class GoldSampleController {
    private final GoldSampleService service;

    @GetMapping
    @ResourcePermission(code="iqc:gold:view", name="查看金标草稿", type="iqc", description="查看候选样本的金标标注与审核状态")
    public QualitySample get(@PathVariable String sampleId) { return service.get(sampleId); }

    @PutMapping
    @ResourcePermission(code="iqc:gold:annotate", name="标注金标样本", type="iqc", description="冻结输入并标注期望事实与业务结论")
    @Audit(operationType=OperationType.UPDATE, description="标注 IQC 金标样本", module="IQC_SAMPLE", request=false, response=false, key="#sampleId")
    public QualitySample annotate(@PathVariable String sampleId, @RequestBody GoldSampleService.Annotation request) {
        return service.annotate(sampleId, request);
    }

    @PostMapping("/reviews")
    @ResourcePermission(code="iqc:gold:review", name="审核金标样本", type="iqc", description="独立审核精确金标修订并保存不可变决定")
    @Audit(operationType=OperationType.CREATE, description="审核 IQC 金标样本", module="IQC_SAMPLE", request=false, response=false, key="#sampleId")
    public QualitySampleVersion review(@PathVariable String sampleId, @RequestBody Review request) {
        if (request == null || request.expectedRevision() == null || request.approve() == null)
            throw IqcException.invalidArgument("审核必须指定修订与批准或退回决定");
        return service.review(sampleId, request.expectedRevision(), request.approve(), request.reason());
    }

    @GetMapping("/versions")
    @ResourcePermission(code="iqc:gold:versions:view", name="查看金标审核历史", type="iqc", description="分页查看金标审核版本摘要")
    public IqcPage<QualitySampleVersion> history(@PathVariable String sampleId,
            @RequestParam(defaultValue="1") long current, @RequestParam(defaultValue="20") long size) {
        return service.history(sampleId, current, size);
    }

    @GetMapping("/versions/{versionNo}")
    @ResourcePermission(code="iqc:gold:version:view", name="查看金标版本快照", type="iqc", description="查看精确审核版本的冻结输入与期望")
    public QualitySampleVersion version(@PathVariable String sampleId, @PathVariable int versionNo) {
        return service.version(sampleId, versionNo);
    }
    public record Review(Integer expectedRevision, Boolean approve, String reason) { }
}
