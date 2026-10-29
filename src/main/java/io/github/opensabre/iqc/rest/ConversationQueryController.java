package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.iqc.conversation.ConversationQueryService;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.shared.IqcPage;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

@RestController
@RequestMapping("/api/iqc/conversations")
public class ConversationQueryController {
    private final ConversationQueryService service;

    private final io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive;
    public ConversationQueryController(ConversationQueryService service){this(service,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public ConversationQueryController(ConversationQueryService service,io.github.opensabre.iqc.privacy.SensitiveReadAccessService sensitive){this.service=service;this.sensitive=sensitive;}
    private io.github.opensabre.iqc.privacy.SensitiveReadAccessService.Access access(String... required){
        if(sensitive==null)throw io.github.opensabre.iqc.governance.IqcException.invalidState("敏感读取授权组件不可用");
        var result=sensitive.current(java.util.Set.of(required));
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet&&servlet.getResponse()!=null){
            servlet.getResponse().setHeader("Cache-Control","private, no-store");servlet.getResponse().setHeader("X-Content-Type-Options","nosniff");}
        return result;
    }

    @GetMapping
    @ResourcePermission(code = "iqc:conversation:view", name = "查看会话", type = "iqc", description = "查询会话列表")
    @RateLimit(sceneCode = "iqc-conversation-query", maxCount = 60, period = 60)
    public IqcPage<Conversation> list(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "20") long size,
                                      @RequestParam(required = false) String employeeId,
                                      @RequestParam(required = false) String customerExternalId,
                                      @RequestParam(required = false) String channel,
                                      @RequestParam(required = false) String businessNo,
                                      @RequestParam(required = false) String fileName,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startedFrom,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startedTo) {
        access("iqc:conversation:view");
        var page=service.page(current,size,employeeId,customerExternalId,channel,businessNo,fileName,startedFrom,startedTo);
        return new IqcPage<>(page.records().stream().map(row->io.github.opensabre.iqc.privacy.ConversationReadProjection.summary(row,false)).toList(),page.current(),page.size(),page.total());
    }

    @GetMapping("/{id}")
    @ResourcePermission(code = "iqc:conversation:detail:view", name = "查看会话详情", type = "iqc", description = "查看会话详情")
    public Map<String, Object> detail(@PathVariable String id) {
        var grant=access("iqc:conversation:detail:view");return io.github.opensabre.iqc.privacy.ConversationReadProjection.detail(service.detail(id),grant,io.github.opensabre.iqc.privacy.ConversationReadProjection.Mode.PREVIEW);
    }
    @GetMapping("/{id}/full-content")
    @ResourcePermission(code=io.github.opensabre.iqc.privacy.SensitiveReadAccessService.FULL_CONVERSATION,name="查看完整会话内容",type="iqc",description="在原数据范围内读取完整且PII脱敏的会话")
    @io.github.opensabre.governance.audit.annotations.Audit(operationType=io.github.opensabre.governance.audit.annotations.OperationType.QUERY,description="读取完整会话内容",module="IQC_PRIVACY",request=false,response=false,key="#id")
    public Map<String,Object> fullContent(@PathVariable String id){
        var grant=access("iqc:conversation:view",io.github.opensabre.iqc.privacy.SensitiveReadAccessService.FULL_CONVERSATION);
        grant.requireFullConversation();
        return io.github.opensabre.iqc.privacy.ConversationReadProjection.detail(service.detail(id),grant,io.github.opensabre.iqc.privacy.ConversationReadProjection.Mode.FULL);
    }
    @GetMapping("/{id}/pii-content")
    @ResourcePermission(code=io.github.opensabre.iqc.privacy.SensitiveReadAccessService.PII,name="查看会话客户PII",type="iqc",description="按独立PII授权读取会话；无完整会话权限时仍只返回预览")
    @io.github.opensabre.governance.audit.annotations.Audit(operationType=io.github.opensabre.governance.audit.annotations.OperationType.QUERY,description="读取会话客户PII",module="IQC_PRIVACY",request=false,response=false,key="#id")
    public Map<String,Object> piiContent(@PathVariable String id){
        var grant=access("iqc:conversation:view",io.github.opensabre.iqc.privacy.SensitiveReadAccessService.PII);
        grant.requirePii();
        return io.github.opensabre.iqc.privacy.ConversationReadProjection.detail(service.detail(id),grant,io.github.opensabre.iqc.privacy.ConversationReadProjection.Mode.PII);
    }

    @GetMapping("/api-stats")
    @ResourcePermission(code = "iqc:conversation:api-stats:view", name = "查看接口会话统计", type = "iqc", description = "按时段统计 API 接入会话")
    public Map<String, Object> apiStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        access("iqc:conversation:api-stats:view");
        return service.apiStats(start, end);
    }
}
