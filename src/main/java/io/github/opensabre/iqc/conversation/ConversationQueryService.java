package io.github.opensabre.iqc.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.shared.IqcPage;
import io.github.opensabre.iqc.governance.IqcException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ConversationQueryService {
    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final IqcDataScope dataScope;

    public List<Conversation> list() {
        var query = Wrappers.<Conversation>lambdaQuery().orderByDesc(Conversation::getCreatedTime);
        if (!dataScope.canViewAll()) {
            String groupId = dataScope.groupId();
            query.and(q -> q.eq(Conversation::getCreatedBy, dataScope.owner())
                    .or(groupId != null, nested -> nested.eq(Conversation::getOwnerGroupId, groupId)));
        }
        return conversationMapper.selectList(query);
    }

    public IqcPage<Conversation> page(long current, long size) {
        return page(current, size, null, null, null, null, null, null, null);
    }

    /** Queries conversation archives by real participants and upstream business metadata. */
    public IqcPage<Conversation> page(long current, long size, String employeeId, String customerExternalId,
                                      String channel, String businessNo, String fileName,
                                      LocalDateTime startedFrom, LocalDateTime startedTo) {
        Page<Conversation> page = new Page<>(Math.max(1, current), Math.min(Math.max(1, size), 100));
        var query = Wrappers.<Conversation>lambdaQuery().orderByDesc(Conversation::getCreatedTime);
        query.eq(hasText(employeeId), Conversation::getEmployeeId, trim(employeeId))
                .eq(hasText(customerExternalId), Conversation::getCustomerExternalId, trim(customerExternalId))
                .eq(hasText(channel), Conversation::getChannel, normalizeChannel(channel))
                .eq(hasText(businessNo), Conversation::getBusinessNo, trim(businessNo))
                .like(hasText(fileName), Conversation::getSourceFileName, trim(fileName))
                .ge(startedFrom != null, Conversation::getStartedTime, startedFrom)
                .le(startedTo != null, Conversation::getStartedTime, startedTo);
        if (!dataScope.canViewAll()) {
            String groupId = dataScope.groupId();
            query.and(q -> q.eq(Conversation::getCreatedBy, dataScope.owner())
                    .or(groupId != null, nested -> nested.eq(Conversation::getOwnerGroupId, groupId)));
        }
        return IqcPage.from(conversationMapper.selectPage(page, query));
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String trim(String value) { return value == null ? null : value.trim(); }
    private String normalizeChannel(String value) {
        String normalized = trim(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    public Map<String, Object> detail(String id) {
        Conversation conversation = conversationMapper.selectById(id);
        if (conversation == null) throw IqcException.notFound("会话不存在: " + id);
        if (!dataScope.canView(conversation.getCreatedBy(), conversation.getOwnerGroupId())) throw IqcException.accessDenied("无权查看该会话");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("conversation", conversation);
        detail.put("messages", messageMapper.selectList(Wrappers.<io.github.opensabre.iqc.conversation.model.ConversationMessage>lambdaQuery()
                .eq(io.github.opensabre.iqc.conversation.model.ConversationMessage::getConversationId, id)
                .orderByAsc(io.github.opensabre.iqc.conversation.model.ConversationMessage::getSequenceNo)));
        return detail;
    }

    /** Aggregates API-ingested conversations for the selected reporting period. */
    public Map<String, Object> apiStats(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw IqcException.invalidArgument("统计开始时间必须早于结束时间");
        }
        var query = Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getSourceType, "API")
                .ge(Conversation::getCreatedTime, start)
                .lt(Conversation::getCreatedTime, end);
        if (!dataScope.canViewAll()) {
            String groupId = dataScope.groupId();
            query.and(q -> q.eq(Conversation::getCreatedBy, dataScope.owner())
                    .or(groupId != null, nested -> nested.eq(Conversation::getOwnerGroupId, groupId)));
        }
        List<Conversation> conversations = conversationMapper.selectList(query);
        long messages = conversations.stream().map(Conversation::getMessageCount).filter(java.util.Objects::nonNull).mapToLong(Integer::longValue).sum();
        long batches = conversations.stream().map(Conversation::getBatchNo).filter(value -> value != null && !value.isBlank()).distinct().count();
        long external = conversations.stream().map(Conversation::getExternalId).filter(value -> value != null && !value.isBlank()).count();
        return Map.of("start", start, "end", end, "conversationCount", conversations.size(),
                "messageCount", messages, "batchCount", batches, "externalIdCount", external);
    }
}
