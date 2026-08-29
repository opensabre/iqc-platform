package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import io.github.opensabre.governance.audit.annotations.Audit;
import io.github.opensabre.governance.audit.annotations.OperationType;
import io.github.opensabre.iqc.mcp.IqcMcpServerService;
import io.github.opensabre.iqc.mcp.IqcMcpServerService.McpCommand;
import io.github.opensabre.iqc.mcp.model.IqcMcpServer;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Management API for reusable MCP server assets. */
@RestController
@RequestMapping("/api/iqc/agent-assets/mcps")
public class IqcMcpServerController {
    private final IqcMcpServerService service;
    public IqcMcpServerController(IqcMcpServerService service) { this.service = service; }
    @GetMapping @ResourcePermission(code="iqc:mcp:view", name="查看 MCP", type="iqc", description="查询 Agent MCP 资产")
    public List<IqcMcpServer> list() { return service.list(); }
    @PostMapping @ResourcePermission(code="iqc:mcp:manage", name="管理 MCP", type="iqc", description="创建 Agent MCP 资产") @Audit(operationType=OperationType.CREATE, description="创建 IQC MCP", module="IQC_MCP")
    public IqcMcpServer create(@RequestBody McpCommand command) { return service.create(command); }
    @PutMapping("/{id}") @ResourcePermission(code="iqc:mcp:manage", name="管理 MCP", type="iqc", description="修改 Agent MCP 资产") @Audit(operationType=OperationType.UPDATE, description="修改 IQC MCP", module="IQC_MCP")
    public IqcMcpServer update(@PathVariable String id, @RequestBody McpCommand command) { return service.update(id, command); }
    @PostMapping("/{id}/enable") @ResourcePermission(code="iqc:mcp:manage", name="管理 MCP", type="iqc", description="启用 Agent MCP 资产") @Audit(operationType=OperationType.UPDATE, description="启用 IQC MCP", module="IQC_MCP")
    public IqcMcpServer enable(@PathVariable String id) { return service.setEnabled(id, true); }
    @PostMapping("/{id}/disable") @ResourcePermission(code="iqc:mcp:manage", name="管理 MCP", type="iqc", description="停用 Agent MCP 资产") @Audit(operationType=OperationType.UPDATE, description="停用 IQC MCP", module="IQC_MCP")
    public IqcMcpServer disable(@PathVariable String id) { return service.setEnabled(id, false); }
}
