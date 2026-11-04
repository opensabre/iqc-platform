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
        var query = filteredQuery(new io.github.opensabre.iqc.task.TaskDataSelection.Filter(
                fileName, employeeId, customerExternalId, channel, businessNo, startedFrom, startedTo), false)
                .orderByDesc(Conversation::getCreatedTime).orderByDesc(Conversation::getId);
        return IqcPage.from(conversationMapper.selectPage(page, query));
    }

    /** Previews data only: never creates a task, locks a final selection, or calls a detector. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public SelectionPreview preview(io.github.opensabre.iqc.task.TaskDataSelection selection, long current, long size) {
        if (selection == null || selection.mode() == io.github.opensabre.iqc.task.TaskDataSelection.Mode.BATCH)
            throw IqcException.invalidArgument("模拟执行请选择随机抽样或条件筛选");
        if (selection.mode() == io.github.opensabre.iqc.task.TaskDataSelection.Mode.FILTER) {
            Page<Conversation> page = new Page<>(Math.max(1, current), Math.min(Math.max(1, size), 100));
            var query = filteredQuery(selection.filter(), true).orderByAsc(Conversation::getId);
            conversationMapper.selectPage(page, query);
            return new SelectionPreview(selection.mode().name(), page.getTotal(), page.getTotal(), null, IqcPage.from(page));
        }
        String seed = selection.sampleSeed() == null ? java.util.UUID.randomUUID().toString() : selection.sampleSeed();
        // A bounded heap samples the entire authorized range, rather than the first query LIMIT.
        var ranking = java.util.Comparator.<Conversation, String>comparing(row -> sampleKey(seed, row.getId()))
                .thenComparing(Conversation::getId);
        var sample = new java.util.PriorityQueue<Conversation>(selection.sampleSize(), ranking.reversed());
        long total = 0;
        String cursor = null;
        // Resolve permission scope once per scan. All pages share the database's read-only snapshot.
        boolean all = dataScope.canViewAll();
        String owner = dataScope.owner(), group = dataScope.groupId();
        while (true) {
            var query = filteredQuery(selection.filter(), true, all, owner, group)
                    .gt(cursor != null, Conversation::getId, cursor).orderByAsc(Conversation::getId).last("LIMIT 500");
            List<Conversation> rows = conversationMapper.selectList(query);
            for (Conversation row : rows) {
                total++;
                if (sample.size() < selection.sampleSize()) sample.add(row);
                else if (ranking.compare(row, sample.peek()) < 0) { sample.poll(); sample.add(row); }
            }
            if (rows.size() < 500) break;
            String next = rows.getLast().getId();
            if (next.equals(cursor)) throw IqcException.invalidState("会话分页游标未前进");
            cursor = next;
        }
        List<Conversation> records = sample.stream().sorted(ranking).toList();
        // Return the complete (at most 1000) sample once: UI pagination cannot silently resample it.
        return new SelectionPreview("RANDOM", total, records.size(), seed,
                new IqcPage<>(records, 1, selection.sampleSize(), records.size()));
    }

    /** Counts are separate: matching population is not the number of sampled records. */
    public record SelectionPreview(String mode, long matchedTotal, long selectedTotal, String sampleSeed,
                                   IqcPage<Conversation> conversations) { }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Conversation> filteredQuery(
            io.github.opensabre.iqc.task.TaskDataSelection.Filter filter, boolean eligibleOnly) {
        return filteredQuery(filter, eligibleOnly, dataScope.canViewAll(), dataScope.owner(), dataScope.groupId());
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Conversation> filteredQuery(
            io.github.opensabre.iqc.task.TaskDataSelection.Filter filter, boolean eligibleOnly,
            boolean all, String owner, String group) {
        var query = Wrappers.<Conversation>lambdaQuery();
        if (filter != null) query.like(filter.fileName() != null, Conversation::getSourceFileName, filter.fileName())
                .eq(filter.employeeId() != null, Conversation::getEmployeeId, filter.employeeId())
                .eq(filter.customerExternalId() != null, Conversation::getCustomerExternalId, filter.customerExternalId())
                .eq(filter.channel() != null, Conversation::getChannel, filter.channel())
                .eq(filter.businessNo() != null, Conversation::getBusinessNo, filter.businessNo())
                .ge(filter.startedFrom() != null, Conversation::getStartedTime, filter.startedFrom())
                .le(filter.startedTo() != null, Conversation::getStartedTime, filter.startedTo());
        if (eligibleOnly) query.eq(Conversation::getStatus, "IMPORTED").gt(Conversation::getMessageCount, 0);
        if (!all) query.and(q -> q.eq(Conversation::getCreatedBy, owner)
                .or(group != null, nested -> nested.eq(Conversation::getOwnerGroupId, group)));
        return query;
    }

    private String sampleKey(String seed, String id) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((seed + ":" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
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
