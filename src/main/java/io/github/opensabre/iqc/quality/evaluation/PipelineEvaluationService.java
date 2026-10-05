package io.github.opensabre.iqc.quality.evaluation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.pipeline.PipelineRuntime;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.pipelineasset.PipelineRelease;
import io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator;
import io.github.opensabre.iqc.quality.BusinessItemReviewEvaluator;
import io.github.opensabre.iqc.quality.dao.EvaluationSampleResultMapper;
import io.github.opensabre.iqc.quality.model.EvaluationSampleResult;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.shared.IqcPage;
import io.github.opensabre.iqc.task.InspectionTaskService;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.dao.TaskExecutionMapper;
import io.github.opensabre.iqc.task.dao.TaskItemMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.task.model.TaskExecution;
import io.github.opensabre.iqc.task.model.TaskItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/** Dataset execution artifacts share the task lease and detectors, but never production result sinks. */
@Service @RequiredArgsConstructor
public class PipelineEvaluationService {
    public static final String TASK_SCHEMA = "iqc-pipeline-evaluation-task-v1";
    private final EvaluationDatasetService datasets;
    private final PipelineAssetService pipelines;
    private final InspectionTaskService taskService;
    private final InspectionTaskMapper tasks;
    private final TaskExecutionMapper executions;
    private final TaskItemMapper items;
    private final EvaluationSampleResultMapper results;
    private final PipelineConversationEvaluator evaluator;
    private final ObjectMapper json;
    private final IqcDataScope scope;

    public static final class Prepared {
        private final DatasetSnapshot dataset;
        private final String datasetHash;
        private final PipelineRelease release;
        private final Map<String,List<ConversationMessage>> messages;
        private Prepared(DatasetSnapshot dataset,String datasetHash,PipelineRelease release,Map<String,List<ConversationMessage>> messages) {
            this.dataset=dataset; this.datasetHash=datasetHash; this.release=release;
            this.messages=Collections.unmodifiableMap(new LinkedHashMap<>(messages));
        }
        public DatasetSnapshot dataset(){return dataset;}
        public String datasetHash(){return datasetHash;}
        public PipelineRelease release(){return release;}
        public Map<String,List<ConversationMessage>> messages(){return messages;}
        public DatasetSnapshot.Member member(String id) {
            return dataset.members().stream().filter(m -> m.gold().conversationId().equals(id)).findFirst()
                    .orElseThrow(() -> IqcException.invalidState("评测会话不在冻结数据集中"));
        }
    }
    public static boolean isEvaluation(JsonNode snapshot) { return snapshot != null && snapshot.has("evaluationSnapshot"); }

    @Transactional
    public InspectionTask create(String datasetId, int datasetVersion, String pipelineId, Integer pipelineVersion,
            Integer draftRevision, String name, Integer concurrency, String requestId) {
        return createBenchmarked(datasetId,datasetVersion,pipelineId,pipelineVersion,draftRevision,name,concurrency,requestId,null);
    }

    /** Internal server-built experiment marker; no public controller accepts raw frozen snapshots. */
    @Transactional
    public InspectionTask createBenchmarked(String datasetId,int datasetVersion,String pipelineId,Integer pipelineVersion,
            Integer draftRevision,String name,Integer concurrency,String requestId,JsonNode benchmark) {
        if ((pipelineVersion == null) == (draftRevision == null)) throw IqcException.invalidArgument("评测必须选择发布版本或精确草稿修订之一");
        var dataset = datasets.get(datasetId);
        if (!"ACTIVE".equals(dataset.getStatus())) throw IqcException.invalidState("数据集已停用");
        var version = datasets.version(datasetId, datasetVersion);
        var frozen = datasets.read(version.getSnapshotJson(), DatasetSnapshot.class);
        var pipeline = pipelineVersion == null ? pipelines.frozenDraft(pipelineId, draftRevision) : pipelines.publishedRelease(pipelineId, pipelineVersion);
        var root = json.createObjectNode(); var marker = json.valueToTree(pipeline);
        ((com.fasterxml.jackson.databind.node.ObjectNode) marker).put("trial", pipelineVersion == null); root.set("pipelineSnapshot", marker);
        root.set("executionStrategy", json.createObjectNode().put("schemaVersion", "iqc-pipeline-task-v1").put("mode", "PIPELINE"));
        var rules = root.putArray("rules"); pipeline.release().pipeline().nodes().forEach(node -> rules.add(node.config().path("ruleSnapshot")));
        root.set("evaluationSnapshot", json.createObjectNode().put("schemaVersion", TASK_SCHEMA).put("datasetId", datasetId)
                .put("versionNo", datasetVersion).put("contentHash", version.getContentHash()).put("sampleCount", frozen.members().size()));
        if (benchmark != null) root.set("benchmarkSnapshot",benchmark.deepCopy());
        validateGold(frozen, pipeline.release());
        return taskService.createEvaluationTask(name, root, frozen.members().stream().map(DatasetSnapshot.Member::gold).toList(),
                concurrency == null ? 1 : concurrency, requestId);
    }

