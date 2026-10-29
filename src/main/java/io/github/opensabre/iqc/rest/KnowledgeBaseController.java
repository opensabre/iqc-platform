package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.*;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.knowledge.KnowledgeBaseService;
import io.github.opensabre.iqc.privacy.*;
import io.github.opensabre.iqc.shared.IqcPage;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/iqc/knowledge-bases") @lombok.RequiredArgsConstructor
public class KnowledgeBaseController {
    private final KnowledgeBaseService bases;
    private final SensitiveReadAccessService sensitive;
    private final ResultReadProjection projection;
    private SensitiveReadAccessService.Access access(String resource){
        var result=sensitive.current(java.util.Set.of(resource));
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet&&servlet.getResponse()!=null){
            servlet.getResponse().setHeader("Cache-Control","private, no-store");servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
        return result;
    }
    public record Create(String code,String name,String corpusJson) { }
    public record Update(
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class)
            @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class)
            JsonNode expectedRevision,String name,String corpusJson) { }
    public record Revision(
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class)
            @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class)
            JsonNode expectedRevision) { }
    public record Availability(
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.ExactValueDeserializer.class)
            @tools.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.quality.HumanFindingReviewEvaluator.HttpExactValueDeserializer.class)
            JsonNode enabled) { }
    private static int revision(JsonNode value){
        if(value==null||!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<1)throw IqcException.invalidArgument("须提供正整数草稿修订");return value.intValue();
    }
    @GetMapping @ResourcePermission(code="iqc:knowledge:view",name="查看知识库目录",type="iqc",description="按组织范围读取知识库元数据")
    @Audit(operationType=OperationType.QUERY,description="读取知识库目录",module="IQC_PRIVACY",request=false,response=false)
    public IqcPage<ResultReadProjection.KnowledgeView> page(@RequestParam(defaultValue="1")long current,@RequestParam(defaultValue="20")long size){
        var access=access("iqc:knowledge:view");var page=bases.page(current,size);
        return new IqcPage<>(page.records().stream().map(row->projection.knowledge(row,access,false)).toList(),page.current(),page.size(),page.total());
    }
    @GetMapping("/{id}") @ResourcePermission(code="iqc:knowledge:detail:view",name="查看知识库详情",type="iqc",description="读取可见知识库草稿及敏感投影")
    @Audit(operationType=OperationType.QUERY,description="读取知识库详情",module="IQC_PRIVACY",request=false,response=false,key="#id")
    public ResultReadProjection.KnowledgeView get(@PathVariable String id){var access=access("iqc:knowledge:detail:view");return projection.knowledge(bases.get(id),access,true);}
    @PostMapping @ResourcePermission(code="iqc:knowledge:create",name="创建知识库",type="iqc",description="创建独立参考语料草稿")
    @Audit(operationType=OperationType.CREATE,description="创建知识库",module="IQC_KNOWLEDGE",request=false,response=false)
    public ResultReadProjection.KnowledgeView create(@RequestBody Create body){var access=access("iqc:knowledge:create");if(body==null)throw IqcException.invalidArgument("知识库请求不能为空");return projection.knowledge(bases.create(body.code(),body.name(),bases.requestCorpus(body.corpusJson())),access,false);}
    @PutMapping("/{id}") @ResourcePermission(code="iqc:knowledge:update",name="修订知识库",type="iqc",description="按组织范围和草稿修订保存知识库")
    @Audit(operationType=OperationType.UPDATE,description="修订知识库",module="IQC_KNOWLEDGE",request=false,response=false,key="#id")
    public ResultReadProjection.KnowledgeView update(@PathVariable String id,@RequestBody Update body){var access=access("iqc:knowledge:update");if(body==null)throw IqcException.invalidArgument("知识库请求不能为空");return projection.knowledge(bases.update(id,revision(body.expectedRevision()),body.name(),bases.requestCorpus(body.corpusJson())),access,false);}
    @PostMapping("/{id}/availability") @ResourcePermission(code="iqc:knowledge:availability",name="启停知识库",type="iqc",description="控制后续知识库新引用")
    @Audit(operationType=OperationType.UPDATE,description="启停知识库",module="IQC_KNOWLEDGE",request=false,response=false,key="#id")
    public ResultReadProjection.KnowledgeView availability(@PathVariable String id,@RequestBody Availability body){var access=access("iqc:knowledge:availability");if(body==null||body.enabled()==null||!body.enabled().isBoolean())throw IqcException.invalidArgument("须提供布尔启停状态");return projection.knowledge(bases.availability(id,body.enabled().booleanValue()),access,false);}
    @GetMapping("/{id}/versions") @ResourcePermission(code="iqc:knowledge:versions:view",name="查看知识库发布历史",type="iqc",description="读取不可变语料版本元数据")
    @Audit(operationType=OperationType.QUERY,description="读取知识库发布历史",module="IQC_PRIVACY",request=false,response=false,key="#id")
    public List<ResultReadProjection.KnowledgeVersionView> history(@PathVariable String id){var access=access("iqc:knowledge:versions:view");return bases.historyMetadata(id).stream().map(row->projection.knowledgeHistory(row,access)).toList();}
    @GetMapping("/{id}/versions/{versionNo}") @ResourcePermission(code="iqc:knowledge:version:view",name="查看精确知识库版本",type="iqc",description="独立授权读取原版本正文投影")
    @Audit(operationType=OperationType.QUERY,description="读取知识库精确版本",module="IQC_PRIVACY",request=false,response=false,key="#id + ':' + #versionNo")
    public ResultReadProjection.KnowledgeVersionView version(@PathVariable String id,@PathVariable int versionNo){var access=access("iqc:knowledge:version:view");return projection.knowledgeVersion(bases.viewVersion(id,versionNo),access);}
    @PostMapping("/{id}/publish") @ResourcePermission(code="iqc:knowledge:publish",name="发布知识库版本",type="iqc",description="冻结当前草稿并保留原来源")
    @Audit(operationType=OperationType.UPDATE,description="发布知识库版本",module="IQC_KNOWLEDGE",request=false,response=false,key="#id")
    public ResultReadProjection.KnowledgeVersionView publish(@PathVariable String id,@RequestBody Revision body){var access=access("iqc:knowledge:publish");if(body==null)throw IqcException.invalidArgument("缺少草稿修订");return projection.knowledgeHistory(bases.publish(id,revision(body.expectedRevision())),access);}
    @PostMapping("/{id}/versions/{versionNo}/restore") @ResourcePermission(code="iqc:knowledge:restore",name="恢复知识库历史为草稿",type="iqc",description="按当前修订向前恢复而不修改原版本")
    @Audit(operationType=OperationType.UPDATE,description="恢复知识库草稿",module="IQC_KNOWLEDGE",request=false,response=false,key="#id + ':' + #versionNo")
    public ResultReadProjection.KnowledgeView restore(@PathVariable String id,@PathVariable int versionNo,@RequestBody Revision body){var access=access("iqc:knowledge:restore");if(body==null)throw IqcException.invalidArgument("缺少草稿修订");return projection.knowledge(bases.restore(id,revision(body.expectedRevision()),versionNo),access,false);}
}
