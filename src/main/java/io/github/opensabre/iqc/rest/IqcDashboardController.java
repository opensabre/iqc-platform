package io.github.opensabre.iqc.rest;

import io.github.opensabre.boot.annotations.ResourcePermission;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.opensabre.governance.ratelimit.annotations.RateLimit;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.result.dao.InspectionResultMapper;
import io.github.opensabre.iqc.result.model.InspectionResult;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/iqc/dashboard")
public class IqcDashboardController {
    private final ConversationMapper conversationMapper;
    private final InspectionTaskMapper taskMapper;
    private final InspectionResultMapper resultMapper;
    private final IqcDataScope dataScope;

    public IqcDashboardController(ConversationMapper conversationMapper, InspectionTaskMapper taskMapper, InspectionResultMapper resultMapper, IqcDataScope dataScope) {
        this.conversationMapper = conversationMapper; this.taskMapper = taskMapper; this.resultMapper = resultMapper; this.dataScope = dataScope;
    }

    @GetMapping
    @ResourcePermission(code = "iqc:dashboard:view", name = "查看质检总览", type = "iqc", description = "查看质检总览")
    @RateLimit(sceneCode = "iqc-dashboard-query", maxCount = 30, period = 60)
    public Map<String, Object> stats(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to) {
        var conversationQuery = Wrappers.<io.github.opensabre.iqc.conversation.model.Conversation>lambdaQuery();
        var taskQuery = Wrappers.<InspectionTask>lambdaQuery();
        if (!dataScope.canViewAll()) {
            String groupId = dataScope.groupId();
            conversationQuery.and(q -> q.eq(io.github.opensabre.iqc.conversation.model.Conversation::getCreatedBy, dataScope.owner())
                    .or(groupId != null, nested -> nested.eq(io.github.opensabre.iqc.conversation.model.Conversation::getOwnerGroupId, groupId)));
            taskQuery.and(q -> q.eq(InspectionTask::getCreatedBy, dataScope.owner())
                    .or(groupId != null, nested -> nested.eq(InspectionTask::getOwnerGroupId, groupId)));
        }
        long conversationCount = conversationMapper.selectCount(conversationQuery);
        long taskCount = taskMapper.selectCount(taskQuery);
        long runningTaskCount = taskMapper.selectCount(taskQuery.clone().in(InspectionTask::getStatus, "QUEUED", "RUNNING"));
        var visibleTasks = taskMapper.selectList(taskQuery);
        var visibleTaskIds = visibleTasks.stream().map(InspectionTask::getId).toList();
        var taskById = visibleTasks.stream().collect(Collectors.toMap(InspectionTask::getId, Function.identity(), (left, right) -> left));
        var resultQuery = Wrappers.<InspectionResult>lambdaQuery()
                .select(InspectionResult::getScore, InspectionResult::getResultStatus, InspectionResult::getRiskLevel, InspectionResult::getCreatedTime)
                .in(InspectionResult::getTaskId, visibleTaskIds);
        if (from != null) resultQuery.ge(InspectionResult::getCreatedTime, java.util.Date.from(from.atZone(ZoneId.systemDefault()).toInstant()));
        if (to != null) resultQuery.lt(InspectionResult::getCreatedTime, java.util.Date.from(to.plusSeconds(1).atZone(ZoneId.systemDefault()).toInstant()));
        var results = visibleTaskIds.isEmpty() ? java.util.List.<InspectionResult>of() : resultMapper.selectList(resultQuery);
        long hitCount = results.stream().filter(item -> "HIT".equals(item.getResultStatus())).count();
        long highRiskCount = results.stream().filter(item -> "HIGH".equalsIgnoreCase(item.getRiskLevel())).count();
        long unqualifiedCount = results.stream().filter(item -> (item.getScore() != null && item.getScore() < 60) || "ERROR".equals(item.getResultStatus()) || "PARTIAL_ERROR".equals(item.getResultStatus())).count();
        BigDecimal unqualifiedRate = results.isEmpty() ? BigDecimal.ZERO : BigDecimal.valueOf(unqualifiedCount * 100.0 / results.size()).setScale(1, RoundingMode.HALF_UP);
        BigDecimal averageScore = results.isEmpty() ? BigDecimal.ZERO : BigDecimal.valueOf(results.stream().mapToInt(item -> item.getScore() == null ? 0 : item.getScore()).average().orElse(0)).setScale(1, RoundingMode.HALF_UP);
        var grouped = new TreeMap<LocalDate, ArrayList<InspectionResult>>();
        results.forEach(item -> { if (item.getCreatedTime() != null) grouped.computeIfAbsent(item.getCreatedTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDate(), ignored -> new ArrayList<>()).add(item); });
        var trend = grouped.entrySet().stream().map(entry -> {
            var dayResults = entry.getValue();
            long dayHitCount = dayResults.stream().filter(item -> "HIT".equals(item.getResultStatus())).count();
            long dayHighRiskCount = dayResults.stream().filter(item -> "HIGH".equalsIgnoreCase(item.getRiskLevel())).count();
            long dayUnqualifiedCount = dayResults.stream().filter(item -> (item.getScore() != null && item.getScore() < 60) || "ERROR".equals(item.getResultStatus()) || "PARTIAL_ERROR".equals(item.getResultStatus())).count();
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", entry.getKey().toString());
            day.put("resultCount", dayResults.size());
            day.put("hitCount", dayHitCount);
            day.put("highRiskCount", dayHighRiskCount);
            day.put("unqualifiedCount", dayUnqualifiedCount);
            day.put("averageScore", BigDecimal.valueOf(dayResults.stream().mapToInt(item -> item.getScore() == null ? 0 : item.getScore()).average().orElse(0)).setScale(1, RoundingMode.HALF_UP));
            return day;
        }).toList();
        var topAgents = ranking(results, taskById, InspectionTask::getAgentId);
        var topOwners = ranking(results, taskById, InspectionTask::getCreatedBy);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversationCount", conversationCount);
        response.put("taskCount", taskCount);
        response.put("runningTaskCount", runningTaskCount);
        response.put("resultCount", results.size());
        response.put("hitCount", hitCount);
        response.put("highRiskCount", highRiskCount);
        response.put("unqualifiedCount", unqualifiedCount);
        response.put("unqualifiedRate", unqualifiedRate);
        response.put("averageScore", averageScore);
        response.put("trend", trend);
        response.put("topAgents", topAgents);
        response.put("topOwners", topOwners);
        return response;
    }

    private List<Map<String, Object>> ranking(List<InspectionResult> results, Map<String, InspectionTask> taskById,
                                               Function<InspectionTask, String> keySelector) {
        Map<String, List<InspectionResult>> grouped = new HashMap<>();
        for (InspectionResult result : results) {
            InspectionTask task = taskById.get(result.getTaskId());
            if (task == null) continue;
            String key = keySelector.apply(task);
            if (key != null && !key.isBlank()) grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result);
        }
        return grouped.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<InspectionResult>>>comparingInt(entry -> entry.getValue().size()).reversed())
                .limit(5)
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", entry.getKey());
                    item.put("resultCount", entry.getValue().size());
                    item.put("hitCount", entry.getValue().stream().filter(result -> "HIT".equals(result.getResultStatus())).count());
                    item.put("highRiskCount", entry.getValue().stream().filter(result -> "HIGH".equalsIgnoreCase(result.getRiskLevel())).count());
                    return item;
                })
                .toList();
    }
}