    /** One validation/read per batch; pass the prepared immutable gold to workers rather than reloading every conversation. */
    public Prepared prepare(InspectionTask task, JsonNode snapshot, boolean requireActive) {
        if (!"PIPELINE_EVALUATION".equals(task.getTaskType()) || !isEvaluation(snapshot)
                || !TASK_SCHEMA.equals(snapshot.path("evaluationSnapshot").path("schemaVersion").asText())
                || !PipelineAssetService.isPipeline(snapshot) || snapshot.has("schemeSnapshot")
                || !"iqc-pipeline-task-v1".equals(snapshot.path("executionStrategy").path("schemaVersion").asText())
                || !"PIPELINE".equals(snapshot.path("executionStrategy").path("mode").asText()))
            throw IqcException.invalidState("评测任务类型与快照协议不一致");
        var marker = snapshot.path("evaluationSnapshot");
        var version = datasets.versionForExecution(marker.path("datasetId").asText(), marker.path("versionNo").asInt(),
                marker.path("contentHash").asText(), requireActive);
        var frozen = datasets.read(version.getSnapshotJson(), DatasetSnapshot.class);
        if (marker.path("sampleCount").asInt() != frozen.members().size()) throw IqcException.invalidState("评测样本数与冻结版本不一致");
        var releaseNode = snapshot.path("pipelineSnapshot").path("release");
        if (!PipelineAssetService.hash(releaseNode.toString()).equals(snapshot.path("pipelineSnapshot").path("contentHash").asText()))
            throw IqcException.invalidState("评测流水线摘要不一致");
        PipelineRelease release = requireActive ? pipelines.taskRelease(snapshot) : read(releaseNode.toString(), PipelineRelease.class);
        validateGold(frozen, release);
        var messages = new LinkedHashMap<String, List<ConversationMessage>>();
        for (var member : frozen.members()) messages.put(member.gold().conversationId(), convert(member.gold()));
        JsonNode ids = parse(task.getConversationIdsJson());
        if (!ids.isArray() || !json.valueToTree(new ArrayList<>(messages.keySet())).equals(ids)
                || !Objects.equals(task.getTotalMessages(), messages.values().stream().mapToInt(List::size).sum()))
            throw IqcException.invalidState("评测任务清单或消息数与冻结数据集不一致");
        return new Prepared(frozen, version.getContentHash(), release, messages);
    }
    private List<ConversationMessage> convert(GoldConversation gold) {
        return gold.messages().stream().map(source -> {
            var message = new ConversationMessage(); message.setId(source.id()); message.setConversationId(gold.conversationId());
            message.setSequenceNo(source.sequence()); message.setSpeakerRole(io.github.opensabre.iqc.conversation.ConversationSpeakerRole.canonical(source.role()));
            message.setContent(source.content());
            message.setRelativeTime(source.relativeTime()); return message;
        }).toList();
    }
    private void validateGold(DatasetSnapshot dataset, PipelineRelease release) {
        var definitions = new HashMap<String, io.github.opensabre.iqc.finding.FindingDefinition>();
        for (var node : release.pipeline().nodes()) {
            var definition = read(node.config().path("findingDefinition").toString(), io.github.opensabre.iqc.finding.FindingDefinition.class);
            var old = definitions.putIfAbsent(definition.code(), definition);
            if (old != null && (old.valueType() != definition.valueType() || !old.allowedValues().equals(definition.allowedValues())))
                throw IqcException.invalidState("评测流水线的事实语义类型不一致");
        }
        for (var member : dataset.members()) for (var expected : member.gold().findings()) {
            var definition = definitions.get(expected.code());
            if (definition == null) continue; // Missing capabilities are measured as missing, never silently dropped from gold.
            if (definition.valueType() != expected.valueType() || expected.state() == io.github.opensabre.iqc.finding.Finding.State.KNOWN
                    && expected.valueType() == io.github.opensabre.iqc.finding.Finding.ValueType.ENUM
                    && !definition.allowedValues().contains(expected.value().textValue()))
                throw IqcException.invalidArgument("金标类型或枚举值与目标事实目录不一致：" + expected.code());
        }
    }

