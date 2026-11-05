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
    private final io.github.opensabre.iqc.mcp.dao.IqcMcpServerMapper mapper;
    private final io.github.opensabre.iqc.result.llm.SnapshotMcpToolProvider tools;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public IqcMcpServerController(IqcMcpServerService service, io.github.opensabre.iqc.mcp.dao.IqcMcpServerMapper mapper,
            io.github.opensabre.iqc.result.llm.SnapshotMcpToolProvider tools, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.service = service; this.mapper=mapper; this.tools=tools; this.json=json;
    }
    @PostMapping("/{id}/discover-tools")
    @ResourcePermission(code="iqc:mcp:manage", name="管理 MCP", type="iqc", description="测试 MCP 连接并发现工具")
    public List<io.github.opensabre.iqc.result.llm.SnapshotMcpToolProvider.DiscoveredTool> discover(@PathVariable String id) {
        var server=mapper.selectById(id);
        if (server == null) throw io.github.opensabre.iqc.governance.IqcException.notFound("MCP 不存在");
        try { return tools.discover(json.valueToTree(server)); }
        catch (RuntimeException error) { throw io.github.opensabre.iqc.governance.IqcException.invalidState("MCP 连接或工具发现失败，请检查地址、认证引用与超时配置"); }
    }
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
