package io.github.opensabre.iqc.quality;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.quality.dao.QualitySampleMapper;
import io.github.opensabre.iqc.quality.dao.ResultReviewMapper;
import io.github.opensabre.iqc.quality.model.QualitySample;
import io.github.opensabre.iqc.quality.model.ResultReview;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.scheme.SchemeResultEvaluator;
import io.github.opensabre.iqc.scoring.InspectionScoring;
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

class BusinessReviewSampleServiceTest {
    private final QualitySampleMapper samples = mock(QualitySampleMapper.class);
    private final ResultReviewMapper reviews = mock(ResultReviewMapper.class);
    private final ConversationInspectionResultMapper results = mock(ConversationInspectionResultMapper.class);
    private final InspectionTaskMapper tasks = mock(InspectionTaskMapper.class);
    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final ConversationMessageMapper messages = mock(ConversationMessageMapper.class);
    private final BusinessItemReviewService business = mock(BusinessItemReviewService.class);
    private final IqcDataScope scope = mock(IqcDataScope.class);
    private final ObjectMapper json = new ObjectMapper();
    private final BusinessReviewSampleService service = new BusinessReviewSampleService(samples, reviews, results, tasks, conversations, messages, business, scope, json);
    private ConversationInspectionResult result;
    private ResultReview review;
    private InspectionTask task;
    private Conversation conversation;
    private List<ConversationMessage> transcript;

