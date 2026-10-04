package io.github.opensabre.iqc.label;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper;
import io.github.opensabre.iqc.label.model.InspectionLabelResult;
import io.github.opensabre.iqc.quality.dao.ResultReviewMapper;
import io.github.opensabre.iqc.quality.model.ResultReview;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LabelResultReviewServiceTest {
    private final ResultReviewMapper reviews = mock(ResultReviewMapper.class);
    private final InspectionLabelResultMapper labels = mock(InspectionLabelResultMapper.class);
    private final ConversationInspectionResultMapper conversations = mock(ConversationInspectionResultMapper.class);
    private final InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
    private final ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final LabelResultReviewService service = new LabelResultReviewService(reviews, labels, conversations, tasks, messages, scope, mapper);
    private InspectionTask task;
    private ConversationInspectionResult conversation;
    private InspectionLabelResult label;

    @BeforeEach
    void setup() {
        for (Class<?> type : List.of(InspectionTask.class, ResultReview.class, ConversationInspectionResult.class,
                InspectionLabelResult.class, ConversationMessage.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), type.getSimpleName()), type);
        task = new InspectionTask(); task.setId("task"); task.setStatus("SUCCEEDED"); task.setRuleSnapshotJson("{}");
        task.setLabelScopeSnapshotJson("""
                {"schemaVersion":"2.0","labels":[{"id":"house","versionNo":2,"targetRole":"customer",
                "values":[{"valueCode":"owned","valueType":"BOOLEAN"}]}]}
                """);
        conversation = new ConversationInspectionResult(); conversation.setId("result");
        conversation.setTaskId("task"); conversation.setConversationId("conversation");
        label = new InspectionLabelResult(); label.setId("label"); label.setConversationResultId("result");
        label.setLabelId("house"); label.setLabelVersionNo(2); label.setValueCode("owned");
        label.setValueJson("""
                {"schemaVersion":"iqc-label-result-v2","status":"UNKNOWN","valueCode":"owned",
                "valueType":"BOOLEAN","subjectRole":"customer","candidates":[],"reasons":["NOT_MENTIONED"]}
                """);
        when(labels.selectById("label")).thenReturn(label);
        when(conversations.selectById("result")).thenReturn(conversation);
        when(conversations.selectLatestForTaskConversation("task", "conversation")).thenReturn(conversation);
        when(tasks.selectById("task")).thenReturn(task); when(tasks.selectOne(any())).thenReturn(task);
        when(scope.canView(null, null)).thenReturn(true); when(scope.owner()).thenReturn("reviewer");
        var evidence = new ConversationMessage(); evidence.setId("m1"); evidence.setConversationId("conversation");
        when(messages.selectList(any())).thenReturn(List.of(evidence));
    }

    private ResultReview request() {
        ResultReview review = service.request("label", 0, "request-token-0001", "申请纠错");
        when(reviews.selectById(review.getId())).thenReturn(review);
        when(reviews.selectOne(any())).thenReturn(review);
        return review;
    }

    @Test
    void queueFiltersByTaskScopeBeforePagingAndRejectsInvalidBounds() {
        when(reviews.selectLabelQueue(any(), any(), any(), anyBoolean(), any(), any()))
                .thenAnswer(call -> call.getArgument(0));
        service.queue(null, null, 1, 20);
        verify(reviews).selectLabelQueue(any(), eq("PENDING"), isNull(), eq(false), eq("reviewer"), isNull());
        when(scope.canViewAll()).thenReturn(true);
        service.queue("ALL", "task", 2, 10);
        verify(reviews).selectLabelQueue(any(), isNull(), eq("task"), eq(true), eq("reviewer"), isNull());
        assertThatThrownBy(() -> service.queue("invalid", null, 1, 20)).hasMessageContaining("状态无效");
        assertThatThrownBy(() -> service.queue(null, null, 0, 20)).hasMessageContaining("分页范围");
        assertThatThrownBy(() -> service.queue(null, null, 1, 101)).hasMessageContaining("分页范围");
    }

    @Test
    void queueUsesVersionMatchedFrozenNamesAndFallsBackToCodesWhenSnapshotIsDamaged() {
        var row = new io.github.opensabre.iqc.label.model.LabelReviewQueueItem();
        row.setTaskId("task"); row.setLabelId("house"); row.setLabelVersionNo(2); row.setValueCode("owned");
        var page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<io.github.opensabre.iqc.label.model.LabelReviewQueueItem>(1, 20);
        page.setRecords(List.of(row)); page.setTotal(1);
        when(reviews.selectLabelQueue(any(), any(), any(), anyBoolean(), any(), any())).thenReturn(page);
        when(tasks.selectBatchIds(anyCollection())).thenReturn(List.of(task));
        task.setLabelScopeSnapshotJson(task.getLabelScopeSnapshotJson().replace("\"id\":\"house\"",
                "\"id\":\"house\",\"name\":\"是否有房\"").replace("\"valueType\":\"BOOLEAN\"",
                "\"valueType\":\"BOOLEAN\",\"description\":\"拥有房产\""));

        var named = service.queue("PENDING", null, 1, 20).records().getFirst();
        assertThat(named.getLabelName()).isEqualTo("是否有房");
        assertThat(named.getValueDescription()).isEqualTo("拥有房产");
        verifyNoInteractions(labels);

        row.setLabelName(null); row.setValueDescription(null);
        row.setLabelVersionNo(3);
        assertThat(service.queue("PENDING", null, 1, 20).records().getFirst().getLabelName()).isNull();
        row.setLabelVersionNo(2);
        task.setLabelScopeSnapshotJson("{broken");
        var fallback = service.queue("PENDING", null, 1, 20).records().getFirst();
        assertThat(fallback.getLabelName()).isNull();
        assertThat(fallback.getValueDescription()).isNull();
    }

    @Test
    void currentResultCheckUsesExecutionOrderedCanonicalResult() {
        service.request("label", 0, "request-token-0001", "申请纠错");
        verify(conversations).selectLatestForTaskConversation("task", "conversation");
    }

    @Test
    void falseCorrectionPreservesMachineResultAndDoesNotTouchScores() throws Exception {
        ResultReview review = request();
        var correction = new LabelResultReviewService.Decision("KNOWN", mapper.valueToTree(false), List.of("m1"));
        service.decide(review.getId(), 1, false, correction, "客户明确否认");
        var projected = mapper.readValue(review.getReviewedResultJson(), LabelResultReviewService.Projection.class);
        assertThat(projected.status()).isEqualTo("KNOWN");
        assertThat(projected.value().booleanValue()).isFalse();
        assertThat(projected.evidenceMessageIds()).containsExactly("m1");
        assertThat(label.getValueJson()).contains("UNKNOWN");
        assertThat(review.getBusinessResultId()).isNull(); assertThat(review.getResultId()).isNull();
        assertThat(service.decide(review.getId(), 1, false, correction, "客户明确否认")).isSameAs(review);
        verify(reviews, times(1)).updateById(review);
    }

    @Test
    void rejectsWrongTypeMissingOrCrossConversationEvidenceAndUnknownWithValue() {
        ResultReview review = request();
        assertThatThrownBy(() -> service.decide(review.getId(), 1, false,
                new LabelResultReviewService.Decision("KNOWN", mapper.valueToTree("yes"), List.of("m1")), "原因"))
                .hasMessageContaining("冻结类型");
        assertThatThrownBy(() -> service.decide(review.getId(), 1, false,
                new LabelResultReviewService.Decision("KNOWN", mapper.valueToTree(false), List.of()), "原因"))
                .hasMessageContaining("证据消息");
        assertThatThrownBy(() -> service.decide(review.getId(), 1, false,
                new LabelResultReviewService.Decision("UNKNOWN", mapper.valueToTree(false), List.of()), "原因"))
                .hasMessageContaining("未知标签");
        when(messages.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.decide(review.getId(), 1, false,
                new LabelResultReviewService.Decision("KNOWN", mapper.valueToTree(false), List.of("m1")), "原因"))
                .hasMessageContaining("当前会话");
        verify(reviews, never()).updateById(any(ResultReview.class));
    }

    @Test
    void protectsRevisionSourceSnapshotCurrentResultAndAccess() {
        ResultReview review = request();
        assertThat(service.request("label", 0, "request-token-0001", "申请纠错")).isSameAs(review);
        assertThatThrownBy(() -> service.request("label", 0, "request-token-0001", "不同内容")).hasMessageContaining("不同内容");
        assertThatThrownBy(() -> service.request("label", 1, "request-token-0002", "再申请")).hasMessageContaining("待处理");
        label.setValueJson(label.getValueJson().replace("NOT_MENTIONED", "SUBJECT_UNCERTAIN"));
        assertThatThrownBy(() -> service.decide(review.getId(), 1, true, null, "退回"))
                .hasMessageContaining("原始结果");
        when(conversations.selectLatestForTaskConversation("task", "conversation")).thenReturn(new ConversationInspectionResult());
        assertThatThrownBy(() -> service.request("label", 0, "request-token-0003", "原因"))
                .hasMessageContaining("更新的质检结果");
        when(scope.canView(null, null)).thenReturn(false);
        assertThatThrownBy(() -> service.history("label")).hasMessageContaining("无权");
    }

    @Test
    void rejectsLegacyOrUnfrozenValuesAndSupportsImmutableRounds() {
        task.setLabelScopeSnapshotJson("{\"schemaVersion\":\"1.0\"}");
        assertThatThrownBy(() -> service.request("label", 0, "request-token-0001", "原因"))
                .hasMessageContaining("旧标签");
        task.setLabelScopeSnapshotJson("{\"schemaVersion\":\"2.0\",\"labels\":[]}");
        assertThatThrownBy(() -> service.request("label", 0, "request-token-0001", "原因"))
                .hasMessageContaining("冻结定义");
        task.setLabelScopeSnapshotJson("""
                {"schemaVersion":"2.0","labels":[{"id":"house","versionNo":2,"targetRole":"customer",
                "values":[{"valueCode":"owned","valueType":"BOOLEAN"}]}]}
                """);
        ResultReview first = request();
        service.decide(first.getId(), 1, true, null, "退回");
        assertThat(first.getReviewedResultJson()).isNull();
        ResultReview second = service.request("label", 1, "request-token-0002", "再申请");
        assertThat(second.getReviewRevision()).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo("REJECTED");
    }

    @Test
    void historyRejectsTamperedHumanProjectionInsteadOfPresentingItAsEffective() {
        ResultReview review = request();
        service.decide(review.getId(), 1, false,
                new LabelResultReviewService.Decision("KNOWN", mapper.valueToTree(false), List.of("m1")), "确认否认");
        when(reviews.selectList(any())).thenReturn(List.of(review));
        assertThat(service.history("label")).containsExactly(review);
        review.setReviewedResultJson(review.getReviewedResultJson().replace("\"sourceLabelResultId\":\"label\"",
                "\"sourceLabelResultId\":\"other\""));
        assertThatThrownBy(() -> service.history("label")).hasMessageContaining("修订快照无效");
    }

    @Test
    void batchReadKeepsLatestCompletedFalseWhenANewerRoundIsPending() {
        ResultReview first = request();
        service.decide(first.getId(), 1, false,
                new LabelResultReviewService.Decision("KNOWN", mapper.valueToTree(false), List.of("m1")), "确认否认");
        ResultReview pending = new ResultReview(); pending.setId("next"); pending.setTargetType("LABEL");
        pending.setLabelResultId("label"); pending.setReviewRevision(2); pending.setStatus("PENDING");
        pending.setSourceHash(first.getSourceHash());
        when(reviews.selectList(any())).thenReturn(List.of(pending, first));
        var evidence = new ConversationMessage(); evidence.setId("m1"); evidence.setConversationId("conversation");
        when(messages.selectBatchIds(anyCollection())).thenReturn(List.of(evidence));

        var effective = service.effectiveForTask(task, java.util.Map.of("result", conversation), List.of(label)).get("label");
        assertThat(effective.latestRevision()).isEqualTo(2);
        assertThat(effective.latestStatus()).isEqualTo("PENDING");
        assertThat(effective.effectiveReviewId()).isEqualTo(first.getId());
        assertThat(effective.effectiveStatus()).isEqualTo("KNOWN");
        assertThat(effective.effectiveValue().booleanValue()).isFalse();
    }

    @Test
    void batchReadRejectsCorruptLedgerOrForeignEvidence() {
        ResultReview first = request();
        service.decide(first.getId(), 1, false,
                new LabelResultReviewService.Decision("KNOWN", mapper.valueToTree(false), List.of("m1")), "确认否认");
        when(reviews.selectList(any())).thenReturn(List.of(first));
        String sourceHash = first.getSourceHash();
        first.setSourceHash("tampered");
        assertThatThrownBy(() -> service.effectiveForTask(task, java.util.Map.of("result", conversation), List.of(label)))
                .hasMessageContaining("基准不一致");
        first.setSourceHash(sourceHash);
        var foreign = new ConversationMessage(); foreign.setId("m1"); foreign.setConversationId("foreign");
        when(messages.selectBatchIds(anyCollection())).thenReturn(List.of(foreign));
        assertThatThrownBy(() -> service.effectiveForTask(task, java.util.Map.of("result", conversation), List.of(label)))
                .hasMessageContaining("不属于当前会话");
    }
}
