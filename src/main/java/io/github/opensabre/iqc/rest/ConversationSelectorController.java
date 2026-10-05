package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.iqc.selector.ConversationSelectionService;
import io.github.opensabre.iqc.selector.SelectorDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/iqc/selectors")
public class ConversationSelectorController {
    private final ConversationSelectionService selections;
    public record PreviewRequest(SelectorDefinition definition,Integer previewSize) { }
    @PostMapping("/preview")
    @ResourcePermission(code="iqc:selector:preview",name="预览会话选择器",type="iqc",description="按授权范围预览条件与抽样选数")
    @RateLimit(sceneCode="iqc-selector-preview",maxCount=20,period=60)
    public ConversationSelectionService.Preview preview(@RequestBody PreviewRequest body) {
        return selections.preview(body.definition(),body.previewSize()==null?10:body.previewSize());
    }
}
