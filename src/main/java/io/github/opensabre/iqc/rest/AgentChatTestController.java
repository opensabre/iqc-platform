package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.iqc.agent.AgentChatTestService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/iqc/config/agents")
public class AgentChatTestController {
    private final AgentChatTestService service;
    public AgentChatTestController(AgentChatTestService service) { this.service=service; }
    // Reuse existing capability-management authorization; no shared-service permission changes.
    @io.github.opensabre.governance.ratelimit.annotations.RateLimit(sceneCode="iqc-agent-test-chat", maxCount=20, period=60)
    @PostMapping("/test-chat")
    @ResourcePermission(code="iqc:agent:manage", name="管理智能体", type="iqc", description="测试当前智能体的大模型能力")
    public AgentChatTestService.Result chat(@RequestBody AgentChatTestService.Request request) { return service.chat(request); }
}
