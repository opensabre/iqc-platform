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

/** Cross-agent read-only queries reuse the session store and its existing data-scope checks. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/iqc/config/agent-sessions")
@Tag(name = "智能体会话管理")
public class AgentChatSessionQueryController {
    private final AgentChatSessionService service;

    @GetMapping
    @Operation(summary = "跨智能体分页查询会话历史")
    @ResourcePermission(code="iqc:agent-session:query", name="查询智能体会话", type="iqc", description="跨智能体查询会话历史")
    public IqcPage<AgentChatSessionService.Summary> page(@RequestParam(required=false) String agentId,
            @RequestParam(defaultValue="1") long current, @RequestParam(defaultValue="10") long size,
            @RequestParam(required=false) String title, @RequestParam(required=false) String owner,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime startedFrom,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime startedTo) {
        return service.page(agentId, current, size, title, owner, startedFrom, startedTo);
    }
}
