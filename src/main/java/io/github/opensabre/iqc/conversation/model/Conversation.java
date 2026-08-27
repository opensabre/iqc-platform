package io.github.opensabre.iqc.conversation.model;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.opensabre.persistence.entity.po.BasePo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("iqc_conversation")
@EqualsAndHashCode(callSuper = true)
public class Conversation extends BasePo {
    private String batchNo;
    private String sourceType;
    private String externalId;
    private String employeeId;
    private String employeeName;
    private String employeeGroupId;
    private String customerExternalId;
    private String customerName;
    private String customerContactMasked;
    private String channel;
    private java.time.LocalDateTime startedTime;
    private java.time.LocalDateTime endedTime;
    private String businessType;
    private String businessNo;
    private String tagsJson;
    private String sourceFileName;
    private String sourceFingerprint;
    private Integer messageCount;
    private Integer errorCount;
    private Integer ignoredBlankLines;
    private String status;
    private String ownerGroupId;
}
