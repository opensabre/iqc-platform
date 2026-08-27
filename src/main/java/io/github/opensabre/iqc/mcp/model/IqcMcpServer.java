package io.github.opensabre.iqc.mcp.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Safe-to-display MCP server configuration; credentials are referenced, never stored inline. */
@Data
@TableName("iqc_mcp_server")
@EqualsAndHashCode(callSuper = true)
public class IqcMcpServer extends BasePo {
    private String name; private String code; private String description;
    private String transport; private String endpoint; private String authType; private String secretRef;
    private Integer timeoutSeconds; private String allowedToolsJson;
    private String status; private String healthStatus; private Integer versionNo;
}
