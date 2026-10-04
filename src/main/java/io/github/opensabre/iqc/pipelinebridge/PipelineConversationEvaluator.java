package io.github.opensabre.iqc.pipelinebridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.pipeline.*;
import io.github.opensabre.iqc.pipelineasset.*;
import io.github.opensabre.iqc.finding.*;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.scoring.InspectionScoring;
import io.github.opensabre.iqc.scheme.SchemeDefinition;
import io.github.opensabre.iqc.scheme.SchemeResultEvaluator;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** Produces the existing business score projection from frozen pipeline facts, never from rule prices. */
@Service
@RequiredArgsConstructor
public class PipelineConversationEvaluator {
    public record Evaluation(String schemaVersion,String inputHash,PipelineRuntime.Run run,
            List<FindingFusion.Result> fusion,List<SchemeResultEvaluator.ItemResult> items,InspectionScoring.Result scoring,String riskLevel,PipelineRuntime.Mode mode) { }
    private final PipelineRuntime runtime;
    private final ObjectMapper json;
    private final io.github.opensabre.iqc.task.dao.InspectionTaskMapper tasks;
    public Evaluation evaluate(InspectionTask task,PipelineRelease release,List<ConversationMessage> messages) {
        return evaluate(task,release,messages,PipelineRuntime.Mode.PRODUCTION);
    }
    public Evaluation evaluate(InspectionTask task,PipelineRelease release,List<ConversationMessage> messages,PipelineRuntime.Mode mode) {
        var input=json.createObjectNode(); input.set("messages",json.valueToTree(messages));
        var run=runtime.execute(release.pipeline(),input,Map.of("__task",json.valueToTree(task),"__messages",json.valueToTree(messages)),
                mode, trace -> {
                    int renewed=tasks.update(null,com.baomidou.mybatisplus.core.toolkit.Wrappers.<InspectionTask>lambdaUpdate()
                            .set(InspectionTask::getUpdatedTime,new java.util.Date()).eq(InspectionTask::getId,task.getId())
                            .eq(InspectionTask::getCurrentExecutionId,task.getCurrentExecutionId()).eq(InspectionTask::getStatus,"RUNNING"));
                    if(renewed!=1) Thread.currentThread().interrupt(); // stop before scheduling another node after pause/cancel/lease loss
                });
        var observations=new ArrayList<>(run.findings());
        for(var node:release.pipeline().nodes()) {
            boolean produced=observations.stream().anyMatch(value->node.nodeId().equals(value.source().nodeId()));
            if(produced) continue;
            var trace=run.trace().stream().filter(value->node.nodeId().equals(value.nodeId())).reduce((a,b)->b).orElse(null);
            var state=trace==null || trace.result().status()==NodeResult.Status.SKIPPED?Finding.State.NOT_EVALUATED:Finding.State.ERROR;
            FindingDefinition definition;
            try { definition=json.treeToValue(node.config().path("findingDefinition"),FindingDefinition.class); }
            catch(Exception ex) { throw new IllegalStateException("Frozen finding definition is invalid",ex); }
            String identity=task.getCurrentExecutionId()+":"+messages.getFirst().getConversationId()+":"+node.nodeId()+":missing";
            var source=new Finding.Source(node.nodeId(),node.type(),node.version(),trace==null?1:Math.max(1,trace.attempt()),null);
            observations.add(new Finding(PipelineAssetService.hash(json.valueToTree(identity).toString()),definition.code(),definition.category(),
                    definition.valueType(),null,state,null,definition.defaultSeverity(),trace==null?"Node not executed":trace.result().error(),List.of(),source));
        }
        var fused=FindingFusion.fuse(observations,release.fusion());
        var byCode=new HashMap<String,FindingFusion.Result>(); fused.forEach(value->byCode.put(value.code(),value));
        var items=new ArrayList<SchemeResultEvaluator.ItemResult>(); var decisions=new ArrayList<InspectionScoring.Decision>();
        Finding.Severity highest=Finding.Severity.NONE;
        for(var mapping:release.items()) {
            var fact=byCode.get(mapping.findingCode());
            InspectionScoring.ItemStatus status=fact==null?InspectionScoring.ItemStatus.NOT_EVALUATED:switch(fact.state()) {
                case KNOWN -> {
                    boolean failed=mapping.hitMeaning()==SchemeDefinition.HitMeaning.VIOLATION?fact.value().booleanValue():!fact.value().booleanValue();
                    yield failed?InspectionScoring.ItemStatus.FAIL:InspectionScoring.ItemStatus.PASS;
                }
                case ERROR -> InspectionScoring.ItemStatus.ERROR;
                case UNKNOWN,CONFLICT -> InspectionScoring.ItemStatus.REVIEW_REQUIRED;
                case NOT_EVALUATED -> InspectionScoring.ItemStatus.NOT_EVALUATED;
            };
            if(mapping.appliesWhenFindingCode()!=null) {
                var gate=byCode.get(mapping.appliesWhenFindingCode());
                if(gate==null || gate.state()==Finding.State.NOT_EVALUATED) status=InspectionScoring.ItemStatus.NOT_EVALUATED;
                else if(gate.state()==Finding.State.ERROR) status=InspectionScoring.ItemStatus.ERROR;
                else if(gate.state()!=Finding.State.KNOWN) status=InspectionScoring.ItemStatus.REVIEW_REQUIRED;
                else if(!gate.value().booleanValue()) status=InspectionScoring.ItemStatus.NOT_APPLICABLE;
            }
            var matched=fact==null || status==InspectionScoring.ItemStatus.NOT_APPLICABLE?List.<String>of():fact.evidence().stream().filter(value->value.type()==Finding.EvidenceType.CONVERSATION)
                    .map(Finding.Evidence::reference).filter(id->messages.stream().anyMatch(message->id.equals(message.getId()))).distinct().toList();
            if(status==InspectionScoring.ItemStatus.FAIL && fact!=null && fact.severity().compareTo(highest)>0) highest=fact.severity();
            var producer=release.pipeline().nodes().stream().filter(node->mapping.findingCode().equals(node.config().path("findingDefinition").path("code").asText()))
                    .findFirst().orElseThrow();
            var rule=producer.config().path("ruleSnapshot");
            items.add(new SchemeResultEvaluator.ItemResult(mapping.itemCode(),mapping.itemCode(),rule.path("id").asText(),rule.path("versionNo").asInt(),status,matched));
            decisions.add(new InspectionScoring.Decision(mapping.itemCode(),status));
        }
        var score=InspectionScoring.evaluate(release.scoring(),decisions);
        return new Evaluation("iqc-pipeline-execution-v1",PipelineAssetService.hash(input.toString()),run,fused,List.copyOf(items),score,score.scoreStatus()==InspectionScoring.ScoreStatus.PENDING?"UNKNOWN":highest.name(),mode);
    }
}
