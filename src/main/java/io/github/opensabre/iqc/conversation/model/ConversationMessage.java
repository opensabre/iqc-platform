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
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Long startMs;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Long endMs;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private java.math.BigDecimal confidence;
    private String content;
    private String rawLine;
    private Integer lineNumber;
}
