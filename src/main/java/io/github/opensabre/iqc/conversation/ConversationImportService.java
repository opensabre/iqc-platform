package io.github.opensabre.iqc.conversation;

import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.governance.IqcException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ConversationImportService {
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final IqcDataScope dataScope;
    private final ObjectMapper objectMapper;

    @Transactional
    public Conversation persist(String fileName, String fingerprint, ConversationParseResult parsed) {
        return persist(fileName, fingerprint, parsed, null, "FILE", null, null);
    }

    /** Persists one imported conversation with its batch and source metadata. */
    @Transactional
    public Conversation persist(String fileName, String fingerprint, ConversationParseResult parsed,
                                String batchNo, String sourceType, String externalId) {
        return persist(fileName, fingerprint, parsed, batchNo, sourceType, externalId, null);
    }

    /** Persists one conversation together with immutable participant and business metadata snapshots. */
    @Transactional
    public Conversation persist(String fileName, String fingerprint, ConversationParseResult parsed,
                                String batchNo, String sourceType, String externalId, ConversationMetadata metadata) {
        Conversation existing = externalId == null || externalId.isBlank()
                ? conversationMapper.selectOne(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getSourceFingerprint, fingerprint))
                : conversationMapper.selectOne(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getSourceType, sourceType)
                .eq(Conversation::getExternalId, externalId.trim())
                .last("LIMIT 1"));
        if (existing != null) {
            // 指纹去重是全局唯一约束，但重复命中不能绕过会话的数据范围校验。
            if (!dataScope.canView(existing.getCreatedBy(), existing.getOwnerGroupId())) {
                throw IqcException.accessDenied("该文件已导入且当前用户无权访问");
            }
            return existing;
        }

        Conversation conversation = new Conversation();
        conversation.setBatchNo(batchNo);
        conversation.setSourceType(sourceType);
        conversation.setExternalId(externalId);
        applyMetadata(conversation, metadata);
        conversation.setSourceFileName(fileName);
        conversation.setSourceFingerprint(fingerprint);
        conversation.setMessageCount(parsed.messages().size());
        conversation.setErrorCount(parsed.errors().size());
        conversation.setIgnoredBlankLines(parsed.ignoredBlankLines());
        conversation.setStatus(parsed.successful() ? "IMPORTED" : "IMPORTED_WITH_ERRORS");
        conversation.setOwnerGroupId(dataScope.groupId());
        conversationMapper.insert(conversation);

        for (ConversationMessageDraft draft : parsed.messages()) {
            ConversationMessage message = new ConversationMessage();
            message.setConversationId(conversation.getId());
            message.setSequenceNo(draft.sequence());
            message.setSpeakerRole(draft.speakerRole());
            message.setRelativeTime(draft.relativeTime());
            message.setContent(draft.content());
            message.setRawLine(draft.rawLine());
            message.setLineNumber(draft.lineNumber());
            messageMapper.insert(message);
        }
        return conversation;
    }

    private void applyMetadata(Conversation conversation, ConversationMetadata metadata) {
        if (metadata == null) return;
        ConversationMetadata value = metadata.normalized();
        conversation.setEmployeeId(value.employeeId()); conversation.setEmployeeName(value.employeeName());
        conversation.setEmployeeGroupId(value.employeeGroupId()); conversation.setCustomerExternalId(value.customerExternalId());
        conversation.setCustomerName(value.customerName()); conversation.setCustomerContactMasked(value.customerContactMasked());
        conversation.setChannel(value.channel()); conversation.setStartedTime(value.startedTime()); conversation.setEndedTime(value.endedTime());
        conversation.setBusinessType(value.businessType()); conversation.setBusinessNo(value.businessNo());
        try { conversation.setTagsJson(objectMapper.writeValueAsString(value.tags())); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("会话标签序列化失败", exception); }
    }
}
