package io.github.opensabre.iqc.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.fusion.*;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.privacy.SensitiveReadAccessService;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.scheme.SchemeResultEvaluator;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.dao.*;
import io.github.opensabre.iqc.task.model.InspectionTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real report aggregation; source validation and current grants are controlled separately. */
class BusinessFindingDistributionTest {
    final ObjectMapper json=new ObjectMapper();
    final InspectionTaskMapper tasks=mock(InspectionTaskMapper.class);
    final ConversationInspectionResultMapper results=mock(ConversationInspectionResultMapper.class);
    final BusinessItemReviewService reviews=mock(BusinessItemReviewService.class);
    final IqcDataScope scope=mock(IqcDataScope.class);
    final SensitiveReadAccessService access=mock(SensitiveReadAccessService.class);
    final BusinessQualityReportService service=new BusinessQualityReportService(tasks,results,reviews,scope,json,mock(TaskExecutionMapper.class),null,null,access);
    final InspectionScoring.Policy scoring=new InspectionScoring.Policy(InspectionScoring.POLICY_VERSION,InspectionScoring.Mode.DEDUCTION,100,90,List.of(new InspectionScoring.Item("a",100,false)));
    SchemeResultEvaluator.Evaluation evaluation(){return new SchemeResultEvaluator.Evaluation(List.of(new SchemeResultEvaluator.ItemResult("a","a","rule",1,InspectionScoring.ItemStatus.PASS,List.of())),InspectionScoring.evaluate(scoring,List.of(new InspectionScoring.Decision("a",InspectionScoring.ItemStatus.PASS))));}
    void grant(){when(access.current(Set.of("iqc:report:view"))).thenReturn(new SensitiveReadAccessService.Access("iqc-sensitive-read-access-v1",true,true,false,Instant.now()));}
    InspectionTask fixture(int count) throws Exception {
        grant();var nodes=new ArrayList<PipelineDefinition.Node>();
        for(var code:List.of("CHECK","TEXT","CHECK")){
            var config=json.createObjectNode();config.set("findingDefinition",json.valueToTree(new FindingDefinition(FindingDefinition.SCHEMA,code,code,"BUSINESS",null,code.equals("TEXT")?Finding.ValueType.STRING:Finding.ValueType.BOOLEAN,List.of(),Finding.Severity.LOW,false,List.of())));
            nodes.add(new PipelineDefinition.Node("n"+nodes.size(),"RULE","check","v1",List.of(),Map.of(),config,null,1000,0,PipelineDefinition.OnError.FAIL_PIPELINE,null,null,true));
        }
        var release=new PipelineRelease(PipelineRelease.SCHEMA,new PipelineDefinition(PipelineDefinition.SCHEMA,nodes),new FusionPolicy("fusion",1,FusionPolicy.Method.PRIORITY,Map.of(),Map.of(),null,Set.of()),scoring,List.of(new PipelineRelease.ItemMapping("a","CHECK",io.github.opensabre.iqc.scheme.SchemeDefinition.HitMeaning.VIOLATION)));
        var snapshot=json.createObjectNode();var marker=snapshot.putObject("pipelineSnapshot").put("pipelineId","pipeline").put("versionNo",1);
        marker.set("release",json.valueToTree(release));marker.put("contentHash",PipelineAssetService.hash(json.writeValueAsString(release)));
        var task=new InspectionTask();task.setId("task");task.setRuleSnapshotJson(snapshot.toString());task.setConversationIdsJson(json.writeValueAsString(java.util.stream.IntStream.range(0,count).mapToObj(i->"c"+i).toList()));
        when(tasks.selectById("task")).thenReturn(task);when(scope.canView(null,null)).thenReturn(true);
        var validator=new BusinessItemReviewService(null,null,null,null,null,json);
        when(reviews.pipelineRelease(any())).thenAnswer(call->validator.pipelineRelease(call.getArgument(0)));
        return task;
    }
    FindingFusion.Result fact(String code,Finding.State state,boolean value){
        return new FindingFusion.Result(code,code.equals("TEXT")?Finding.ValueType.STRING:Finding.ValueType.BOOLEAN,state,state==Finding.State.KNOWN?(code.equals("TEXT")?json.getNodeFactory().textNode("private-secret-value"):json.getNodeFactory().booleanNode(value)):null,Finding.Severity.HIGH,List.of(),List.of("private-selected-id"),List.of(),List.of(new Finding.Evidence("e",Finding.EvidenceType.CONVERSATION,"private-evidence-reference",json.getNodeFactory().textNode("private-body"))),"private-explanation","fusion",1);
    }
    ConversationInspectionResult source(int i,List<FindingFusion.Result> facts){
        var result=new ConversationInspectionResult();result.setId("r"+i);result.setConversationId("c"+i);
        when(results.selectLatestForTaskConversation("task","c"+i)).thenReturn(result);
        when(reviews.validatedMachineData(eq(result),any())).thenReturn(new BusinessItemReviewService.MachineData(evaluation(),facts));return result;
    }
    BusinessQualityReportService.Report report(){return service.report(List.of("task"),null,"SELECTED_RUNS",false,false,true);}
    BusinessQualityReportService.FindingDistribution distribution(){return report().findingDistributions().values().iterator().next();}
    @ParameterizedTest @ValueSource(ints={0,1,2}) void eachCurrentGrantIsRequiredBeforeAnyTaskOrResultRead(int missing){
        if(missing==0)when(access.current(anySet())).thenThrow(io.github.opensabre.iqc.governance.IqcException.accessDenied("revoked"));
        else when(access.current(anySet())).thenReturn(new SensitiveReadAccessService.Access("v1",missing!=1,missing!=2,false,Instant.now()));
        assertThatThrownBy(this::report).isInstanceOf(io.github.opensabre.iqc.governance.IqcException.class);
        verifyNoInteractions(tasks,results,reviews,scope);
    }
    @Test void defaultRetainsOldShapeAndDoesNotRequestSensitiveAccessOrMachineData() throws Exception {
        fixture(1);var result=new ConversationInspectionResult();result.setId("r");when(results.selectLatestForTaskConversation(any(),any())).thenReturn(result);when(reviews.validatedMachineEvaluation(any(),any())).thenReturn(evaluation());
        clearInvocations(access,reviews);var report=service.report(List.of("task"));
        assertThat(json.valueToTree(report).has("findingDistributions")).isFalse();verifyNoInteractions(access);verify(reviews,never()).validatedMachineData(any(),any());
    }
    @Test void countsAllFiveStatesOncePerDeclaredCodeAndDoesNotExposeRawFacts() throws Exception {
        fixture(6);int i=0;for(var state:Finding.State.values())source(i++,List.of(fact("CHECK",state,true),fact("TEXT",state,false)));source(5,List.of(fact("CHECK",Finding.State.KNOWN,false)));
        var report=report();var distribution=report.findingDistributions().values().iterator().next();var machine=distribution.machine();
        assertThat(machine.expectedResults()).isEqualTo(6);assertThat(machine.sourceResults()).isEqualTo(6);assertThat(machine.unavailableResults()).isZero();assertThat(machine.facts()).extracting(BusinessQualityReportService.FactDistribution::code).containsExactly("CHECK","TEXT");
        var check=machine.facts().getFirst();assertThat(check.presentCount()).isEqualTo(6);assertThat(check.states()).containsEntry("KNOWN",2).containsEntry("UNKNOWN",1).containsEntry("CONFLICT",1).containsEntry("ERROR",1).containsEntry("NOT_EVALUATED",1);assertThat(check.trueCount()).isEqualTo(1);assertThat(check.falseCount()).isEqualTo(1);assertThat(check.knownSeverityCounts().get("HIGH")).isEqualTo(2);
        var text=machine.facts().getLast();assertThat(text.presentCount()).isEqualTo(5);assertThat(text.missingFindingCount()).isEqualTo(1);assertThat(text.trueCount()+text.falseCount()).isZero();
        assertThat(distribution.reviewed().sourceResults()).isZero();assertThat(distribution.reviewed().unavailableResults()).isEqualTo(6);
        assertThat(json.writeValueAsString(report)).doesNotContain("private-secret-value","private-evidence-reference","private-body","private-explanation","private-selected-id");
    }
    @Test void absentResultAndAbsentFactHaveSeparateDenominators() throws Exception {
        fixture(3);source(0,List.of(fact("CHECK",Finding.State.UNKNOWN,false)));source(1,List.of());
        var machine=distribution().machine();assertThat(machine.expectedResults()).isEqualTo(3);assertThat(machine.sourceResults()).isEqualTo(2);assertThat(machine.unavailableResults()).isEqualTo(1);assertThat(machine.facts().getFirst().presentCount()).isEqualTo(1);assertThat(machine.facts().getFirst().missingFindingCount()).isEqualTo(1);assertThat(machine.facts().getFirst().states().get("UNKNOWN")).isEqualTo(1);
    }
    @Test void completedReviewWithNoFindingOverrideReusesValidatedMachineFacts() throws Exception {
        fixture(2);var r=source(0,List.of(fact("CHECK",Finding.State.KNOWN,true)));source(1,List.of(fact("CHECK",Finding.State.KNOWN,false)));
        var completed=new io.github.opensabre.iqc.quality.model.ResultReview();completed.setStatus("COMPLETED");when(reviews.latest("r0",true)).thenReturn(completed);
        when(reviews.validatedProjection(eq(completed),eq(r),any())).thenReturn(new BusinessItemReviewEvaluator.Projection("r0",evaluation(),evaluation(),List.of()));
        var d=distribution();assertThat(d.machine().facts().getFirst().falseCount()).isEqualTo(1);assertThat(d.reviewed().sourceResults()).isEqualTo(1);assertThat(d.reviewed().unavailableResults()).isEqualTo(1);assertThat(d.reviewed().facts().getFirst().trueCount()).isEqualTo(1);assertThat(d.reviewed().facts().getFirst().falseCount()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","undeclared","wrongType","badBoolean"}) void invalidSourceCannotProducePartialStatistics(String kind) throws Exception {
        fixture(1);var valid=fact("CHECK",Finding.State.KNOWN,true);List<FindingFusion.Result> facts=switch(kind){
            case "duplicate"->List.of(valid,valid);
            case "undeclared"->List.of(fact("OTHER",Finding.State.KNOWN,true));
            case "wrongType"->List.of(new FindingFusion.Result("CHECK",Finding.ValueType.STRING,Finding.State.KNOWN,json.getNodeFactory().textNode("private"),Finding.Severity.HIGH,List.of(),List.of(),List.of(),List.of(),"reason","fusion",1));
            default->List.of(new FindingFusion.Result("CHECK",Finding.ValueType.BOOLEAN,Finding.State.KNOWN,json.getNodeFactory().textNode("private"),Finding.Severity.HIGH,List.of(),List.of(),List.of(),List.of(),"reason","fusion",1));};
        source(0,facts);assertThatThrownBy(this::report).isInstanceOf(io.github.opensabre.iqc.governance.IqcException.class);
    }
}
