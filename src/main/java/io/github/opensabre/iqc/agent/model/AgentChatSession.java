package io.github.opensabre.iqc.agent.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

/** Bounded conversation history; never stores model credentials or internal reasoning. */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("iqc_agent_chat_session")
public class AgentChatSession extends BasePo {
    private String agentId;
    private Integer versionNo;
    private String title;
    private String source;
    private String owner;
    private String ownerGroupId;
    private String configHash;
    private Integer messageCount;
    private String messagesJson;
    private LocalDateTime lastMessageTime;
}
