package io.github.opensabre.iqc.quality;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.dao.ResultReviewMapper;
import io.github.opensabre.iqc.quality.model.ResultReview;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.scheme.InspectionSchemeService;
import io.github.opensabre.iqc.scheme.SchemeResultEvaluator;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Versioned business reviews in the existing review store; never updates machine results or legacy scores. */
@Service
@RequiredArgsConstructor
public class BusinessItemReviewService {
    private final ResultReviewMapper reviews;
    private final ConversationInspectionResultMapper results;
    private final InspectionTaskMapper tasks;
    private final ConversationMessageMapper messages;
    private final IqcDataScope scope;
    private final ObjectMapper mapper;

    /** Lists cross-task rounds using SQL-level task scope and bounded database pagination. */
    @Transactional(readOnly = true)
    public io.github.opensabre.iqc.shared.IqcPage<io.github.opensabre.iqc.quality.model.BusinessReviewQueueItem> queue(
            String status, String taskId, int current, int size) {
        String selected = status == null ? "PENDING" : status;
        if (!Set.of("PENDING", "COMPLETED", "REJECTED", "ALL").contains(selected)) throw IqcException.invalidArgument("业务复核状态无效");
        if (current < 1 || current > 100_000 || size < 1 || size > 100) throw IqcException.invalidArgument("复核分页范围无效，每页最多 100 条");
        if (taskId != null && (taskId.isBlank() || taskId.length() > 64)) throw IqcException.invalidArgument("任务筛选无效");
        boolean all = scope.canViewAll();
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<io.github.opensabre.iqc.quality.model.BusinessReviewQueueItem>(current, size);
        return io.github.opensabre.iqc.shared.IqcPage.from(reviews.selectBusinessQueue(page,
                "ALL".equals(selected) ? null : selected, taskId, all, scope.owner(), all ? null : scope.groupId()));
    }

