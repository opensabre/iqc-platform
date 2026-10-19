package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.iqc.selector.*;
import io.github.opensabre.iqc.selector.model.*;
import io.github.opensabre.iqc.shared.IqcPage;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/iqc/selectors/assets")
public class SelectorAssetController {
    private final SelectorAssetService assets;
    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    private final io.github.opensabre.iqc.privacy.ResultReadProjection projection;
    @org.springframework.beans.factory.annotation.Autowired
    public SelectorAssetController(SelectorAssetService assets,io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,
            io.github.opensabre.iqc.privacy.ResultReadProjection projection){this.assets=assets;this.sensitive=sensitive;this.projection=projection;}
    public SelectorAssetController(SelectorAssetService assets){this(assets,null,null);}

    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access access(String resource){
        if(sensitive==null || projection==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("选择器预览授权组件不可用");
        var access=sensitive.current(java.util.Set.of(resource));
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet && servlet.getResponse()!=null){
            servlet.getResponse().setHeader("Cache-Control","private, no-store");servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
        return access;
    }
    public record Create(String code,String name,SelectorDefinition definition,String definitionJson) {
        public Create(String code,String name,SelectorDefinition definition){this(code,name,definition,null);}
    }
    public record Update(int expectedRevision,String name,SelectorDefinition definition,String definitionJson) {
        public Update(int expectedRevision,String name,SelectorDefinition definition){this(expectedRevision,name,definition,null);}
    }
    public record Availability(boolean enabled) { }
    public record Publish(int expectedRevision) { }
    public record Preview(Integer previewSize) { }
    @GetMapping
    @ResourcePermission(code="iqc:selector:asset:view",name="查看选择器目录",type="iqc",description="查看可见选择器资产")
    @Audit(operationType=OperationType.QUERY,description="读取选择器资产",module="IQC_PRIVACY",request=false,response=false)
    public IqcPage<SelectorAsset> page(@RequestParam(defaultValue="1")long current,@RequestParam(defaultValue="20")long size){var access=access("iqc:selector:asset:view");var page=assets.page(current,size);return new IqcPage<>(page.records().stream().map(row -> projection.selectorAsset(row,access,false)).toList(),page.current(),page.size(),page.total());}
    @GetMapping("/{id}")
    @ResourcePermission(code="iqc:selector:asset:detail:view",name="查看选择器详情",type="iqc",description="查看选择器草稿及发布指针")
    @Audit(operationType=OperationType.QUERY,description="读取选择器资产",module="IQC_PRIVACY",request=false,response=false,key="#id")
    public SelectorAsset get(@PathVariable String id){var access=access("iqc:selector:asset:detail:view");return projection.selectorAsset(assets.get(id),access,true);}
    @PostMapping
    @ResourcePermission(code="iqc:selector:asset:create",name="创建选择器资产",type="iqc",description="保存独立选择器草稿")
    @Audit(operationType=OperationType.CREATE,description="创建选择器资产",module="IQC_SELECTOR",request=false,response=false)
    public SelectorAsset create(@RequestBody Create body){var access=access("iqc:selector:asset:create");return projection.selectorAsset(assets.create(body.code(),body.name(),assets.requestDefinition(body.definition(),body.definitionJson())),access,false);}
    @PutMapping("/{id}")
    @ResourcePermission(code="iqc:selector:asset:update",name="修订选择器资产",type="iqc",description="按修订保存选择器草稿")
    @Audit(operationType=OperationType.UPDATE,description="修订选择器资产",module="IQC_SELECTOR",request=false,response=false,key="#id")
    public SelectorAsset update(@PathVariable String id,@RequestBody Update body){var access=access("iqc:selector:asset:update");return projection.selectorAsset(assets.update(id,body.expectedRevision(),body.name(),assets.requestDefinition(body.definition(),body.definitionJson())),access,false);}
    @PostMapping("/{id}/availability")
    @ResourcePermission(code="iqc:selector:asset:availability",name="启停选择器资产",type="iqc",description="启停选择器新使用")
    @Audit(operationType=OperationType.UPDATE,description="启停选择器资产",module="IQC_SELECTOR",request=false,response=false,key="#id")
    public SelectorAsset availability(@PathVariable String id,@RequestBody Availability body){var access=access("iqc:selector:asset:availability");return projection.selectorAsset(assets.availability(id,body.enabled()),access,false);}
    @GetMapping("/{id}/versions")
    @ResourcePermission(code="iqc:selector:asset:versions:view",name="查看选择器发布历史",type="iqc",description="查看不可变选择器版本")
    @Audit(operationType=OperationType.QUERY,description="读取选择器资产",module="IQC_PRIVACY",request=false,response=false,key="#id")
    public List<SelectorAssetVersion> history(@PathVariable String id){var access=access("iqc:selector:asset:versions:view");return assets.history(id).stream().map(row -> projection.selectorHistory(row,access)).toList();}
    @PostMapping("/{id}/publish")
    @ResourcePermission(code="iqc:selector:asset:publish",name="发布选择器版本",type="iqc",description="冻结当前选择器修订")
    @Audit(operationType=OperationType.UPDATE,description="发布选择器版本",module="IQC_SELECTOR",request=false,response=false,key="#id")
    public SelectorAssetVersion publish(@PathVariable String id,@RequestBody Publish body){var access=access("iqc:selector:asset:publish");return projection.selectorHistory(assets.publish(id,body.expectedRevision()),access);}
    @GetMapping("/{id}/versions/{versionNo}")
    @ResourcePermission(code="iqc:selector:asset:version:view",name="查看精确选择器版本",type="iqc",description="解析已发布选择器定义")
    @Audit(operationType=OperationType.QUERY,description="读取选择器资产",module="IQC_PRIVACY",request=false,response=false,key="#id + ':' + #versionNo")
    public SelectorAssetService.Prepared version(@PathVariable String id,@PathVariable int versionNo){var access=access("iqc:selector:asset:version:view");return projection.publishedSelector(assets.viewVersion(id,versionNo),access);}
    @PostMapping("/{id}/versions/{versionNo}/preview")
    @ResourcePermission(code="iqc:selector:asset:version:preview",name="预览选择器发布版本",type="iqc",description="按当前授权数据预览精确选择器版本")
    @RateLimit(sceneCode="iqc-selector-asset-preview",maxCount=20,period=60)
    @Audit(operationType=OperationType.QUERY,description="预览已发布选择器选数",module="IQC_PRIVACY",request=false,response=false,key="#id + ':' + #versionNo")
    public ConversationSelectionService.Preview preview(@PathVariable String id,@PathVariable int versionNo,@RequestBody Preview body){
        var access=access("iqc:selector:asset:version:preview");
        return projection.selectorPreview(assets.preview(id,versionNo,body.previewSize()==null?10:body.previewSize()),access);
    }
}
