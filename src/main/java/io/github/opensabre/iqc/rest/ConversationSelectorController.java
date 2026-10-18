package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.iqc.selector.ConversationSelectionService;
import io.github.opensabre.iqc.selector.SelectorDefinition;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/iqc/selectors")
public class ConversationSelectorController {
    private final ConversationSelectionService selections;
    private final io.github.opensabre.iqc.selector.SelectorAssetService assets;
    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    private final io.github.opensabre.iqc.privacy.ResultReadProjection projection;
    @org.springframework.beans.factory.annotation.Autowired
    public ConversationSelectorController(ConversationSelectionService selections,io.github.opensabre.iqc.selector.SelectorAssetService assets,
            io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive,io.github.opensabre.iqc.privacy.ResultReadProjection projection){
        this.selections=selections;this.assets=assets;this.sensitive=sensitive;this.projection=projection;
    }
    public ConversationSelectorController(ConversationSelectionService selections){this(selections,null,null,null);}
    public record PreviewRequest(SelectorDefinition definition,Integer previewSize,String definitionJson) {
        public PreviewRequest(SelectorDefinition definition,Integer previewSize){this(definition,previewSize,null);}
    }
    @PostMapping("/preview")
    @ResourcePermission(code="iqc:selector:preview",name="预览会话选择器",type="iqc",description="按授权范围预览条件与抽样选数")
    @RateLimit(sceneCode="iqc-selector-preview",maxCount=20,period=60)
    @io.github.opensabre.governance.audit.annotations.Audit(operationType=io.github.opensabre.governance.audit.annotations.OperationType.QUERY,description="预览选择器选数",module="IQC_PRIVACY",request=false,response=false)
    public ConversationSelectionService.Preview preview(@RequestBody PreviewRequest body) {
        if(sensitive==null || projection==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("选择器预览授权组件不可用");
        var access=sensitive.current(java.util.Set.of("iqc:selector:preview"));
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servlet && servlet.getResponse()!=null){
            servlet.getResponse().setHeader("Cache-Control","private, no-store");servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");
        }
        if(body.definitionJson()!=null && assets==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("精确选择器解析组件不可用");
        return projection.selectorPreview(selections.preview(body.definitionJson()==null?body.definition():assets.requestDefinition(body.definition(),body.definitionJson()),body.previewSize()==null?10:body.previewSize()),access);
    }
}