    @Transactional
    public void materialize(InspectionTask task, String executionId, JsonNode snapshot, Prepared prepared,
            List<ConversationMessage> messages, PipelineConversationEvaluator.Evaluation evaluated, List<TaskItem> pending,long durationMs) {
        var current = tasks.selectOne(Wrappers.<InspectionTask>lambdaQuery().eq(InspectionTask::getId, task.getId()).last("FOR UPDATE"));
        if (current == null || !"RUNNING".equals(current.getStatus()) || !executionId.equals(current.getCurrentExecutionId())) return;
        if (!Objects.equals(current.getRuleSnapshotJson(), task.getRuleSnapshotJson())
                || !parse(current.getRuleSnapshotJson()).equals(snapshot)) throw IqcException.invalidState("评测任务快照已变化");
        if (messages.isEmpty()) throw IqcException.invalidState("评测输入不能为空");
        if(durationMs<0) throw IqcException.invalidState("评测耗时无效");
        String conversationId = messages.getFirst().getConversationId(); var member = prepared.member(conversationId);
        if (!json.valueToTree(convert(member.gold())).equals(json.valueToTree(messages))
                || !prepared.datasetHash().equals(snapshot.path("evaluationSnapshot").path("contentHash").asText()))
            throw IqcException.invalidState("评测输入与冻结金标不一致");
        validateActual(prepared, member, evaluated,executionId);
        var existing = results.selectOne(Wrappers.<EvaluationSampleResult>lambdaQuery().eq(EvaluationSampleResult::getTaskId, task.getId())
                .eq(EvaluationSampleResult::getExecutionId, executionId).eq(EvaluationSampleResult::getSampleId, member.sampleId()));
        String actual = write(evaluated), digest = PipelineAssetService.hash(actual);
        var result = existing;
        if (existing != null && !digest.equals(existing.getActualHash())) throw IqcException.invalidState("同一评测执行不能覆盖既有样本结果");
        if (result == null) {
            result = new EvaluationSampleResult(); result.setTaskId(task.getId()); result.setExecutionId(executionId);
            result.setDatasetId(prepared.dataset().datasetId()); result.setDatasetVersion(snapshot.path("evaluationSnapshot").path("versionNo").asInt());
            result.setDatasetHash(prepared.datasetHash()); result.setSampleId(member.sampleId()); result.setSampleVersion(member.versionNo());
            result.setGoldHash(member.contentHash()); result.setConversationId(conversationId); result.setActualJson(actual); result.setActualHash(digest);
            result.setDurationMs(durationMs);
            boolean failed = evaluated.run().status() != PipelineRuntime.Status.SUCCESS
                    || evaluated.items().stream().anyMatch(item -> item.status() == InspectionScoring.ItemStatus.ERROR);
            result.setStatus(failed ? "ERROR" : "SUCCESS"); results.insert(result);
        }
        for (var requested : pending) {
            var item = items.selectOne(Wrappers.<TaskItem>lambdaQuery().eq(TaskItem::getId, requested.getId()).last("FOR UPDATE"));
            if (item == null || !task.getId().equals(item.getTaskId()) || !executionId.equals(item.getExecutionId())
                    || !conversationId.equals(item.getConversationId()) || messages.stream().noneMatch(m -> m.getId().equals(item.getMessageId())))
                throw IqcException.invalidState("评测检查点归属无效");
            if ("SUCCEEDED".equals(item.getStatus()) || "CANCELLED".equals(item.getStatus())) continue;
            item.setResultId(result.getId()); item.setStatus("SUCCESS".equals(result.getStatus()) ? "SUCCEEDED" : "FAILED");
            item.setAttemptCount((item.getAttemptCount() == null ? 0 : item.getAttemptCount()) + 1);
            item.setErrorMessage("ERROR".equals(result.getStatus()) ? "评测执行失败，可重试" : null); items.updateById(item);
        }
    }
    private void validateActual(Prepared prepared, DatasetSnapshot.Member member, PipelineConversationEvaluator.Evaluation actual,String executionId) {
        if (actual == null || actual.mode() != PipelineRuntime.Mode.EVALUATION
                || !PipelineAssetService.hash(evaluator.executionInput(convert(member.gold()), member.gold().businessContext()).toString()).equals(actual.inputHash()))
            throw IqcException.invalidState("评测模式或运行输入摘要无效");
        BusinessItemReviewEvaluator.evaluatePipeline("evaluation", prepared.release(), actual, actual.items(), List.of(),
                member.gold().messages().stream().map(GoldConversation.Message::id).collect(Collectors.toSet()));
        if(!io.github.opensabre.iqc.pipelinebridge.PipelineDecisionEvaluator.riskLevel(prepared.release().items(),actual.fusion(),actual.scoring()).equals(actual.riskLevel()))
            throw IqcException.invalidState("评测风险投影与冻结事实不一致");
        Set<String> messageIds=member.gold().messages().stream().map(GoldConversation.Message::id).collect(Collectors.toSet());
        for(var fact:actual.fusion().stream().flatMap(value->value.rawFindings().stream()).toList()) {
            String identity=executionId+":"+member.gold().conversationId()+":"+fact.source().nodeId();
            String nodeFact=PipelineAssetService.hash(json.valueToTree(identity+":"+fact.source().runIndex()).toString());
            String missingFact=PipelineAssetService.hash(json.valueToTree(identity+":missing").toString());
            if(!nodeFact.equals(fact.id()) && !(fact.state()!=io.github.opensabre.iqc.finding.Finding.State.KNOWN && missingFact.equals(fact.id())))
                throw IqcException.invalidState("评测事实不是该执行轮次的输出");
            if(fact.evidence().stream().filter(e->e.type()==io.github.opensabre.iqc.finding.Finding.EvidenceType.CONVERSATION)
                    .anyMatch(e->!messageIds.contains(e.reference()))) throw IqcException.invalidState("评测事实证据越出冻结会话");
        }
    }