    @BeforeEach
    void setup() throws Exception {
        for (Class<?> type : List.of(InspectionTask.class, ConversationMessage.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), type.getSimpleName()), type);
        task = new InspectionTask(); task.setId("task"); task.setOwnerGroupId("group");
        task.setRuleSnapshotJson("""
                {"schemeSnapshot":{"schemeId":"scheme","versionNo":2,"release":{"definition":{"schemaVersion":"iqc-scheme-v2",
                "items":[{"itemCode":"a","name":"问候","rule":{"id":"r","versionNo":1},"hitMeaning":"VIOLATION"}],
                "scoring":{"version":"iqc-score-v2","mode":"DEDUCTION","baseScore":100,"passingScore":80,
                "items":[{"itemCode":"a","points":30,"veto":false}]}}}}}
                """);
        result = new ConversationInspectionResult(); result.setId("result"); result.setTaskId("task");
        result.setConversationId("conversation"); result.setExecutionId("execution");
        review = new ResultReview(); review.setId("review"); review.setTargetType("BUSINESS"); review.setBusinessResultId("result");
        review.setReviewRevision(2); review.setStatus("COMPLETED"); review.setReviewerId("inspector");
        review.setReviewedTime(java.time.LocalDateTime.of(2026,10,4,1,2)); review.setSourceHash("source-hash");
        conversation = new Conversation(); conversation.setId("conversation"); conversation.setMessageCount(2); conversation.setChannel("TEXT");
        var first = new ConversationMessage(); first.setId("m1"); first.setConversationId("conversation"); first.setSequenceNo(1);
        first.setContent("完整第一句"); first.setSpeakerRole("AGENT");
        var second = new ConversationMessage(); second.setId("m2"); second.setConversationId("conversation"); second.setSequenceNo(2);
        second.setContent("完整第二句"); second.setSpeakerRole("CUSTOMER"); transcript = List.of(first,second); refreshTrace();
        var definition = SchemeResultEvaluator.definition(json.readTree(task.getRuleSnapshotJson()), json);
        var projection = BusinessItemReviewEvaluator.evaluate("result", definition, List.of(new SchemeResultEvaluator.ItemResult(
                "a", "问候", "r", 1, InspectionScoring.ItemStatus.FAIL, List.of("m1"))),
                List.of(new BusinessItemReviewEvaluator.Decision("result","a",InspectionScoring.ItemStatus.FAIL,
                        InspectionScoring.ItemStatus.PASS,"人工确认",List.of("m2"))), java.util.Set.of("m1","m2"));
        when(business.validatedProjection(review,result,task)).thenReturn(projection);
        when(results.selectById("result")).thenReturn(result); when(tasks.selectById("task")).thenReturn(task); when(tasks.selectOne(any())).thenReturn(task);
        when(reviews.selectById("review")).thenReturn(review); when(conversations.selectById("conversation")).thenReturn(conversation);
        when(messages.selectList(any())).thenReturn(transcript); when(scope.owner()).thenReturn("creator"); when(scope.canView(null,"group")).thenReturn(true);
    }
    private void refreshTrace() {
        var input = json.createObjectNode(); input.set("messages",json.valueToTree(transcript.stream().map(message -> {
            var copy = json.convertValue(message, ConversationMessage.class);
            copy.setSpeakerRole(io.github.opensabre.iqc.conversation.ConversationSpeakerRole.canonical(message.getSpeakerRole()));
            return copy;
        }).toList()));
        result.setExecutionTraceJson(json.createObjectNode().put("schemaVersion","iqc-pipeline-execution-v1")
                .put("inputHash",PipelineAssetService.hash(input.toString())).toString());
    }
    private QualitySample create() { return service.create("result","review","sample-request-001",null,"FALSE_POSITIVE","[\"regression\"]"); }

    @Test
    void capturesWholeConversationHumanProjectionAndVersionAsCandidate() throws Exception {
        var sample = create(); var input = json.readTree(sample.getContentSnapshot()); var expected = json.readTree(sample.getExpectedJson());
        assertThat(sample.getStatus()).isEqualTo("CANDIDATE"); assertThat(sample.getMessageId()).isNull();
        assertThat(input.path("segments")).hasSize(2); assertThat(input.at("/segments/1/content").asText()).isEqualTo("完整第二句");
        assertThat(expected.at("/expected/scoring/finalScore").asInt()).isEqualTo(100);
        assertThat(expected.at("/humanDecisions/0/reason").asText()).isEqualTo("人工确认");
        assertThat(expected.path("reviewRevision").asInt()).isEqualTo(2); assertThat(expected.path("reviewerId").asText()).isEqualTo("inspector");
        assertThat(expected.at("/policySource/id").asText()).isEqualTo("scheme"); assertThat(expected.path("executionInputHash").asText()).isNotBlank();
        assertThat(sample.getCreatedBy()).isEqualTo("creator"); assertThat(sample.getOwnerGroupId()).isEqualTo("group");
        verify(samples).insert(sample); verify(results, never()).updateById(any(ConversationInspectionResult.class));
    }
    @Test
    void retryReturnsImmutableFirstCaptureAndRejectsChangedRequest() {
        var sample = create(); when(samples.selectById(sample.getId())).thenReturn(sample);
        transcript.getFirst().setContent("创建后编辑");
        assertThat(create()).isSameAs(sample); verify(samples,times(1)).insert(any(QualitySample.class));
        assertThatThrownBy(() -> service.create("result","review","sample-request-001","different","FALSE_POSITIVE","[\"regression\"]"))
                .hasMessageContaining("不同内容");
    }
    @Test
    void changedOrMissingOriginalExecutionInputCannotBecomeCandidate() {
        transcript.getFirst().setContent("执行后编辑");
        assertThatThrownBy(this::create).hasMessageContaining("原执行输入一致");
        result.setExecutionTraceJson(null); assertThatThrownBy(this::create).hasMessageContaining("原执行输入一致");
        verify(samples, never()).insert(any(QualitySample.class));
    }
    @Test
    void missingMessagesAndForeignReviewAreRejected() {
        conversation.setMessageCount(3); assertThatThrownBy(this::create).hasMessageContaining("不完整");
        conversation.setMessageCount(2); review.setBusinessResultId("other");
        assertThatThrownBy(this::create).hasMessageContaining("该结果"); verify(samples,never()).insert(any(QualitySample.class));
    }
    @Test
    void damagedOrUnfinishedReviewIsValidatedByExistingReviewService() {
        when(business.validatedProjection(review,result,task)).thenThrow(io.github.opensabre.iqc.governance.IqcException.invalidState("只能导出已完成的复核轮次"));
        assertThatThrownBy(this::create).hasMessageContaining("已完成"); verify(samples,never()).insert(any(QualitySample.class));
    }
    @Test
    void preservesConversationsLargerThanOldTextColumnAndRejectsOversizeWithoutTruncating() {
        transcript.getFirst().setContent("字".repeat(30_000)); refreshTrace();
        assertThat(create().getContentSnapshot()).contains("字".repeat(30_000));
        transcript.getFirst().setContent("字".repeat(1_500_000)); refreshTrace();
        assertThatThrownBy(this::create).hasMessageContaining("4MiB"); verify(samples,times(1)).insert(any(QualitySample.class));
    }
    @Test
    void rejectsInvalidTagsAndUnauthorizedSourcesBeforeWriting() {
        assertThatThrownBy(() -> service.create("result","review","sample-request-001",null,"FALSE_POSITIVE","{}"))
                .hasMessageContaining("标签");
        when(scope.canView(null,"group")).thenReturn(false); assertThatThrownBy(this::create).hasMessageContaining("无权");
        verify(samples,never()).insert(any(QualitySample.class));
    }
    @Test void candidateKeepsHumanFindingValuesAndMessageIdsWithoutDetectorPayload() throws Exception {
        var base=business.validatedProjection(review,result,task);
        var details=json.createObjectNode().put("messageId","m2").put("privatePayload","DO_NOT_CAPTURE");
        var evidence=new io.github.opensabre.iqc.finding.Finding.Evidence("evidence",io.github.opensabre.iqc.finding.Finding.EvidenceType.EXTERNAL_RESULT,"external:call",details);
        var raw=new io.github.opensabre.iqc.finding.Finding("raw","CHECK","BUSINESS",io.github.opensabre.iqc.finding.Finding.ValueType.BOOLEAN,
                json.getNodeFactory().booleanNode(false),io.github.opensabre.iqc.finding.Finding.State.KNOWN,null,
                io.github.opensabre.iqc.finding.Finding.Severity.LOW,"DO_NOT_CAPTURE",List.of(evidence),
                new io.github.opensabre.iqc.finding.Finding.Source("node","SMALL_MODEL","small-model-v1",1,"hash"));
        var fact=new io.github.opensabre.iqc.fusion.FindingFusion.Result("CHECK",raw.valueType(),raw.state(),json.getNodeFactory().booleanNode(true),
                raw.severity(),List.of(raw),List.of(),List.of("raw"),List.of(evidence),"human","policy",1);
        var decision=new HumanFindingReviewEvaluator.Decision("result","CHECK","expected-hash",fact.state(),fact.value(),fact.severity(),
                "manual correction",List.of("evidence"),List.of());
        var findings=new HumanFindingReviewEvaluator.Projection(List.of(fact),List.of(fact),List.of(decision));
        var policy=new HumanFindingReviewEvaluator.PolicyProjection(findings,List.of(),base.reviewed().scoring(),null,"LOW",java.util.Map.of(),null,"PASS");
        when(business.validatedProjection(review,result,task)).thenReturn(new BusinessItemReviewEvaluator.Projection("result",base.original(),base.reviewed(),base.decisions(),policy));
        var sample=create();var expected=json.readTree(sample.getExpectedJson());
        assertThat(expected.at("/humanFindingDecisions/0/reason").asText()).isEqualTo("manual correction");
        assertThat(expected.at("/humanReviewedFindings/0/value").booleanValue()).isTrue();
        assertThat(expected.at("/humanReviewedFindings/0/evidenceMessageIds/0").asText()).isEqualTo("m2");
        assertThat(sample.getExpectedJson()).doesNotContain("DO_NOT_CAPTURE","privatePayload","rawFindings");
        assertThat(sample.getStatus()).isEqualTo("CANDIDATE");
    }

    @Test void candidatePreservesArbitrationBindingAndSeparateManualScoreOnFirstCapture() throws Exception {
        var base=business.validatedProjection(review,result,task);
        var workflow=new BusinessItemReviewEvaluator.ReviewWorkflow(
                BusinessItemReviewEvaluator.ReviewWorkflow.SCHEMA,BusinessItemReviewEvaluator.ReviewStage.ARBITRATION,
                "second-review",2,"a".repeat(64));
        var adjustment=new BusinessItemReviewEvaluator.ScoreAdjustment("result",
                BusinessItemReviewEvaluator.scoringHash("result",base.reviewed().scoring(),json),"90.25","仲裁人工调整");
        var manual=BusinessItemReviewEvaluator.manualScore("result",base.reviewed().scoring(),adjustment,true,json);
        when(business.validatedProjection(review,result,task)).thenReturn(new BusinessItemReviewEvaluator.Projection(
                "result",base.original(),base.reviewed(),base.decisions(),null,manual,workflow));
        review.setReviewRevision(3);
        var sample=create();var expected=json.readTree(sample.getExpectedJson());
        assertThat(expected.path("reviewWorkflow")).isEqualTo(json.valueToTree(workflow));
        assertThat(expected.path("reviewRevision").asInt()).isEqualTo(3);
        assertThat(expected.at("/expected/scoring/finalScore").decimalValue()).isEqualByComparingTo("100");
        assertThat(expected.at("/manualScore/status").asText()).isEqualTo("APPLIED");
        assertThat(expected.at("/manualScore/effectiveScore").decimalValue()).isEqualByComparingTo("90.25");
        String captured=sample.getExpectedJson();when(samples.selectById(sample.getId())).thenReturn(sample);
        when(business.validatedProjection(review,result,task)).thenThrow(new IllegalStateException("must not recapture"));
        assertThat(create()).isSameAs(sample);assertThat(sample.getExpectedJson()).isEqualTo(captured);
        verify(samples,times(1)).insert(any(QualitySample.class));
    }

}
