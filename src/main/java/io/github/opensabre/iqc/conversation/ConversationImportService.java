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
        return persistInternal(fileName,fingerprint,parsed,batchNo,sourceType,externalId,metadata,false);
    }

    /** External identity replay must carry the same immutable text and metadata fingerprint. */
    @Transactional(noRollbackFor=ConversationReplayConflictException.class)
    public Conversation persistVerified(String fileName,String fingerprint,ConversationParseResult parsed,
            String batchNo,String sourceType,String externalId,ConversationMetadata metadata) {
        return persistInternal(fileName,fingerprint,parsed,batchNo,sourceType,externalId,metadata,true);
    }
    private Conversation persistInternal(String fileName,String fingerprint,ConversationParseResult parsed,
            String batchNo,String sourceType,String externalId,ConversationMetadata metadata,boolean verifyFingerprint) {
        if (metadata != null) metadata = metadata.normalized();
        Conversation existing = externalId == null || externalId.isBlank()
                ? conversationMapper.selectOne(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getSourceFingerprint, fingerprint))
                : conversationMapper.selectOne(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getSourceType, sourceType)
                .eq(Conversation::getExternalId, externalId.trim())
                .last("LIMIT 1"));
        if(verifyFingerprint && externalId!=null && !externalId.isBlank()
                && conversationMapper.selectCount(Wrappers.<Conversation>lambdaQuery().eq(Conversation::getSourceType,sourceType)
                    .eq(Conversation::getExternalId,externalId.trim()))>1)
            throw new ConversationReplayConflictException("重复外部输入身份不唯一，请使用明确的新修订身份");
        if (existing != null) {
            // 指纹去重是全局唯一约束，但重复命中不能绕过会话的数据范围校验。
            if (!dataScope.canView(existing.getCreatedBy(), existing.getOwnerGroupId())) {
                throw IqcException.accessDenied("该文件已导入且当前用户无权访问");
            }
            if(verifyFingerprint)verifyReplay(existing,fingerprint,parsed,metadata);
            if (metadata != null && (metadata.asrStatus() != null || metadata.customerLevel() != null
                    || metadata.complaint() != null || metadata.businessFields() != null || metadata.employeeDepartmentId() != null)) {
                String fieldsJson = metadata.businessFields() == null ? null : serializeFields(metadata.businessFields());
                if (!java.util.Objects.equals(existing.getEmployeeDepartmentId(), metadata.employeeDepartmentId())
                        || !java.util.Objects.equals(existing.getAsrStatus(), metadata.asrStatus())
                        || !java.util.Objects.equals(existing.getCustomerLevel(), metadata.customerLevel())
                        || !java.util.Objects.equals(existing.getComplaint(), metadata.complaint())
                        || !java.util.Objects.equals(existing.getBusinessFieldsJson(), fieldsJson)) {
                    throw IqcException.invalidArgument("重复会话的新选择器元数据与已导入快照不一致");
                }
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
            message.setSpeakerRole(ConversationSpeakerRole.canonical(draft.speakerRole()));
            message.setRelativeTime(draft.relativeTime());
            message.setContent(draft.content());
            message.setRawLine(draft.rawLine());
            message.setLineNumber(draft.lineNumber());
            messageMapper.insert(message);
        }
        return conversation;
    }

    private void verifyReplay(Conversation existing,String fingerprint,ConversationParseResult parsed,ConversationMetadata metadata) {
        if(!java.util.Objects.equals(fingerprint,existing.getSourceFingerprint()))
            throw new ConversationReplayConflictException("相同外部会话身份对应不同正文或元数据，请提交明确的新输入修订身份");
        var stored=messageMapper.selectList(Wrappers.<ConversationMessage>lambdaQuery().eq(ConversationMessage::getConversationId,existing.getId())
                .orderByAsc(ConversationMessage::getSequenceNo));
        if(stored.size()!=parsed.messages().size())throw new ConversationReplayConflictException("重复外部身份的消息清单不一致");
        for(int i=0;i<stored.size();i++) {
            var row=stored.get(i);var expected=parsed.messages().get(i);
            if(!java.util.Objects.equals(row.getSequenceNo(),expected.sequence())
                    || !java.util.Objects.equals(ConversationSpeakerRole.canonical(row.getSpeakerRole()),ConversationSpeakerRole.canonical(expected.speakerRole()))
                    || !java.util.Objects.equals(row.getRelativeTime(),expected.relativeTime()) || !java.util.Objects.equals(row.getContent(),expected.content()))
                throw new ConversationReplayConflictException("重复外部身份的实际正文与请求不一致");
        }
        var expected=new Conversation();applyMetadata(expected,metadata);
        var actualTree=objectMapper.valueToTree(existing);var expectedTree=objectMapper.valueToTree(expected);
        for(String field:java.util.List.of("employeeId","employeeName","employeeGroupId","employeeDepartmentId","customerExternalId",
                "customerName","customerContactMasked","channel","startedTime","endedTime","businessType","businessNo",
                "tagsJson","asrStatus","customerLevel","complaint","businessFieldsJson"))
            if(!java.util.Objects.equals(actualTree.path(field),expectedTree.path(field)))
                throw new ConversationReplayConflictException("重复外部身份的分析元数据与请求不一致");
    }

    String serializeFields(java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> fields) {
        try {
            String json = objectMapper.writeValueAsString(fields);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
                throw IqcException.invalidArgument("businessFields 序列化最长 65536 字节");
            return json;
        } catch (JsonProcessingException exception) {
            throw IqcException.invalidArgument("businessFields 无法序列化");
        }
    }

    private void applyMetadata(Conversation conversation, ConversationMetadata metadata) {
        if (metadata == null) return;
        ConversationMetadata value = metadata.normalized();
        conversation.setEmployeeId(value.employeeId()); conversation.setEmployeeName(value.employeeName());
        conversation.setEmployeeGroupId(value.employeeGroupId()); conversation.setEmployeeDepartmentId(value.employeeDepartmentId()); conversation.setCustomerExternalId(value.customerExternalId());
        conversation.setCustomerName(value.customerName()); conversation.setCustomerContactMasked(value.customerContactMasked());
        conversation.setChannel(value.channel()); conversation.setStartedTime(value.startedTime()); conversation.setEndedTime(value.endedTime());
        conversation.setAsrStatus(value.asrStatus()); conversation.setCustomerLevel(value.customerLevel());
        conversation.setComplaint(value.complaint());
        conversation.setBusinessFieldsJson(value.businessFields() == null ? null : serializeFields(value.businessFields()));
        conversation.setBusinessType(value.businessType()); conversation.setBusinessNo(value.businessNo());
        try { conversation.setTagsJson(objectMapper.writeValueAsString(value.tags())); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("会话标签序列化失败", exception); }
    }
}
