package io.github.opensabre.iqc.task;

import io.github.opensabre.iqc.governance.IqcException;
import java.time.LocalDateTime;
import java.util.List;

/** Data selection is independent of the task's inspection/labeling operations and schedule. */
public record TaskDataSelection(Mode mode, List<String> conversationIds, Filter filter, Integer sampleSize, String sampleSeed) {
    public enum Mode { BATCH, RANDOM, FILTER }

    /** Uses the conversation centre's actual metadata and conversation-time filters. */
    public record Filter(String fileName, String employeeId, String customerExternalId, String channel,
                         String businessNo, LocalDateTime startedFrom, LocalDateTime startedTo) {
        public Filter {
            fileName = clean(fileName); employeeId = clean(employeeId); customerExternalId = clean(customerExternalId);
            channel = clean(channel); businessNo = clean(businessNo);
            if (channel != null) channel = channel.toUpperCase(java.util.Locale.ROOT);
            if (startedFrom != null && startedTo != null && startedFrom.isAfter(startedTo))
                throw IqcException.invalidArgument("会话开始时间不能晚于结束时间");
        }
        private static String clean(String value) {
            if (value == null || value.isBlank()) return null;
            if (value.length() > 255) throw IqcException.invalidArgument("会话筛选字段不能超过 255 个字符");
            return value.trim();
        }
    }

    /** Validates mode-specific fields without silently turning a dynamic filter into a batch. */
    public TaskDataSelection {
        if (mode == null) throw IqcException.invalidArgument("请选择数据筛选方式");
        conversationIds = conversationIds == null ? List.of() : conversationIds;
        if (mode == Mode.BATCH) {
            if (conversationIds.isEmpty() || conversationIds.size() > 1000 || conversationIds.stream().anyMatch(id -> id == null || id.isBlank()))
                throw IqcException.invalidArgument("批量选择须包含 1 到 1000 个有效会话 ID");
            if (filter != null || sampleSize != null || sampleSeed != null)
                throw IqcException.invalidArgument("批量选择不能同时指定筛选或抽样参数");
            conversationIds = List.copyOf(conversationIds);
        } else {
            if (!conversationIds.isEmpty()) throw IqcException.invalidArgument("抽样和条件筛选不能指定固定会话名单");
            if (mode == Mode.RANDOM) {
                if (sampleSize == null || sampleSize < 1 || sampleSize > 1000)
                    throw IqcException.invalidArgument("抽样数量必须为 1 到 1000 的整数");
                if (sampleSeed != null && (sampleSeed.isBlank() || sampleSeed.length() > 100))
                    throw IqcException.invalidArgument("抽样标识不合法，请重新模拟执行");
            } else if (sampleSize != null || sampleSeed != null)
                throw IqcException.invalidArgument("条件筛选不能同时指定抽样参数");
        }
    }
}
