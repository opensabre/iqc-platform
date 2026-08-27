package io.github.opensabre.iqc.mcp;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.governance.IqcMcpAuthType;
import io.github.opensabre.iqc.governance.IqcMcpTransport;
import io.github.opensabre.iqc.mcp.dao.IqcMcpServerMapper;
import io.github.opensabre.iqc.mcp.model.IqcMcpServer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/** Manages reusable MCP endpoints without accepting credential material. */
@Service
public class IqcMcpServerService {
    private final IqcMcpServerMapper mapper; private final ObjectMapper objectMapper;
    public IqcMcpServerService(IqcMcpServerMapper mapper, ObjectMapper objectMapper) { this.mapper = mapper; this.objectMapper = objectMapper; }
    public List<IqcMcpServer> list() { return mapper.selectList(Wrappers.<IqcMcpServer>lambdaQuery().orderByAsc(IqcMcpServer::getStatus).orderByDesc(IqcMcpServer::getCreatedTime)); }

    /** Creates an enabled MCP endpoint with an UNKNOWN health state until explicitly tested. */
    @Transactional
    public IqcMcpServer create(McpCommand command) {
        validate(command);
        if (findByCode(command.code()) != null) throw IqcException.invalidState("MCP 编码已存在: " + command.code());
        IqcMcpServer server = new IqcMcpServer(); apply(server, command);
        server.setStatus("ENABLED"); server.setHealthStatus("UNKNOWN"); server.setVersionNo(1); mapper.insert(server); return server;
    }
    /** Updates connection metadata and resets health because the endpoint configuration changed. */
    @Transactional
    public IqcMcpServer update(String id, McpCommand command) {
        IqcMcpServer server = require(id); command = command.withCode(server.getCode()); validate(command); apply(server, command);
        server.setHealthStatus("UNKNOWN"); server.setVersionNo(server.getVersionNo() + 1); mapper.updateById(server); return server;
    }
    @Transactional
    public IqcMcpServer setEnabled(String id, boolean enabled) { IqcMcpServer server = require(id); server.setStatus(enabled ? "ENABLED" : "DISABLED"); mapper.updateById(server); return server; }

    private void validate(McpCommand value) {
        if (blank(value.name()) || blank(value.code()) || blank(value.transport()) || blank(value.endpoint()) || blank(value.authType())) throw IqcException.invalidArgument("MCP 名称、编码、传输类型、地址和认证方式不能为空");
        if (!value.code().matches("[A-Z][A-Z0-9_]{1,63}")) throw IqcException.invalidArgument("MCP 编码必须为大写字母、数字或下划线");
        if (Arrays.stream(IqcMcpTransport.values()).noneMatch(item -> item.value().equals(value.transport()))) throw IqcException.invalidArgument("不支持的 MCP 传输类型");
        if (Arrays.stream(IqcMcpAuthType.values()).noneMatch(item -> item.value().equals(value.authType()))) throw IqcException.invalidArgument("不支持的 MCP 认证方式");
        if (!"NONE".equals(value.authType()) && blank(value.secretRef())) throw IqcException.invalidArgument("启用认证时必须配置密钥引用");
        if (value.secretRef() != null && value.secretRef().length() > 255) throw IqcException.invalidArgument("密钥引用不能超过 255 字符");
        try { URI uri = URI.create(value.endpoint()); if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null) throw new IllegalArgumentException(); }
        catch (Exception exception) { throw IqcException.invalidArgument("MCP 地址必须是有效的 HTTP(S) URL"); }
        if (value.timeoutSeconds() == null || value.timeoutSeconds() < 1 || value.timeoutSeconds() > 300) throw IqcException.invalidArgument("MCP 超时时间必须在 1 到 300 秒之间");
        if (!blank(value.allowedToolsJson())) try { if (!objectMapper.readTree(value.allowedToolsJson()).isArray()) throw IqcException.invalidArgument("工具白名单必须是 JSON 数组"); }
        catch (IqcException exception) { throw exception; } catch (Exception exception) { throw IqcException.invalidArgument("工具白名单不是有效 JSON", exception); }
    }
    private IqcMcpServer findByCode(String code) { return mapper.selectOne(Wrappers.<IqcMcpServer>lambdaQuery().eq(IqcMcpServer::getCode, code)); }
    private IqcMcpServer require(String id) { IqcMcpServer server = mapper.selectById(id); if (server == null) throw IqcException.notFound("MCP 不存在: " + id); return server; }
    private static void apply(IqcMcpServer target, McpCommand source) { target.setName(source.name().trim()); target.setCode(source.code().trim()); target.setDescription(source.description()); target.setTransport(source.transport()); target.setEndpoint(source.endpoint().trim()); target.setAuthType(source.authType()); target.setSecretRef("NONE".equals(source.authType()) ? null : source.secretRef()); target.setTimeoutSeconds(source.timeoutSeconds()); target.setAllowedToolsJson(source.allowedToolsJson()); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record McpCommand(String name, String code, String description, String transport, String endpoint,
                             String authType, String secretRef, Integer timeoutSeconds, String allowedToolsJson) {
        McpCommand withCode(String stableCode) { return new McpCommand(name, stableCode, description, transport, endpoint, authType, secretRef, timeoutSeconds, allowedToolsJson); }
    }
}
