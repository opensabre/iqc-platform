package io.github.opensabre.iqc.conversation.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalTime;

@Data
@TableName("iqc_conversation_message")
@EqualsAndHashCode(callSuper = true)
public class ConversationMessage extends BasePo {
    private String conversationId;
    private Integer sequenceNo;
    private String speakerRole;
    private LocalTime relativeTime;
    private String content;
    private String rawLine;
    private Integer lineNumber;
}
