package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.iqc.agent.AgentChatSessionService;
import io.github.opensabre.iqc.shared.IqcPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

/** Read-only history management; retention cleanup is intentionally a separate future capability. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/iqc/config/agents/{agentId}/sessions")
@Tag(name = "智能体会话管理")
public class AgentChatSessionController {
    private final AgentChatSessionService service;

    @GetMapping
    @Operation(summary = "分页查询智能体会话历史")
    @ResourcePermission(code="iqc:agent-session:agent-query", name="查询指定智能体会话", type="iqc", description="查询指定智能体会话历史")
    public IqcPage<AgentChatSessionService.Summary> page(@PathVariable String agentId,
            @RequestParam(defaultValue="1") long current, @RequestParam(defaultValue="10") long size,
            @RequestParam(required=false) String title, @RequestParam(required=false) String owner,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime startedFrom,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime startedTo) {
        return service.page(agentId, current, size, title, owner, startedFrom, startedTo);
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "查看智能体会话消息及调用信息")
    @ResourcePermission(code="iqc:agent-session:detail", name="查看智能体会话详情", type="iqc", description="查看智能体会话详情")
    public AgentChatSessionService.Detail detail(@PathVariable String agentId, @PathVariable String sessionId) {
        return service.detail(agentId, sessionId);
    }
}
