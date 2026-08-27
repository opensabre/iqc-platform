package io.github.opensabre.iqc.conversation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** IQC 一期 txt 会话上传约束；网关和前端限制之外，后端仍必须独立校验。 */
@Data
@ConfigurationProperties(prefix = "iqc.conversation")
public class ConversationUploadProperties {
    private long maxFileSizeBytes = 20L * 1024 * 1024;
}