    public IqcPage<EvaluationSampleResult> page(String taskId, String executionId, long current, long size) {
        var task = requireTask(taskId); var execution = requireExecution(task, executionId);
        if (current < 1 || size < 1 || size > 100) throw IqcException.invalidArgument("无效评测结果分页");
        var query = Wrappers.<EvaluationSampleResult>lambdaQuery().eq(EvaluationSampleResult::getTaskId, taskId)
                .eq(EvaluationSampleResult::getExecutionId, execution.getId())
                .select(EvaluationSampleResult.class, field -> !"actualJson".equals(field.getProperty())).orderByAsc(EvaluationSampleResult::getSampleId);
        return IqcPage.from(results.selectPage(new Page<>(current, size), query));
    }
    public EvaluationSampleResult result(String taskId, String resultId) {
        var task = requireTask(taskId); var row = results.selectById(resultId);
        if (row == null || !taskId.equals(row.getTaskId())) throw IqcException.notFound("评测样本结果不存在");
        var prepared = prepare(task, parse(task.getRuleSnapshotJson()), false);
        validatedRow(task, prepared, row); return row;
    }
    public record Report(String schemaVersion, String taskId, String executionId, String datasetHash,
                         int expectedSamples, int availableSamples, int successfulSamples, boolean complete,
                         List<String> missingSampleIds, Map<String, String> sourceExecutionIds, FindingEvaluationMetrics.Result findingMetrics,
                         BusinessMetrics businessMetrics,LatencyMetrics latencyMetrics,RegressionMetrics regressionMetrics) { }
    public record RegressionMetrics(String scope,int expectedSamples,int passedSamples,
                                    java.math.BigDecimal passRate,List<String> failedSampleIds) { }
    public record BusinessMetrics(int scoreGold,int comparableScores,java.math.BigDecimal meanAbsoluteScoreError,
                                  java.math.BigDecimal scoreCoverage,int riskGold,int highRiskGold,int highRiskDetected,
                                  java.math.BigDecimal highRiskRecall,int labelGold,int comparableLabels,java.math.BigDecimal labelAccuracy) { }
    public record LatencyMetrics(String scope,int measuredSamples,Long p50Ms,Long p95Ms,Long p99Ms,java.math.BigDecimal executionFailureRate) { }
    public Report report(String taskId, String executionId) {
        var task = requireTask(taskId); var execution = requireExecution(task, executionId);
        var prepared = prepare(task, parse(task.getRuleSnapshotJson()), false);
        var rounds = executions.selectList(Wrappers.<TaskExecution>lambdaQuery().eq(TaskExecution::getTaskId, taskId)
                .lt(TaskExecution::getAttemptNo, execution.getAttemptNo()).orderByAsc(TaskExecution::getAttemptNo));
        rounds.add(execution); // Do not inherit an abandoned concurrent round sharing the same attempt number.
        var bySample = new LinkedHashMap<String, EvaluationSampleResult>();
        for (var round : rounds) for (var row : results.selectList(Wrappers.<EvaluationSampleResult>lambdaQuery()
                .eq(EvaluationSampleResult::getTaskId, taskId).eq(EvaluationSampleResult::getExecutionId, round.getId())))
            bySample.put(row.getSampleId(), row);
        var metricSamples = new ArrayList<FindingEvaluationMetrics.Sample>(); var missing = new ArrayList<String>();
        var sources = new LinkedHashMap<String, String>(); int successful = 0;
        boolean regression = prepared.dataset().type() == DatasetDraft.Type.REGRESSION;
        var regressionFailed = new ArrayList<String>(); int regressionPassed = 0;
        int scoreGold=0,comparableScores=0,riskGold=0,highRiskGold=0,highRiskDetected=0,labelGold=0;
        var absoluteScoreError=java.math.BigDecimal.ZERO; var durations=new ArrayList<Long>();
        for (var member : prepared.dataset().members()) {
            var row = bySample.get(member.sampleId());
            var expected=member.gold().decision();
            if(expected!=null) {
                if(expected.scoreStatus()==GoldDecision.ScoreStatus.FINAL) scoreGold++;
                if(expected.risk()!=null && expected.risk()!=GoldDecision.Risk.UNKNOWN) riskGold++;
                if(expected.risk()==GoldDecision.Risk.HIGH || expected.risk()==GoldDecision.Risk.CRITICAL) highRiskGold++;
                labelGold+=expected.labels().size();
            }
            if (row == null) {
                missing.add(member.sampleId()); metricSamples.add(new FindingEvaluationMetrics.Sample(member.gold(), List.of()));
                if (regression) regressionFailed.add(member.sampleId());
                continue;
            }
            var actual = validatedRow(task, prepared, row); sources.put(member.sampleId(), row.getExecutionId());
            if ("SUCCESS".equals(row.getStatus())) successful++;
            durations.add(row.getDurationMs());
            if(expected!=null) {
                if(expected.scoreStatus()==GoldDecision.ScoreStatus.FINAL && actual.scoring().scoreStatus()==InspectionScoring.ScoreStatus.FINAL) {
                    comparableScores++; absoluteScoreError=absoluteScoreError.add(expected.score().subtract(actual.scoring().finalScore()).abs());
                }
                if((expected.risk()==GoldDecision.Risk.HIGH || expected.risk()==GoldDecision.Risk.CRITICAL)
                        && Set.of("HIGH","CRITICAL").contains(actual.riskLevel())) highRiskDetected++;
            }
            var metricSample = new FindingEvaluationMetrics.Sample(member.gold(), actual.fusion());
            metricSamples.add(metricSample);
            if (regression) {
                boolean matched = "SUCCESS".equals(row.getStatus()) && FindingEvaluationMetrics.matchesGold(metricSample)
                        && matchesBusiness(expected, actual);
                if (matched) regressionPassed++; else regressionFailed.add(member.sampleId());
            }
        }
        boolean terminal = Set.of("SUCCEEDED", "PARTIAL_FAILED", "FAILED").contains(execution.getStatus());
        durations.sort(Long::compareTo);
        var business=new BusinessMetrics(scoreGold,comparableScores,comparableScores==0?null:absoluteScoreError.divide(
                java.math.BigDecimal.valueOf(comparableScores),6,java.math.RoundingMode.HALF_UP),ratio(comparableScores,scoreGold),
                riskGold,highRiskGold,highRiskDetected,ratio(highRiskDetected,highRiskGold),labelGold,0,null);
        var latency=new LatencyMetrics("CONVERSATION_EVALUATOR_WALL_EXCLUDING_QUEUE",durations.size(),percentile(durations,0.5),
                percentile(durations,0.95),percentile(durations,0.99),ratio(sources.size()-successful,sources.size()));
        return new Report("iqc-evaluation-report-v1", taskId, execution.getId(), prepared.datasetHash(), prepared.dataset().members().size(),
                sources.size(), successful, terminal && missing.isEmpty(), List.copyOf(missing), Collections.unmodifiableMap(sources),
                FindingEvaluationMetrics.evaluate(metricSamples),business,latency,
                new RegressionMetrics("EXACT_LABELLED_FINDING_EVIDENCE_SCORE_RISK",regression?prepared.dataset().members().size():0,
                        regressionPassed,regression?ratio(regressionPassed,prepared.dataset().members().size()):null,List.copyOf(regressionFailed)));
    }
    private boolean matchesBusiness(GoldDecision expected, PipelineConversationEvaluator.Evaluation actual) {
        if (expected == null) return true;
        if (!expected.labels().isEmpty()) return false; // No label output exists in this Pipeline contract yet.
        if (expected.risk() != null && !expected.risk().name().equals(actual.riskLevel())) return false;
        if (expected.scoreStatus() != null && !expected.scoreStatus().name().equals(actual.scoring().scoreStatus().name())) return false;
        return expected.score() == null || actual.scoring().finalScore() != null
                && expected.score().compareTo(actual.scoring().finalScore()) == 0;
    }
    private java.math.BigDecimal ratio(int n,int d){return d==0?null:java.math.BigDecimal.valueOf(n).divide(java.math.BigDecimal.valueOf(d),6,java.math.RoundingMode.HALF_UP);}
    private Long percentile(List<Long> values,double fraction){return values.isEmpty()?null:values.get((int)Math.ceil(values.size()*fraction)-1);}
    private PipelineConversationEvaluator.Evaluation validatedRow(InspectionTask task, Prepared prepared, EvaluationSampleResult row) {
        var member = prepared.member(row.getConversationId()); requireExecution(task, row.getExecutionId());
        var actual = read(row.getActualJson(), PipelineConversationEvaluator.Evaluation.class);
        validateActual(prepared, member, actual,row.getExecutionId());
        boolean failed=actual.run().status()!=PipelineRuntime.Status.SUCCESS
                || actual.items().stream().anyMatch(item->item.status()==InspectionScoring.ItemStatus.ERROR);
        var marker=parse(task.getRuleSnapshotJson()).path("evaluationSnapshot");
        if(row.getDurationMs()==null || row.getDurationMs()<0) throw IqcException.invalidState("评测耗时无效");
        if (!task.getId().equals(row.getTaskId()) || !prepared.dataset().datasetId().equals(row.getDatasetId())
                || !Objects.equals(row.getDatasetVersion(),marker.path("versionNo").asInt())
                || !(failed?"ERROR":"SUCCESS").equals(row.getStatus())
                || !member.sampleId().equals(row.getSampleId()) || !Objects.equals(member.versionNo(),row.getSampleVersion())
                || !member.contentHash().equals(row.getGoldHash()) || !prepared.datasetHash().equals(row.getDatasetHash())
                || !PipelineAssetService.hash(row.getActualJson()).equals(row.getActualHash())) throw IqcException.invalidState("评测结果来源或摘要无效");
        return actual;
    }
    private InspectionTask requireTask(String id) {
        var task = tasks.selectById(id);
        if (task == null || !"PIPELINE_EVALUATION".equals(task.getTaskType())) throw IqcException.notFound("评测任务不存在");
        if (!scope.canView(task.getCreatedBy(), task.getOwnerGroupId())) throw IqcException.accessDenied("无权查看评测任务");
        return task;
    }
    public record Context(InspectionTask task,JsonNode snapshot,Prepared prepared) { }
    public Context context(String id) {
        var task=requireTask(id); var snapshot=parse(task.getRuleSnapshotJson());
        return new Context(task,snapshot,prepare(task,snapshot,false));
    }
    private TaskExecution requireExecution(InspectionTask task, String id) {
        String selected = id == null || id.isBlank() ? task.getCurrentExecutionId() : id;
        var execution = selected == null ? null : executions.selectById(selected);
        if (execution == null || !task.getId().equals(execution.getTaskId())) throw IqcException.notFound("评测执行轮次不存在");
        return execution;
    }
    private String write(Object value) {
        try {
            String body = json.writeValueAsString(value);
            if (body.getBytes(StandardCharsets.UTF_8).length > 16 * 1024 * 1024) throw IqcException.invalidState("评测样本产物超过16MiB");
            return body;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("评测产物序列化失败"); }
    }
    private JsonNode parse(String value) { return read(value, JsonNode.class); }
    private <T> T read(String value, Class<T> type) {
        if(value==null || value.getBytes(StandardCharsets.UTF_8).length>16*1024*1024)
            throw IqcException.invalidState("评测单个快照或轨迹为空或超过16MiB");
        try { var result = json.readValue(value, type); if (result == null) throw IqcException.invalidState("评测快照为空"); return result; }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw IqcException.invalidState("评测快照格式无效"); }
    }
}