    /** Looks up the immutable first request without opening a round or requiring the current EID. */
    @Transactional(readOnly = true)
    public ResultReview lookupRequest(String resultId, int expectedRevision, String requestId, String reason) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}") || expectedRevision < 0
                || expectedRevision == Integer.MAX_VALUE)
            throw IqcException.invalidArgument("复核请求标识或预期修订无效");
        String owner = actor();
        var source = results.selectById(resultId);
        var task = source == null ? null : tasks.selectById(source.getTaskId());
        if (task == null || !owner.equals(task.getCreatedBy()) || !scope.canView(task.getCreatedBy(), task.getOwnerGroupId()))
            throw IqcException.accessDenied("无权核对业务复核来源");
        String fingerprint = hash(List.of(resultId, expectedRevision, reason(reason)));
        var existing = reviews.selectById("br-" + hash(List.of(owner, requestId)).substring(0, 60));
        if (existing != null && (!"BUSINESS".equals(existing.getTargetType())
                || !resultId.equals(existing.getBusinessResultId()) || !owner.equals(existing.getCreatedBy())
                || !Objects.equals(expectedRevision + 1, existing.getReviewRevision())
                || !fingerprint.equals(existing.getRequestFingerprint())))
            throw IqcException.invalidState("复核持久产物与原请求不一致");
        return existing;
    }

    /** Historical evidence for the automatic escalation token; never opens a new round. */
    @Transactional(readOnly = true)
    public ResultReview lookupEscalation(String resultId, String requestId, String reason) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}"))
            throw IqcException.invalidArgument("复核升级请求标识无效");
        String owner = actor();
        var source = results.selectById(resultId);
        var task = source == null ? null : tasks.selectById(source.getTaskId());
        if (task == null || !owner.equals(task.getCreatedBy()) || !scope.canView(task.getCreatedBy(), task.getOwnerGroupId()))
            throw IqcException.accessDenied("无权核对业务复核来源");
        var existing = reviews.selectById("br-" + hash(List.of(owner, requestId)).substring(0, 60));
        if (existing == null) return null;
        if (existing.getReviewRevision() == null || existing.getReviewRevision() < 1)
            throw IqcException.invalidState("复核升级产物轮次损坏");
        return lookupRequest(resultId, existing.getReviewRevision() - 1, requestId, reason);
    }

    /** Internal owning operation. Null means an existing pending round must finish first. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResultReview requestEscalation(String resultId, String requestId, String reason) {
        scope.requireScheduledResources(Set.of("iqc:review:create"));
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}"))
            throw IqcException.invalidArgument("复核升级请求标识无效");
        String comment = reason(reason);
        Context context = context(resultId); // Original Task lock serializes all review round openings.
        var existing = lookupEscalation(resultId, requestId, comment);
        if (existing != null) {
            scope.requireScheduledResources(Set.of("iqc:review:create"));
            return existing;
        }
        requireCurrent(context);
        var latest = latest(resultId, false);
        if (latest != null && (!"BUSINESS".equals(latest.getTargetType())
                || !resultId.equals(latest.getBusinessResultId()) || latest.getReviewRevision() == null
                || latest.getReviewRevision() < 1
                || !Set.of("PENDING", "COMPLETED", "REJECTED").contains(Objects.toString(latest.getStatus(), ""))))
            throw IqcException.invalidState("复核升级最新轮次损坏");
        if (latest != null && "PENDING".equals(latest.getStatus())) return null;
        int revision = latest == null ? 0 : latest.getReviewRevision();
        if (revision == Integer.MAX_VALUE) throw IqcException.invalidState("复核升级轮次已耗尽");
        scope.requireScheduledResources(Set.of("iqc:review:create"));
        var opened = request(resultId, revision, requestId, comment);
        scope.requireScheduledResources(Set.of("iqc:review:create"));
        return opened;
    }

    /** Opens the next review round; caller supplies the last observed revision and a stable retry token. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResultReview request(String resultId, int expectedRevision, String requestId, String reason) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{16,64}") || expectedRevision < 0)
            throw IqcException.invalidArgument("复核请求标识或预期修订无效");
        String comment = reason(reason);
        Context context = context(resultId);
        String id = "br-" + hash(List.of(actor(), requestId)).substring(0, 60);
        String fingerprint = hash(List.of(resultId, expectedRevision, comment));
        ResultReview existing = reviews.selectById(id);
        if (existing != null) {
            if (!"BUSINESS".equals(existing.getTargetType()) || !fingerprint.equals(existing.getRequestFingerprint()))
                throw IqcException.invalidArgument("同一复核请求标识不能用于不同内容");
            return existing;
        }
        requireCurrent(context);
        ResultReview latest = latest(resultId, false);
        int revision = latest == null ? 0 : latest.getReviewRevision();
        if (revision != expectedRevision) throw IqcException.invalidState("复核版本已变化，请刷新");
        if (latest != null && "PENDING".equals(latest.getStatus())) throw IqcException.invalidState("已有待处理复核");
        ResultReview review = new ResultReview();
        review.setId(id); review.setTargetType("BUSINESS"); review.setBusinessResultId(resultId);
        review.setReviewRevision(revision + 1); review.setSourceHash(sourceHash(context));
        review.setRequestFingerprint(fingerprint); review.setStatus("PENDING");
        review.setOriginalStatus(context.result().getResultStatus()); review.setReviewComment(comment);
        review.setRequestComment(comment);
        review.setOwnerGroupId(context.task().getOwnerGroupId()); review.setCreatedBy(actor());
        reviews.insert(review);
        return review;
    }

    /** Completes a pending round once; identical retries return its preserved decision, not another update. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResultReview decide(String reviewId, int expectedRevision, boolean rejected,
                               List<BusinessItemReviewEvaluator.Decision> decisions, String reason) {
        return decide(reviewId,expectedRevision,rejected,decisions,List.of(),reason);
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResultReview decide(String reviewId,int expectedRevision,boolean rejected,
            List<BusinessItemReviewEvaluator.Decision> decisions,List<HumanFindingReviewEvaluator.Decision> findingDecisions,String reason) {
        findingDecisions=findingDecisions==null?List.of():findingDecisions;
        if(findingDecisions.stream().anyMatch(java.util.Objects::isNull))throw IqcException.invalidArgument("事实裁决不能为空");
        ResultReview initial = reviews.selectById(reviewId);
        if (initial == null || !"BUSINESS".equals(initial.getTargetType())) throw IqcException.notFound("业务复核不存在");
        Context context = context(initial.getBusinessResultId());
        ResultReview review = reviews.selectById(reviewId); // Re-read after taking the shared task lock.
        String comment = reason(reason);
        String reviewer = actor();
        if (decisions == null || decisions.size() > 200 || findingDecisions.size()>1000
                || rejected && (!decisions.isEmpty()||!findingDecisions.isEmpty()) || !rejected && decisions.isEmpty()&&findingDecisions.isEmpty())
            throw IqcException.invalidArgument("退回复核不得包含裁决，完成复核必须包含项目或事实裁决");
        String fingerprint = findingDecisions.isEmpty()?hash(List.of(expectedRevision,rejected,decisions,comment,reviewer))
                :hash(List.of(expectedRevision,rejected,decisions,comment,reviewer,findingDecisions));
        if (!Objects.equals(review.getReviewRevision(), expectedRevision)) throw IqcException.invalidState("复核版本已变化");
        if (!"PENDING".equals(review.getStatus())) {
            if (fingerprint.equals(review.getDecisionFingerprint())) return review;
            throw IqcException.invalidState("该轮复核已结束，不能覆盖历史裁决");
        }
        requireCurrent(context);
        if (!review.getSourceHash().equals(sourceHash(context))) throw IqcException.invalidState("原始结果已变化，请重新发起复核");
        if (!reviewId.equals(latest(review.getBusinessResultId(), false).getId())) throw IqcException.invalidState("复核版本已变化");
        if (!rejected) {
            // Each round stores the full effective overlay, while leaving all earlier round records untouched.
            var effective = new LinkedHashMap<String, BusinessItemReviewEvaluator.Decision>();
            var effectiveFindings=new LinkedHashMap<String,HumanFindingReviewEvaluator.Decision>();
            ResultReview previous = latest(review.getBusinessResultId(), true);
            if (previous != null) {
                if (!review.getSourceHash().equals(previous.getSourceHash())) throw IqcException.invalidState("历史复核基准不一致");
                var projection = read(previous.getReviewedResultJson(), BusinessItemReviewEvaluator.Projection.class);
                projection.decisions().forEach(item -> effective.put(item.itemCode(), item));
                if(projection.findingReview()!=null)projection.findingReview().findings().decisions().forEach(item->effectiveFindings.put(item.findingCode(),item));
            }
            Set<String> submitted = new java.util.HashSet<>();
            for (var decision : decisions) {
                if (decision == null || !submitted.add(decision.itemCode())) throw IqcException.invalidArgument("复核项目不能为空或重复");
                effective.put(decision.itemCode(), decision);
            }
            var findingCodes=new java.util.HashSet<String>();
            for(var decision:findingDecisions){
                if(decision==null||!findingCodes.add(decision.findingCode()))throw IqcException.invalidArgument("同一事实不能重复复核");
                effectiveFindings.put(decision.findingCode(),decision);
            }
            try {
                var original = mapper.readValue(context.result().getBusinessItemResultsJson(), new TypeReference<List<SchemeResultEvaluator.ItemResult>>() { });
                Set<String> allowed = messages.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                        .eq(ConversationMessage::getConversationId, context.result().getConversationId())).stream()
                        .map(ConversationMessage::getId).collect(Collectors.toSet());
                var projection = projection(context.task(), context.result(), original,
                        List.copyOf(effective.values()),List.copyOf(effectiveFindings.values()),allowed);
                review.setReviewedResultJson(mapper.writeValueAsString(projection));
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw IqcException.invalidState("复核基准或结果快照损坏");
            }
        }
        review.setStatus(rejected ? "REJECTED" : "COMPLETED"); review.setDecisionFingerprint(fingerprint);
        review.setReviewerId(reviewer); review.setReviewedTime(LocalDateTime.now()); review.setReviewComment(comment);
        reviews.updateById(review);
        return review;
    }

    /** Returns all rounds for an authorized canonical result, including superseded executions for audit. */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public List<ResultReview> history(String resultId) {
        ConversationInspectionResult result = results.selectById(resultId);
        if (result == null) throw IqcException.notFound("会话质检结果不存在");
        authorize(tasks.selectById(result.getTaskId()));
        return reviews.selectList(Wrappers.<ResultReview>lambdaQuery().eq(ResultReview::getTargetType, "BUSINESS")
                .eq(ResultReview::getBusinessResultId, resultId).orderByDesc(ResultReview::getReviewRevision));
    }

    /** Current-result batch export with an explicit coverage manifest; all reads share one database snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] exportTaskZip(String taskId) {
        if (taskId == null || taskId.isBlank() || taskId.length() > 64) throw IqcException.invalidArgument("必须指定有效的任务 ID");
        InspectionTask task = tasks.selectById(taskId);
        authorize(task);
        if (!Set.of("SUCCEEDED", "PARTIAL_FAILED", "FAILED", "CANCELLED").contains(task.getStatus()))
            throw IqcException.invalidState("任务结束后才能批量导出复核结果");
        final int configuredItems;
        try {
            var snapshot = mapper.readTree(task.getRuleSnapshotJson());
            var definition = SchemeResultEvaluator.definition(snapshot, mapper);
            if (snapshot.has("pipelineSnapshot")) configuredItems = pipelineRelease(snapshot).items().size();
            else if (definition != null) configuredItems = definition.items().size();
            else throw IqcException.invalidArgument("批量复核导出仅支持业务会话任务");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("任务快照损坏"); }
        var ids = io.github.opensabre.iqc.result.BatchResultQueryService.exportConversationIds(task, mapper);
        if (ids.size() > 500 || (long) ids.size() * configuredItems > 50_000)
            throw IqcException.invalidArgument("批量复核导出最多 500 个会话、50000 个项目，请缩小任务范围");
        StringBuilder manifest = new StringBuilder("\uFEFF任务ID,会话ID,当前结果ID,执行实例,覆盖状态,采用复核ID,采用轮次,最新复核状态,最新轮次,存在更新待处理轮次,明细文件\n");
        StringBuilder detail = new StringBuilder();
        long detailBytes = 0;
        for (String id : ids) {
            var canonical = results.selectLatestForTaskConversation(taskId, id);
            if (canonical == null) {
                appendCsv(manifest, taskId, id, "", "", "NO_RESULT", "", "", "", "", false, "");
                continue;
            }
            var completed = latest(canonical.getId(), true);
            var last = latest(canonical.getId(), false);
            if (last != null && (!"BUSINESS".equals(last.getTargetType()) || !canonical.getId().equals(last.getBusinessResultId())
                    || last.getReviewRevision() == null || last.getReviewRevision() < 1
                    || !Set.of("PENDING", "COMPLETED", "REJECTED").contains(Objects.toString(last.getStatus(), ""))))
                throw IqcException.invalidState("最新复核轮次损坏");
            if (completed != null) {
                if (!"BUSINESS".equals(completed.getTargetType()) || !canonical.getId().equals(completed.getBusinessResultId())
                        || last == null || last.getReviewRevision() == null || completed.getReviewRevision() == null
                        || last.getReviewRevision() < completed.getReviewRevision())
                    throw IqcException.invalidState("复核轮次或来源不一致");
                // Reuse single-round validation/serialization after authorizing this task once.
                String csv = renderReviewCsv(completed, canonical, task);
                String rows = detail.isEmpty() ? csv : csv.substring(csv.indexOf('\n') + 1);
                detailBytes += rows.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (detailBytes > 50L * 1024 * 1024) throw IqcException.invalidArgument("复核导出明细超过 50 MiB，请缩小任务范围");
                detail.append(rows);
            }
            appendCsv(manifest, taskId, id, canonical.getId(), canonical.getExecutionId(),
                    completed == null ? "NO_COMPLETED_REVIEW" : "COMPLETED_REVIEW",
                    completed == null ? "" : completed.getId(), completed == null ? "" : completed.getReviewRevision(),
                    last == null ? "NONE" : last.getStatus(), last == null ? "" : last.getReviewRevision(),
                    last != null && "PENDING".equals(last.getStatus()) && (completed == null || last.getReviewRevision() > completed.getReviewRevision()),
                    completed == null ? "" : "reviewed-items.csv");
        }
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var zip = new java.util.zip.ZipOutputStream(bytes, java.nio.charset.StandardCharsets.UTF_8)) {
                writeZipEntry(zip, "coverage.csv", manifest.toString());
                if (!detail.isEmpty()) writeZipEntry(zip, "reviewed-items.csv", detail.toString());
            }
            return bytes.toByteArray();
        } catch (java.io.IOException exception) { throw IqcException.invalidState("无法生成复核导出文件"); }
    }

    private static void writeZipEntry(java.util.zip.ZipOutputStream zip, String name, String content) throws java.io.IOException {
        // Filenames are fixed, never derived from user-controlled task/conversation names.
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Exports exactly one completed round, including superseded executions; never substitutes a newer round. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String exportCsv(String reviewId) {
        if (reviewId == null || reviewId.isBlank() || reviewId.length() > 64)
            throw IqcException.invalidArgument("必须指定有效的复核轮次 ID");
        ResultReview review = reviews.selectById(reviewId);
        if (review == null || !"BUSINESS".equals(review.getTargetType())) throw IqcException.notFound("业务复核不存在");
        ConversationInspectionResult result = results.selectById(review.getBusinessResultId());
        if (result == null) throw IqcException.notFound("原始会话结果不存在");
        InspectionTask task = tasks.selectById(result.getTaskId());
        authorize(task);
        return renderReviewCsv(review, result, task);
    }

    /** Domain-internal validation shared by exports/reports; caller must authorize the task and hold a read transaction. */
    BusinessItemReviewEvaluator.Projection validatedProjection(ResultReview review, ConversationInspectionResult result, InspectionTask task) {
        if (!"BUSINESS".equals(review.getTargetType()) || !result.getId().equals(review.getBusinessResultId()))
            throw IqcException.invalidState("复核结果来源不一致");
        if (!"COMPLETED".equals(review.getStatus())) throw IqcException.invalidState("只能导出已完成的复核轮次");
        if (review.getReviewRevision() == null || review.getReviewRevision() < 1
                || review.getReviewerId() == null || review.getReviewedTime() == null
                || !Objects.equals(review.getSourceHash(), sourceHash(new Context(task, result))))
            throw IqcException.invalidState("复核基准或裁决信息已变化，无法导出");
        try {
            var snapshot = mapper.readTree(task.getRuleSnapshotJson());
            var original = mapper.readValue(result.getBusinessItemResultsJson(), new TypeReference<List<SchemeResultEvaluator.ItemResult>>() { });
            var stored = read(review.getReviewedResultJson(), BusinessItemReviewEvaluator.Projection.class);
            if (stored == null || !result.getId().equals(stored.sourceResultId())) throw IqcException.invalidState("复核结果来源不一致");
            if (stored.decisions() == null || stored.decisions().isEmpty()&&stored.findingReview()==null)
                throw IqcException.invalidState("已完成复核缺少项目裁决");
            Set<String> allowed = messages.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                    .eq(ConversationMessage::getConversationId, result.getConversationId())).stream()
                    .map(ConversationMessage::getId).collect(Collectors.toSet());
            // Revalidate the saved overlay against the frozen policy; reject corruption instead of repairing it on export.
            var checked = projection(task,result,original,stored.decisions(),
                    stored.findingReview()==null?List.of():stored.findingReview().findings().decisions(),allowed);
            if (!mapper.valueToTree(checked).equals(mapper.valueToTree(stored)))
                throw IqcException.invalidState("复核项目或评分快照不一致，无法导出");
            return checked;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw IqcException.invalidState("复核基准或结果快照损坏");
        }
    }

    /** Validates persisted machine scoring instead of trusting arbitrary stored totals in business statistics. */
    io.github.opensabre.iqc.scoring.InspectionScoring.Result validatedMachine(ConversationInspectionResult result, InspectionTask task) {
        return validatedMachineEvaluation(result, task).scoring();
    }

    /** Returns the same validated items and score together; reports must not parse unchecked item JSON separately. */
    SchemeResultEvaluator.Evaluation validatedMachineEvaluation(ConversationInspectionResult result, InspectionTask task) {
        try {
            var original = mapper.readValue(result.getBusinessItemResultsJson(), new TypeReference<List<SchemeResultEvaluator.ItemResult>>() { });
            var allowed = messages.selectList(Wrappers.<ConversationMessage>lambdaQuery()
                    .select(ConversationMessage::getId).eq(ConversationMessage::getConversationId, result.getConversationId()))
                    .stream().map(ConversationMessage::getId).collect(Collectors.toSet());
            var evaluation = projection(task, result, original, List.of(), allowed).original();
            var score = evaluation.scoring();
            var stored = read(result.getScoringResultJson(), io.github.opensabre.iqc.scoring.InspectionScoring.Result.class);
            if (!mapper.valueToTree(score).equals(mapper.valueToTree(stored)) || !score.scoreStatus().name().equals(result.getScoreStatus())
                    || (score.finalScore() == null ? result.getFinalScore() != null
                    : result.getFinalScore() == null || score.finalScore().compareTo(result.getFinalScore()) != 0))
                throw IqcException.invalidState("机器评分快照不一致");
            return evaluation;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("机器结果快照损坏"); }
    }

    private String renderReviewCsv(ResultReview review, ConversationInspectionResult result, InspectionTask task) {
        var checked = validatedProjection(review, result, task);
        try {
            var snapshot = mapper.readTree(task.getRuleSnapshotJson());
            StringBuilder csv = new StringBuilder("\uFEFF任务ID,任务名称,会话ID,原始结果ID,执行实例,复核ID,复核轮次,复核状态,基准摘要,复核人,复核时间,申请原因,本轮裁决原因,评分政策,评分模式,机器评分状态,机器会话分数（勿按行求和）,人工评分状态,人工会话分数（勿按行求和）,质检项编码,质检项名称,规则ID,规则版本,机器结论,本轮有效结论,结论来源,有效裁决原因（可能沿用前轮）,机器证据消息ID,有效证据消息ID\n");
            csv.setLength(csv.length() - 1);
            csv.append(",方案ID,发布版本,试跑修订,方案内容摘要,方案类型,机器计分贡献,人工计分贡献,机器一票否决,人工一票否决,流水线ID,流水线发布版本,流水线试跑修订,流水线内容摘要,规则字段含义\n");
            var marker = snapshot.path("schemeSnapshot");
            var pipelineMarker = snapshot.path("pipelineSnapshot");
            var decisions = checked.decisions().stream().collect(Collectors.toMap(BusinessItemReviewEvaluator.Decision::itemCode, item -> item));
            var machineScore = checked.original().scoring();
            var reviewedScore = checked.reviewed().scoring();
            if(machineScore.advanced()!=null || reviewedScore.advanced()!=null){
                int machineColumn=csv.indexOf("机器计分贡献");csv.replace(machineColumn,machineColumn+"机器计分贡献".length(),"机器配置贡献（未加权）");
                int reviewedColumn=csv.indexOf("人工计分贡献");csv.replace(reviewedColumn,reviewedColumn+"人工计分贡献".length(),"人工配置贡献（未加权）");
                csv.setLength(csv.length()-1);csv.append(",机器等级,人工等级,机器复杂评分JSON,人工复杂评分JSON\n");
            }
            boolean hasFeedback=checked.decisions().stream().anyMatch(decision->decision.feedbackCategory()!=null);
            if(hasFeedback){csv.setLength(csv.length()-1);csv.append(",有效裁决反馈分类（可能沿用前轮）\n");}
            boolean hasFindingReview=checked.findingReview()!=null;
            if(hasFindingReview){csv.setLength(csv.length()-1);csv.append(",人工Finding裁决JSON（本轮有效）\n");}
            var machineLines = machineScore.lines().stream().collect(Collectors.toMap(io.github.opensabre.iqc.scoring.InspectionScoring.Line::itemCode, line -> line));
            var reviewedLines = reviewedScore.lines().stream().collect(Collectors.toMap(io.github.opensabre.iqc.scoring.InspectionScoring.Line::itemCode, line -> line));
            for (int index = 0; index < checked.reviewed().items().size(); index++) {
                var item = checked.reviewed().items().get(index);
                var machine = checked.original().items().get(index);
                var decision = decisions.get(item.itemCode());
                var machineLine = machineLines.get(item.itemCode());
                var reviewedLine = reviewedLines.get(item.itemCode());
                Object[] cells=new Object[]{task.getId(), task.getName(), result.getConversationId(), result.getId(), result.getExecutionId(),
                        review.getId(), review.getReviewRevision(), review.getStatus(), review.getSourceHash(), review.getReviewerId(),
                        review.getReviewedTime(), review.getRequestComment(), review.getReviewComment(), machineScore.policyVersion(),
                        machineScore.mode(), machineScore.scoreStatus(), exportScore(machineScore), reviewedScore.scoreStatus(), exportScore(reviewedScore),
                        item.itemCode(), item.name(), item.ruleId(), item.ruleVersionNo(), machine.status(), item.status(),
                        decision == null ? (hasFindingReview?"FINDING_REVIEW_PROJECTED":"MACHINE") : "HUMAN_EFFECTIVE", decision == null ? "" : decision.reason(),
                        mapper.writeValueAsString(machine.matchedMessageIds()), mapper.writeValueAsString(item.matchedMessageIds()),
                        marker.path("schemeId").asText(""), marker.path("versionNo").asText(""), marker.path("draftRevision").asText(""),
                        marker.path("contentHash").asText(""), marker.path("kind").asText(""),
                        machineLine == null ? null : machineLine.contribution(), reviewedLine == null ? null : reviewedLine.contribution(),
                        machineLine == null ? null : machineLine.vetoTriggered(), reviewedLine == null ? null : reviewedLine.vetoTriggered(),
                        pipelineMarker.path("pipelineId").asText(""), pipelineMarker.path("versionNo").asText(""),
                        pipelineMarker.path("draftRevision").asText(""), pipelineMarker.path("contentHash").asText(""),
                        "冻结项目绑定；检测来源见执行轨迹"};
                if(machineScore.advanced()!=null || reviewedScore.advanced()!=null){
                    var extended=new java.util.ArrayList<Object>(java.util.Arrays.asList(cells));
                    extended.add(machineScore.advanced()==null?null:machineScore.advanced().grade());
                    extended.add(reviewedScore.advanced()==null?null:reviewedScore.advanced().grade());
                    extended.add(machineScore.advanced()==null?null:mapper.writeValueAsString(machineScore.advanced()));
                    extended.add(reviewedScore.advanced()==null?null:mapper.writeValueAsString(reviewedScore.advanced()));
                    cells=extended.toArray();
                }
                if(hasFeedback){
                    var extended=new java.util.ArrayList<Object>(java.util.Arrays.asList(cells));
                    extended.add(decision==null?null:decision.feedbackCategory());cells=extended.toArray();
                }
                if(hasFindingReview){
                    var extended=new java.util.ArrayList<Object>(java.util.Arrays.asList(cells));
                    extended.add(mapper.writeValueAsString(checked.findingReview().findings().decisions()));cells=extended.toArray();
                }
                appendCsv(csv,cells);
            }
            return csv.toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw IqcException.invalidState("复核基准或结果快照损坏");
        }
    }

    private static String exportScore(io.github.opensabre.iqc.scoring.InspectionScoring.Result score) {
        return score.scoreStatus() == io.github.opensabre.iqc.scoring.InspectionScoring.ScoreStatus.FINAL
                && score.finalScore() != null ? score.finalScore().toPlainString() : "";
    }

    private static void appendCsv(StringBuilder csv, Object... values) {
        csv.append(java.util.Arrays.stream(values)
                .map(value -> io.github.opensabre.iqc.result.InspectionExecutionService.row(value == null ? null : value.toString()))
                .collect(Collectors.joining(","))).append('\n');
    }

    private Context context(String id) {
        ConversationInspectionResult result = results.selectById(id);
        if (result == null) throw IqcException.notFound("会话质检结果不存在");
        authorize(tasks.selectById(result.getTaskId()));
        InspectionTask task = tasks.selectOne(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getId, result.getTaskId()).last("FOR UPDATE"));
        authorize(task);
        result = results.selectById(id);
        if (result == null || !task.getId().equals(result.getTaskId())) throw IqcException.invalidState("原始结果已变化");
        return new Context(task, result);
    }

    private void authorize(InspectionTask task) {
        if (task == null || !scope.canView(task.getCreatedBy(), task.getOwnerGroupId())) throw IqcException.accessDenied("无权访问该业务复核");
    }

    private void requireCurrent(Context context) {
        if (!Set.of("SUCCEEDED", "PARTIAL_FAILED", "FAILED", "CANCELLED").contains(context.task().getStatus()))
            throw IqcException.invalidState("任务尚未结束，暂不能复核");
        var latest = results.selectLatestForTaskConversation(context.task().getId(), context.result().getConversationId());
        if (latest == null || !context.result().getId().equals(latest.getId())) throw IqcException.invalidState("该会话已有更新的质检结果");
        try {
            var snapshot = mapper.readTree(context.task().getRuleSnapshotJson());
            if (snapshot.has("pipelineSnapshot")) {
                if (pipelineRelease(snapshot).items().isEmpty()) throw IqcException.invalidArgument("该流水线没有可复核的业务质检项");
                validatedMachineEvaluation(context.result(), context.task());
            }
            else if (SchemeResultEvaluator.definition(snapshot, mapper) == null)
                throw IqcException.invalidArgument("旧消息任务请使用原复核流程");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("任务快照损坏"); }
    }

    ResultReview latest(String resultId, boolean completedOnly) {
        return reviews.selectOne(Wrappers.<ResultReview>lambdaQuery().eq(ResultReview::getTargetType, "BUSINESS")
                .eq(ResultReview::getBusinessResultId, resultId).eq(completedOnly, ResultReview::getStatus, "COMPLETED")
                .orderByDesc(ResultReview::getReviewRevision).last("LIMIT 1"));
    }

    private io.github.opensabre.iqc.pipelineasset.PipelineRelease pipelineRelease(com.fasterxml.jackson.databind.JsonNode snapshot)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        var marker = snapshot.path("pipelineSnapshot");
        var release = mapper.treeToValue(marker.path("release"), io.github.opensabre.iqc.pipelineasset.PipelineRelease.class);
        if (release == null || snapshot.has("schemeSnapshot") || !marker.path("contentHash").asText().equals(
                io.github.opensabre.iqc.pipelineasset.PipelineAssetService.hash(mapper.writeValueAsString(release))))
            throw IqcException.invalidState("流水线复核政策快照摘要不一致");
        return release;
    }

    private BusinessItemReviewEvaluator.Projection projection(InspectionTask task, ConversationInspectionResult result,
            List<SchemeResultEvaluator.ItemResult> original, List<BusinessItemReviewEvaluator.Decision> decisions,
            Set<String> allowed) throws com.fasterxml.jackson.core.JsonProcessingException {
        return projection(task,result,original,decisions,List.of(),allowed);
    }
    private BusinessItemReviewEvaluator.Projection projection(InspectionTask task,ConversationInspectionResult result,
            List<SchemeResultEvaluator.ItemResult> original,List<BusinessItemReviewEvaluator.Decision> decisions,
            List<HumanFindingReviewEvaluator.Decision> findingDecisions,Set<String> allowed) throws com.fasterxml.jackson.core.JsonProcessingException {
        var snapshot = mapper.readTree(task.getRuleSnapshotJson());
        if (snapshot.has("pipelineSnapshot")) {
            var release = pipelineRelease(snapshot);
            var trace = findingDecisions.isEmpty()?mapper.readValue(result.getExecutionTraceJson(),io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator.Evaluation.class)
                    :mapper.readerFor(io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator.Evaluation.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .<io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator.Evaluation>readValue(result.getExecutionTraceJson());
            if(!findingDecisions.isEmpty()){
                var input=io.github.opensabre.iqc.conversation.ProductionInputSnapshotService.validatedExecutionInput(task,snapshot,trace,result.getConversationId(),mapper);
                var reviewAllowed=new java.util.HashSet<String>();input.path("messages").forEach(message->reviewAllowed.add(message.path("id").asText()));
                var checked=BusinessItemReviewEvaluator.evaluatePipeline(result.getId(),release,trace,original,decisions,reviewAllowed,input.path("businessContext"));
                var human=HumanFindingReviewEvaluator.evaluatePolicies(result.getId(),release,trace,findingDecisions,decisions,input,mapper);
                var statuses=human.items().stream().collect(Collectors.toMap(io.github.opensabre.iqc.scoring.InspectionScoring.Decision::itemCode,item->item.status()));
                var itemOverrides=checked.decisions().stream().collect(Collectors.toMap(BusinessItemReviewEvaluator.Decision::itemCode,item->item));
                var facts=human.findings().reviewed().stream().collect(Collectors.toMap(io.github.opensabre.iqc.fusion.FindingFusion.Result::code,item->item));
                var mappings=release.items().stream().collect(Collectors.toMap(io.github.opensabre.iqc.pipelineasset.PipelineRelease.ItemMapping::itemCode,item->item));
                var items=original.stream().map(item->{
                    var override=itemOverrides.get(item.itemCode());var fact=facts.get(mappings.get(item.itemCode()).findingCode());
                    var ids=new java.util.LinkedHashSet<String>();
                    for(var evidence:fact.evidence()){
                        if(reviewAllowed.contains(evidence.reference()))ids.add(evidence.reference());
                        var details=evidence.details();if(details!=null){
                            if(reviewAllowed.contains(details.path("messageId").asText()))ids.add(details.path("messageId").asText());
                            if(details.path("messageIds").isArray())details.path("messageIds").forEach(id->{if(id.isTextual()&&reviewAllowed.contains(id.asText()))ids.add(id.asText());});
                        }
                    }
                    return new SchemeResultEvaluator.ItemResult(item.itemCode(),item.name(),item.ruleId(),item.ruleVersionNo(),statuses.get(item.itemCode()),
                            override==null?List.copyOf(ids):override.evidenceMessageIds());
                }).toList();
                return new BusinessItemReviewEvaluator.Projection(result.getId(),checked.original(),
                        new SchemeResultEvaluator.Evaluation(items,human.scoring()),checked.decisions(),human);
            }
            return BusinessItemReviewEvaluator.evaluatePipeline(result.getId(), release, trace, original, decisions, allowed,
                    io.github.opensabre.iqc.conversation.ProductionInputSnapshotService.validatedBusinessContext(task,snapshot,trace,result.getConversationId(),mapper));
        }
        if(!findingDecisions.isEmpty())throw IqcException.invalidArgument("Finding人工修正必须来自冻结流水线结果");
        return BusinessItemReviewEvaluator.evaluate(result.getId(), SchemeResultEvaluator.definition(snapshot, mapper), original, decisions, allowed);
    }

    private String sourceHash(Context context) {
        try {
            if (mapper.readTree(context.task().getRuleSnapshotJson()).has("pipelineSnapshot"))
                return hash(java.util.Arrays.asList(context.task().getRuleSnapshotJson(), context.result().getId(),
                        context.result().getBusinessItemResultsJson(), context.result().getScoringResultJson(), context.result().getExecutionTraceJson()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw IqcException.invalidState("复核政策快照损坏"); }
        return hash(java.util.Arrays.asList(context.task().getRuleSnapshotJson(), context.result().getId(),
                context.result().getBusinessItemResultsJson(), context.result().getScoringResultJson()));
    }
    private String hash(Object value) {
        try { return InspectionSchemeService.contentHash(mapper.writeValueAsString(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidArgument("复核数据无法序列化"); }
    }
    private <T> T read(String json, Class<T> type) {
        try {
            if(type==BusinessItemReviewEvaluator.Projection.class)return mapper.readerFor(type)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(json);
            return mapper.readValue(json,type);
        }
        catch (Exception exception) { throw IqcException.invalidState("历史复核快照损坏"); }
    }
    private String actor() {
        String owner = scope.owner();
        if (owner == null || owner.isBlank()) throw IqcException.accessDenied("复核必须由已登录用户操作");
        return owner;
    }
    private String reason(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 1000) throw IqcException.invalidArgument("复核原因不能为空且不得超过 1000 字");
        return value.trim();
    }
    private record Context(InspectionTask task, ConversationInspectionResult result) { }
}
