package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.evaluation.GoldSampleService;
import io.github.opensabre.iqc.quality.model.QualitySample;
import io.github.opensabre.iqc.quality.model.QualitySampleVersion;
import io.github.opensabre.iqc.shared.IqcPage;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/iqc/quality-operations/samples/{sampleId}/gold")
public class GoldSampleController {
    private final GoldSampleService service;
    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    private final io.github.opensabre.iqc.privacy.ResultReadProjection projection;
    public GoldSampleController(GoldSampleService service) { this(service,null,null); }
    @org.springframework.beans.factory.annotation.Autowired
    public GoldSampleController(GoldSampleService service, io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,
            io.github.opensabre.iqc.privacy.ResultReadProjection projection) {
        this.service=service; this.sensitive=sensitive; this.projection=projection;
    }
    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access access(String required) {
        if(sensitive==null || projection==null)throw IqcException.invalidState("金标敏感读取组件不可用");
        var access=sensitive.current(java.util.Set.of(required));
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet && servlet.getResponse()!=null) {
            servlet.getResponse().setHeader("Cache-Control","private, no-store");
            servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
        return access;
    }

    @GetMapping
    @ResourcePermission(code="iqc:gold:view", name="查看金标草稿", type="iqc", description="查看候选样本的金标标注与审核状态")
    @Audit(operationType=OperationType.QUERY,description="读取金标草稿",module="IQC_PRIVACY",request=false,response=false,key="#sampleId")
    public QualitySample get(@PathVariable String sampleId) {
        var access=access("iqc:gold:view"); return projection.goldSample(service.get(sampleId),access);
    }

    @PutMapping
    @ResourcePermission(code="iqc:gold:annotate", name="标注金标样本", type="iqc", description="冻结输入并标注期望事实与业务结论")
    @Audit(operationType=OperationType.UPDATE, description="标注 IQC 金标样本", module="IQC_SAMPLE", request=false, response=false, key="#sampleId")
    public QualitySample annotate(@PathVariable String sampleId, @RequestBody GoldSampleService.Annotation request) {
        var access=access("iqc:gold:annotate");
        return projection.sampleMetadata(service.annotate(sampleId, request),access);
    }

    @PostMapping("/reviews")
    @ResourcePermission(code="iqc:gold:review", name="审核金标样本", type="iqc", description="独立审核精确金标修订并保存不可变决定")
    @Audit(operationType=OperationType.CREATE, description="审核 IQC 金标样本", module="IQC_SAMPLE", request=false, response=false, key="#sampleId")
    public QualitySampleVersion review(@PathVariable String sampleId, @RequestBody Review request) {
        var access=access("iqc:gold:review");
        if (request == null || request.expectedRevision() == null || request.approve() == null)
            throw IqcException.invalidArgument("审核必须指定修订与批准或退回决定");
        return projection.sampleVersionMetadata(service.review(sampleId, request.expectedRevision(), request.approve(), request.reason()),access);
    }

    @GetMapping("/versions")
    @ResourcePermission(code="iqc:gold:versions:view", name="查看金标审核历史", type="iqc", description="分页查看金标审核版本摘要")
    @Audit(operationType=OperationType.QUERY,description="读取金标审核历史",module="IQC_PRIVACY",request=false,response=false,key="#sampleId")
    public IqcPage<QualitySampleVersion> history(@PathVariable String sampleId,
            @RequestParam(defaultValue="1") long current, @RequestParam(defaultValue="20") long size) {
        var access=access("iqc:gold:versions:view");
        return projection.sampleHistory(service.history(sampleId, current, size),access);
    }

    @GetMapping("/versions/{versionNo}")
    @ResourcePermission(code="iqc:gold:version:view", name="查看金标版本快照", type="iqc", description="查看精确审核版本的冻结输入与期望")
    @Audit(operationType=OperationType.QUERY,description="读取金标版本快照",module="IQC_PRIVACY",request=false,response=false,key="#sampleId + ':' + #versionNo")
    public QualitySampleVersion version(@PathVariable String sampleId, @PathVariable int versionNo) {
        var access=access("iqc:gold:version:view");
        return projection.sampleVersion(service.version(sampleId, versionNo),access);
    }
    public record Review(Integer expectedRevision, Boolean approve, String reason) { }
}
