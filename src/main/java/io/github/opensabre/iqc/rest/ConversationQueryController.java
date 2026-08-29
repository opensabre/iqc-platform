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

    public ConversationQueryController(ConversationQueryService service) { this.service = service; }

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
        return service.page(current, size, employeeId, customerExternalId, channel, businessNo, fileName, startedFrom, startedTo);
    }

    @GetMapping("/{id}")
    @ResourcePermission(code = "iqc:conversation:view", name = "查看会话详情", type = "iqc", description = "查看会话详情")
    public Map<String, Object> detail(@PathVariable String id) { return service.detail(id); }

    @GetMapping("/api-stats")
    @ResourcePermission(code = "iqc:conversation:view", name = "查看接口会话统计", type = "iqc", description = "按时段统计 API 接入会话")
    public Map<String, Object> apiStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return service.apiStats(start, end);
    }
}
