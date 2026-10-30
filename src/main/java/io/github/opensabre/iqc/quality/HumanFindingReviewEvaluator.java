package io.github.opensabre.iqc.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.finding.FindingDefinition;
import io.github.opensabre.iqc.fusion.FindingFusion;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import java.util.*;

/** Human projections over validated frozen fusion; no detector calls, writes or action execution. */
public final class HumanFindingReviewEvaluator {
    private HumanFindingReviewEvaluator() { }
    /** An explicit human assessment of a frozen model claim, separate from quote syntax and correctness. */
    public enum SourceSupport { SUPPORTED, CONTRADICTED, INSUFFICIENT_SOURCE }
    public record ClaimSourceSupport(String rawFindingId, SourceSupport verdict, List<String> evidenceIds,
            List<String> messageIds, String reason) {
        public ClaimSourceSupport {
            if(rawFindingId==null||rawFindingId.isBlank()||rawFindingId.length()>256||verdict==null
                    ||reason==null||reason.isBlank()||reason.trim().length()>1000)
                throw new IllegalArgumentException("来源支持标注须指定模型事实、结论和原因");
            evidenceIds=evidenceIds==null?List.of():List.copyOf(evidenceIds);
            messageIds=messageIds==null?List.of():List.copyOf(messageIds);
            if(evidenceIds.size()+messageIds.size()>100
                    ||evidenceIds.stream().anyMatch(id->id.isBlank()||id.length()>256)
                    ||messageIds.stream().anyMatch(id->id.isBlank()||id.length()>64)
                    ||evidenceIds.stream().distinct().count()!=evidenceIds.size()
                    ||messageIds.stream().distinct().count()!=messageIds.size()
                    ||verdict!=SourceSupport.INSUFFICIENT_SOURCE&&evidenceIds.isEmpty()&&messageIds.isEmpty())
                throw new IllegalArgumentException("来源支持标注须引用有界、唯一的来源；支持或矛盾结论不能缺少来源");
            reason=reason.trim();
        }
    }
    /** Retaining an existing evidence ID and adding a frozen message are explicit separate operations. */
    public record Decision(String sourceResultId,String findingCode,String expectedFindingHash,
            Finding.State state,@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=ExactValueDeserializer.class) JsonNode value,Finding.Severity severity,String reason,
            List<String> retainedEvidenceIds,List<String> addedMessageIds,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String valueJson,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
            List<ClaimSourceSupport> sourceSupport) {
        public Decision(String sourceResultId,String findingCode,String expectedFindingHash,Finding.State state,JsonNode value,
                Finding.Severity severity,String reason,List<String> retainedEvidenceIds,List<String> addedMessageIds,String valueJson){
            this(sourceResultId,findingCode,expectedFindingHash,state,value,severity,reason,retainedEvidenceIds,addedMessageIds,valueJson,List.of());
        }
        public Decision(String sourceResultId,String findingCode,String expectedFindingHash,Finding.State state,JsonNode value,
                Finding.Severity severity,String reason,List<String> retainedEvidenceIds,List<String> addedMessageIds){
            this(sourceResultId,findingCode,expectedFindingHash,state,value,severity,reason,retainedEvidenceIds,addedMessageIds,null,List.of());
        }
        public Decision {
            sourceSupport=sourceSupport==null?List.of():List.copyOf(sourceSupport);
            if(sourceSupport.size()>1000||sourceSupport.stream().map(ClaimSourceSupport::rawFindingId).distinct().count()!=sourceSupport.size())
                throw new IllegalArgumentException("来源支持标注过多或重复");
            if(valueJson!=null){
                if(value!=null&&!value.isNull()||valueJson.length()>100000)throw new IllegalArgumentException("人工事实值须使用单一且有界的JSON输入");
                try{value=new ObjectMapper().readerFor(JsonNode.class)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(valueJson);}
                catch(java.io.IOException invalid){throw new IllegalArgumentException("人工事实JSON值无效",invalid);}
                valueJson=null;
            }
            validateManualValue(value,0,new int[2]);
            value=value==null||value.isNull()?null:value.deepCopy();
            retainedEvidenceIds=retainedEvidenceIds==null?null:List.copyOf(retainedEvidenceIds);
            addedMessageIds=addedMessageIds==null?null:List.copyOf(addedMessageIds);
        }
        @Override public JsonNode value(){return value==null?null:value.deepCopy();}
    }
    private static void validateManualValue(JsonNode value,int depth,int[] budget){
        if(value==null||value.isNull())return;
        if(depth>32||++budget[0]>10000)throw new IllegalArgumentException("人工事实值嵌套或节点数量过大");
        if(value.isNumber()){
            var decimal=value.decimalValue();
            if(Math.abs((long)decimal.scale())>64)throw new IllegalArgumentException("人工数值超出精确范围");
            new io.github.opensabre.iqc.pipelineasset.PipelineRelease.NumericPredicate(
                    io.github.opensabre.iqc.pipeline.PipelineDefinition.Operator.EQUALS,decimal.toPlainString());
        }
        if(value.isTextual())budget[1]+=value.textValue().length();
        if(value.isObject())for(var property:value.properties()){
            budget[1]+=property.getKey().length();validateManualValue(property.getValue(),depth+1,budget);
        }
        else if(value.isArray())for(var child:value)validateManualValue(child,depth+1,budget);
        if(budget[1]>100000)throw new IllegalArgumentException("人工事实文本过大");
    }
    /** Scope exact tree decoding to manual values, including numbers nested inside OBJECT findings. */
    public static final class ExactValueDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<JsonNode> {
        @Override public JsonNode deserialize(com.fasterxml.jackson.core.JsonParser parser,com.fasterxml.jackson.databind.DeserializationContext context)
                throws java.io.IOException {
            return new ObjectMapper().readerFor(JsonNode.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(parser);
        }
    }
    /** Field-local bridge for HTTP Jackson 3 while persisted domain trees remain Jackson 2. */
    public static final class HttpExactValueDeserializer extends tools.jackson.databind.ValueDeserializer<JsonNode> {
        private static final tools.jackson.databind.json.JsonMapper HTTP = tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                // This mapper consumes one nested field; the outer MVC reader still checks the whole document.
                .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        @Override public JsonNode deserialize(tools.jackson.core.JsonParser parser,
                tools.jackson.databind.DeserializationContext context) {
            var tree = HTTP.readTree(parser);
            try {
                return new ObjectMapper().readerFor(JsonNode.class)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .readValue(tree.toString());
            } catch (java.io.IOException invalid) {
                return context.reportInputMismatch(JsonNode.class, "精确 JSON 值无效");
            }
        }
    }

    /** Original observations stay available; effective values are explicitly human and not re-fused outputs. */
    public record Projection(List<FindingFusion.Result> original,List<FindingFusion.Result> reviewed,List<Decision> decisions) {
        public Projection {original=List.copyOf(original);reviewed=List.copyOf(reviewed);decisions=List.copyOf(decisions);}
    }
    public static String findingHash(FindingFusion.Result finding,ObjectMapper json) {
        try{return SelectorAssetService.hash(json.writeValueAsString(finding));}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw IqcException.invalidState("复核事实摘要无法生成");}
    }
    /** Caller validates the machine trace and authorizes/locks its source before providing frozen inputs. */
    public static Projection evaluate(String sourceResultId,List<FindingFusion.Result> original,
            Map<String,FindingDefinition> definitions,List<Decision> decisions,JsonNode frozenMessages,ObjectMapper json) {
        if(sourceResultId==null||sourceResultId.isBlank()||original==null||definitions==null||decisions==null||json==null
                ||original.size()>1000||decisions.size()>1000||frozenMessages==null||!frozenMessages.isArray()
                ||frozenMessages.isEmpty()||frozenMessages.size()>10000)
            throw IqcException.invalidArgument("人工事实复核必须指定冻结来源和完整消息");
        var messages=new LinkedHashMap<String,JsonNode>();String conversation=null;
        for(var message:frozenMessages){
            if(!message.isObject()||!message.path("id").isTextual()||message.path("id").asText().isBlank()
                    ||!message.path("conversationId").isTextual()||message.path("conversationId").asText().isBlank()
                    ||!message.path("sequenceNo").isIntegralNumber()||!message.path("sequenceNo").canConvertToInt()
                    ||message.path("sequenceNo").intValue()<0
                    ||messages.putIfAbsent(message.path("id").asText(),message)!=null)
                throw IqcException.invalidState("冻结会话消息身份无效或重复");
            if(conversation==null)conversation=message.path("conversationId").asText();
            else if(!conversation.equals(message.path("conversationId").asText()))throw IqcException.invalidState("冻结消息跨会话");
        }
        var facts=new LinkedHashMap<String,FindingFusion.Result>();
        for(var fact:original){
            if(fact==null||facts.putIfAbsent(fact.code(),fact)!=null||!definitions.containsKey(fact.code())
                    ||fact.rawFindings().isEmpty()||definitions.get(fact.code())==null||definitions.get(fact.code()).valueType()!=fact.valueType())
                throw IqcException.invalidState("冻结事实定义或融合身份损坏");
        }
        var overrides=new LinkedHashMap<String,FindingFusion.Result>();var normalized=new ArrayList<Decision>();
        for(var decision:decisions){
            if(decision==null||!sourceResultId.equals(decision.sourceResultId()))throw IqcException.invalidArgument("人工事实来源不一致");
            var fact=facts.get(decision.findingCode());
            if(fact==null||!findingHash(fact,json).equals(decision.expectedFindingHash())||overrides.containsKey(fact.code()))
                throw IqcException.invalidArgument("人工事实基准已变化、未知或重复");
            validateSourceSupport(decision.sourceSupport(),fact,messages.keySet());
            if(decision.state()!=Finding.State.KNOWN&&decision.state()!=Finding.State.UNKNOWN
                    ||decision.severity()==null||decision.reason()==null||decision.reason().isBlank()||decision.reason().trim().length()>1000)
                throw IqcException.invalidArgument("人工事实须明确已知或未知状态、严重度和原因");
            unique(decision.retainedEvidenceIds());unique(decision.addedMessageIds());
            if(decision.retainedEvidenceIds().size()+decision.addedMessageIds().size()>100)
                throw IqcException.invalidArgument("人工事实证据最多100条");
            var oldEvidence=new LinkedHashMap<String,Finding.Evidence>();
            for(var evidence:fact.evidence())if(oldEvidence.putIfAbsent(evidence.id(),evidence)!=null)
                throw IqcException.invalidState("原始融合证据身份重复");
            var evidence=new ArrayList<Finding.Evidence>();
            for(var id:decision.retainedEvidenceIds()){
                var retained=oldEvidence.get(id);if(retained==null)throw IqcException.invalidArgument("不可引用其它事实证据");evidence.add(retained);
            }
            for(var id:decision.addedMessageIds()){
                var message=messages.get(id);if(message==null)throw IqcException.invalidArgument("新增证据必须引用冻结会话消息");
                var details=json.createObjectNode().put("messageId",id).put("sequenceNo",message.path("sequenceNo").intValue());
                for(String field:List.of("startMs","endMs"))if(message.hasNonNull(field))details.set(field,message.get(field));
                String identity=SelectorAssetService.hash(json.valueToTree(List.of(sourceResultId,fact.code(),id)).toString());
                evidence.add(new Finding.Evidence("human-"+identity,Finding.EvidenceType.CONVERSATION,id,details));
            }
            // Reuse canonical value/evidence validation; this temporary object is never returned as an engine observation.
            var anchor=fact.rawFindings().getFirst();var definition=definitions.get(fact.code());
            try {
                var validated=new Finding(anchor.id(),fact.code(),definition.category(),fact.valueType(),decision.value(),decision.state(),
                        null,decision.severity(),decision.reason().trim(),evidence,anchor.source());
                definition.validate(validated);
            } catch(IllegalArgumentException invalid){throw IqcException.invalidArgument("人工事实值、严重度或证据与冻结定义不一致");}
            // No machine source wins the human conclusion: confidence gates must not inherit machine calibration.
            var reviewed=new FindingFusion.Result(fact.code(),fact.valueType(),decision.state(),decision.value(),decision.severity(),
                    fact.rawFindings(),List.of(),fact.rawFindings().stream().map(Finding::id).toList(),evidence,
                    decision.reason().trim(),fact.policyId(),fact.policyVersion());
            overrides.put(fact.code(),reviewed);
            normalized.add(new Decision(sourceResultId,fact.code(),decision.expectedFindingHash(),decision.state(),decision.value(),
                    decision.severity(),decision.reason().trim(),decision.retainedEvidenceIds(),decision.addedMessageIds(),null,decision.sourceSupport()));
        }
        return new Projection(original,original.stream().map(fact->overrides.getOrDefault(fact.code(),fact)).toList(),normalized);
    }
    private static void validateSourceSupport(List<ClaimSourceSupport> assessments,FindingFusion.Result fact,Set<String> messageIds) {
        for(var assessment:assessments){
            var targets=fact.rawFindings().stream().filter(raw->raw.id().equals(assessment.rawFindingId())).toList();
            if(targets.size()!=1||targets.getFirst().state()!=Finding.State.KNOWN
                    ||!"LLM".equals(targets.getFirst().source().engineType()))
                throw IqcException.invalidArgument("来源支持标注须引用本冻结事实中的唯一已知LLM原始观察");
            var evidenceIds=targets.getFirst().evidence().stream().map(Finding.Evidence::id).collect(java.util.stream.Collectors.toSet());
            if(!evidenceIds.containsAll(assessment.evidenceIds())||!messageIds.containsAll(assessment.messageIds()))
                throw IqcException.invalidArgument("来源支持标注不可引用其它观察证据或冻结会话外消息");
        }
    }
    public record PolicyProjection(Projection findings,List<io.github.opensabre.iqc.scoring.InspectionScoring.Decision> items,
            io.github.opensabre.iqc.scoring.InspectionScoring.Result scoring,
            io.github.opensabre.iqc.pipelineasset.RiskPolicy.Decision riskDecision,String riskLevel,
            Map<String,io.github.opensabre.iqc.label.FindingLabelProjection.Projection> labels,
            io.github.opensabre.iqc.pipelineasset.ReviewPolicy.Decision reviewDecision,String resultStatus) {
        public PolicyProjection {items=List.copyOf(items);labels=Collections.unmodifiableMap(new LinkedHashMap<>(labels));}
    }
    /** Uses original decision policies over explicitly reviewed facts; never dispatches resulting actions. */
    public static PolicyProjection evaluatePolicies(String sourceResultId,
            io.github.opensabre.iqc.pipelineasset.PipelineRelease release,
            io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator.Evaluation trace,
            List<Decision> corrections,JsonNode validatedInput,ObjectMapper json) {
        return evaluatePolicies(sourceResultId,release,trace,corrections,List.of(),validatedInput,json);
    }
    public static PolicyProjection evaluatePolicies(String sourceResultId,
            io.github.opensabre.iqc.pipelineasset.PipelineRelease release,
            io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator.Evaluation trace,
            List<Decision> corrections,List<BusinessItemReviewEvaluator.Decision> itemOverrides,JsonNode validatedInput,ObjectMapper json) {
        if(release==null||trace==null||validatedInput==null||!validatedInput.isObject()
                ||!validatedInput.path("businessContext").isObject()||!validatedInput.path("messages").isArray())
            throw IqcException.invalidArgument("人工事实重算必须指定冻结流水线和执行输入");
        var messages=validatedInput.path("messages");var allowed=new HashSet<String>();
        messages.forEach(message->allowed.add(message.path("id").asText()));
        // Validate the original machine fusion/items/scoring before computing any human alternative.
        var original=BusinessItemReviewEvaluator.evaluatePipeline(sourceResultId,release,trace,trace.items(),itemOverrides,allowed,
                validatedInput.path("businessContext"));
        var definitions=new LinkedHashMap<String,FindingDefinition>();
        for(var node:release.pipeline().nodes())try {
            var definition=json.treeToValue(node.config().path("findingDefinition"),FindingDefinition.class);
            if(definition==null)throw IqcException.invalidState("冻结事实定义缺失");
            var existing=definitions.putIfAbsent(definition.code(),definition);
            if(existing!=null&&!existing.equals(definition))throw IqcException.invalidState("同名事实的冻结语义不一致");
        }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw IqcException.invalidState("冻结事实定义损坏");}
        var projection=evaluate(sourceResultId,trace.fusion(),definitions,corrections,messages,json);
        var context=validatedInput.path("businessContext");var facts=projection.reviewed();
        var items=io.github.opensabre.iqc.pipelinebridge.PipelineDecisionEvaluator.decisions(release.items(),facts);
        var overrides=new LinkedHashMap<String,io.github.opensabre.iqc.scoring.InspectionScoring.ItemStatus>();
        original.decisions().forEach(item->overrides.put(item.itemCode(),item.finalStatus()));
        items=items.stream().map(item->new io.github.opensabre.iqc.scoring.InspectionScoring.Decision(item.itemCode(),
                overrides.getOrDefault(item.itemCode(),item.status()))).toList();
        var inputs=io.github.opensabre.iqc.pipelinebridge.PipelineDecisionEvaluator.inputContext(release,facts,items,context);
        var score=io.github.opensabre.iqc.scoring.InspectionScoring.evaluate(release.scoring(),items,
                release.scoring().advanced()==null?null:inputs);
        var risk=io.github.opensabre.iqc.pipelinebridge.PipelineDecisionEvaluator.riskDecision(release,facts,items,score,context);
        String riskLevel=risk==null?io.github.opensabre.iqc.pipelinebridge.PipelineDecisionEvaluator.riskLevel(release.items(),facts,items,score):risk.level();
        var statuses=new LinkedHashMap<String,io.github.opensabre.iqc.scoring.InspectionScoring.ItemStatus>();
        items.forEach(item->statuses.put(item.itemCode(),item.status()));
        var statusItems=trace.items().stream().map(item->new io.github.opensabre.iqc.scheme.SchemeResultEvaluator.ItemResult(
                item.itemCode(),item.name(),item.ruleId(),item.ruleVersionNo(),statuses.get(item.itemCode()),item.matchedMessageIds())).toList();
        String status=io.github.opensabre.iqc.pipelinebridge.PipelineDecisionEvaluator.resultStatus(trace.run(),statusItems,score);
        String samplingHash=release.reviewPolicy()!=null&&io.github.opensabre.iqc.pipelineasset.ReviewPolicy.EXACT_SCHEMA.equals(release.reviewPolicy().schemaVersion())
                ?SelectorAssetService.hash(validatedInput.toString()):null;
        var review=io.github.opensabre.iqc.pipelinebridge.PipelineDecisionEvaluator.reviewDecision(release,facts,items,score,riskLevel,status,
                trace.inputHash(),context,samplingHash,json);
        return new PolicyProjection(projection,items,score,risk,riskLevel,inputs.labels(),review,status);
    }
    private static void unique(List<String> ids){
        if(ids==null||ids.size()>100||ids.stream().anyMatch(id->id==null||id.isBlank())||new HashSet<>(ids).size()!=ids.size())
            throw IqcException.invalidArgument("人工证据引用不能为空或重复");
    }
}
