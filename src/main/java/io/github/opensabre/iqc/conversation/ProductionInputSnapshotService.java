package io.github.opensabre.iqc.conversation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMessageMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.task.model.InspectionTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.*;

/** Production input revisions live in the existing immutable task snapshot, not the reviewed gold dataset. */
@Service @RequiredArgsConstructor
public class ProductionInputSnapshotService {
    public static final String SCHEMA="iqc-production-inputs-v1";
    public static final String ALIGNED_SCHEMA="iqc-production-inputs-v2";
    public static final String AUDIO_SCHEMA="iqc-production-inputs-v3";
    private static final int MAX_BYTES=16*1024*1024;
    public record Message(String id,int sequence,String role,String content,LocalTime relativeTime,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.conversation.ExactMillisecondsDeserializer.class) Long startMs,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=io.github.opensabre.iqc.conversation.ExactMillisecondsDeserializer.class) Long endMs,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) java.math.BigDecimal confidence) {
        public Message(String id,int sequence,String role,String content,LocalTime relativeTime){this(id,sequence,role,content,relativeTime,null,null,null);}
        public Message {
            if(id==null || !id.matches("[A-Za-z0-9:_-]{1,64}") || sequence<0 || role==null || role.isBlank()
                    || role.length()>128 || content==null || content.length()>MAX_BYTES)
                throw new IllegalArgumentException("invalid frozen production message");
            role=ConversationSpeakerRole.canonical(role);
            TranscriptAlignment.validate(startMs,endMs,confidence);
            if(confidence!=null)confidence=confidence.stripTrailingZeros();
        }
    }
    /** Frozen attachment identity stays separate from the context sent to model providers. */
    public record Audio(String sha256,long sizeBytes,String mediaType,long durationMs) {
        public Audio {
            if(sha256==null || !sha256.matches("[a-f0-9]{64}") || sizeBytes<1 || sizeBytes>9007199254740991L
                    || durationMs<1 || durationMs>9007199254740991L
                    || mediaType==null || !Set.of("audio/wav","audio/mpeg","audio/ogg","audio/flac","audio/mp4").contains(mediaType))
                throw new IllegalArgumentException("invalid frozen audio identity");
        }
        static Audio from(Conversation row) {
            if(row.getAudioSha256()==null && row.getAudioSizeBytes()==null && row.getAudioMediaType()==null && row.getAudioDurationMs()==null)return null;
            if(row.getAudioSizeBytes()==null || row.getAudioDurationMs()==null)throw new IllegalArgumentException("partial archived audio identity");
            return new Audio(row.getAudioSha256(),row.getAudioSizeBytes(),row.getAudioMediaType(),row.getAudioDurationMs());
        }
    }
    public record Input(String conversationId,String sourceFingerprint,JsonNode businessContext,List<Message> messages,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Audio audio) {
        public Input(String conversationId,String sourceFingerprint,JsonNode businessContext,List<Message> messages){this(conversationId,sourceFingerprint,businessContext,messages,null);}
        public Input {
            if(conversationId==null || !conversationId.matches("[A-Za-z0-9:_-]{1,64}") || businessContext==null || !businessContext.isObject()
                    || messages==null || messages.isEmpty() || messages.size()>10000)
                throw new IllegalArgumentException("invalid frozen production conversation");
            businessContext=businessContext.deepCopy();messages=List.copyOf(messages);
            int previous=-1;Set<String> ids=new HashSet<>();
            for(var message:messages){if(message.sequence()<=previous || !ids.add(message.id()))
                throw new IllegalArgumentException("frozen messages must be ordered and unique");
                if(audio!=null && message.endMs()!=null && message.endMs()>audio.durationMs())throw new IllegalArgumentException("segment exceeds frozen audio duration");
                previous=message.sequence();}
        }
        @Override public JsonNode businessContext(){return businessContext.deepCopy();}
    }
    public static final class Prepared {
        private final Map<String,List<ConversationMessage>> messages;
        private final Map<String,JsonNode> contexts;
        private final Map<String,Input> originals;
        private final String taskId;
        private final String snapshotJson;
        private final String releaseSchema;
        private Prepared(List<Input> inputs,InspectionTask task,String releaseSchema) {
            this.releaseSchema=releaseSchema;
            taskId=task.getId();snapshotJson=task.getRuleSnapshotJson();
            var originalsById=new LinkedHashMap<String,Input>();inputs.forEach(input->originalsById.put(input.conversationId(),input));
            originals=Collections.unmodifiableMap(originalsById);
            var byId=new LinkedHashMap<String,List<ConversationMessage>>();var values=new LinkedHashMap<String,JsonNode>();
            for(var input:inputs){
                byId.put(input.conversationId(),convert(input));values.put(input.conversationId(),input.businessContext());
            }
            messages=Collections.unmodifiableMap(byId);contexts=Collections.unmodifiableMap(values);
        }
        private List<ConversationMessage> originalMessages(String id){
            var input=originals.get(id);if(input==null)throw IqcException.invalidState("会话不在冻结输入中");
            return convert(input);
        }
        private static List<ConversationMessage> convert(Input input){
            return input.messages().stream().map(value->{var row=new ConversationMessage();row.setId(value.id());
                row.setConversationId(input.conversationId());row.setSequenceNo(value.sequence());row.setSpeakerRole(value.role());
                row.setContent(value.content());row.setRelativeTime(value.relativeTime());row.setStartMs(value.startMs());row.setEndMs(value.endMs());row.setConfidence(value.confidence());return row;}).toList();
        }
        public Map<String,List<ConversationMessage>> messages(){return messages;}
        /** Protocol comes from the persisted original task decoded during prepare. */
        public String releaseSchema(){return releaseSchema;}
        /** Returns only the task's frozen attachment; the caller must authorize any byte access separately. */
        public Audio audio(String conversationId){var input=originals.get(conversationId);if(input==null)throw IqcException.invalidState("会话不在冻结输入中");return input.audio();}
        public JsonNode businessContext(String id){var value=contexts.get(id);if(value==null)throw IqcException.invalidState("会话不在冻结输入中");return value.deepCopy();}
    }
    private final ConversationMapper conversations;
    private final ConversationMessageMapper messages;
    private final IqcDataScope scope;
    private final ObjectMapper json;
    private ObjectMapper exact(){return json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){throw IqcException.invalidState("生产输入无法序列化");}}
    public static boolean hasSnapshot(JsonNode snapshot){return snapshot!=null && snapshot.has("productionInputs");}
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public JsonNode capture(List<String> conversationIds) {
        if(conversationIds==null || conversationIds.size()>1000 || conversationIds.stream().distinct().count()!=conversationIds.size())
            throw IqcException.invalidArgument("生产输入要求唯一且最多1000个会话");
        var inputs=new ArrayList<Input>();int count=0;long bytes=0;
        for(String id:conversationIds){
            var row=conversations.selectById(id);if(row==null)throw IqcException.notFound("生产输入会话不存在");
            if(!scope.canView(row.getCreatedBy(),row.getOwnerGroupId()))throw IqcException.accessDenied("无权冻结该会话输入");
            var records=messages.selectList(Wrappers.<ConversationMessage>lambdaQuery().eq(ConversationMessage::getConversationId,id)
                    .orderByAsc(ConversationMessage::getSequenceNo).orderByAsc(ConversationMessage::getId).last("LIMIT 10001"));
            if(records.isEmpty() || records.size()>10000 || count+records.size()>100000)throw IqcException.invalidArgument("生产输入消息数为空或超过冻结上限");
            count+=records.size();
            var frozen=records.stream().map(value->{if(value.getSequenceNo()==null)throw IqcException.invalidState("归档消息顺序缺失");
                return new Message(value.getId(),value.getSequenceNo(),value.getSpeakerRole(),value.getContent(),value.getRelativeTime(),value.getStartMs(),value.getEndMs(),value.getConfidence());}).toList();
            var input=new Input(id,row.getSourceFingerprint(),context(row),frozen,Audio.from(row));
            bytes+=write(input).getBytes(StandardCharsets.UTF_8).length;if(bytes>MAX_BYTES)throw IqcException.invalidArgument("生产输入冻结超过16MiB");
            inputs.add(input);
        }
        boolean aligned=inputs.stream().flatMap(input->input.messages().stream()).anyMatch(message->message.startMs()!=null || message.confidence()!=null);
        var body=json.valueToTree(inputs);var envelope=json.createObjectNode().put("schemaVersion",inputs.stream().anyMatch(input->input.audio()!=null)?AUDIO_SCHEMA:aligned?ALIGNED_SCHEMA:SCHEMA)
                .put("hashAlgorithm","selector-decimal-sha256-v1").put("contentHash",SelectorAssetService.hash(body.toString()));
        envelope.set("conversations",body);if(envelope.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)
            throw IqcException.invalidArgument("生产输入冻结超过16MiB");return envelope;
    }
    private JsonNode context(Conversation row){
        var value=json.createObjectNode();
        value.put("employeeId",row.getEmployeeId()).put("employeeName",row.getEmployeeName()).put("employeeGroupId",row.getEmployeeGroupId())
                .put("employeeDepartmentId",row.getEmployeeDepartmentId()).put("customerExternalId",row.getCustomerExternalId())
                .put("customerName",row.getCustomerName()).put("customerContactMasked",row.getCustomerContactMasked())
                .put("channel",row.getChannel()).put("businessType",row.getBusinessType()).put("businessNo",row.getBusinessNo())
                .put("asrStatus",row.getAsrStatus()).put("customerLevel",row.getCustomerLevel());
        value.set("startedTime",json.valueToTree(row.getStartedTime()));value.set("endedTime",json.valueToTree(row.getEndedTime()));
        value.set("complaint",json.valueToTree(row.getComplaint()));
        try{
            var fields=row.getBusinessFieldsJson()==null?json.createObjectNode():exact().readTree(row.getBusinessFieldsJson());
            var tags=row.getTagsJson()==null?json.createArrayNode():exact().readTree(row.getTagsJson());
            if(!fields.isObject() || !tags.isArray())throw IqcException.invalidState("归档业务元数据结构无效");
            value.set("businessFields",fields);value.set("tags",tags);return value;
        }catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw IqcException.invalidState("归档业务元数据JSON无效");}
    }
    public void validateResult(InspectionTask task,JsonNode snapshot,List<ConversationMessage> messages,
            io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator.Evaluation evaluated) {
        validateResult(task,snapshot,messages,evaluated,prepare(task,snapshot));
    }
    public void validateResult(InspectionTask task,JsonNode snapshot,List<ConversationMessage> messages,
            io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator.Evaluation evaluated,Prepared prepared) {
        if(!hasSnapshot(snapshot))return;
        io.github.opensabre.iqc.quality.evaluation.CalibrationRuntime.verify(task,evaluated==null?null:evaluated.run(),json);
        if(prepared==null || !Objects.equals(prepared.taskId,task.getId()) || !Objects.equals(prepared.snapshotJson,task.getRuleSnapshotJson()))
            throw IqcException.invalidState("生产结果不属于已核验的冻结任务");
        if(messages==null || messages.isEmpty() || evaluated==null || evaluated.mode()!=io.github.opensabre.iqc.pipeline.PipelineRuntime.Mode.PRODUCTION)
            throw IqcException.invalidState("生产冻结输入结果模式无效");
        String id=messages.getFirst().getConversationId();var frozen=prepared.originalMessages(id);
        if(frozen==null || !json.valueToTree(frozen).equals(json.valueToTree(messages)))throw IqcException.invalidState("生产结果使用了不同的冻结输入");
        var input=json.createObjectNode();input.set("messages",json.valueToTree(frozen));input.set("businessContext",prepared.businessContext(id));
        if(!io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator.executionInputHash(prepared.releaseSchema(),input).equals(evaluated.inputHash()))
            throw IqcException.invalidState("生产执行输入摘要与冻结文本/上下文不一致");
    }
    /** Read-only decision context reuses the original persisted-input and result fingerprint checks. */
    public static JsonNode validatedBusinessContext(InspectionTask task,JsonNode snapshot,
            io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator.Evaluation trace,String conversationId,ObjectMapper json) {
        var validator=new ProductionInputSnapshotService(null,null,null,json);
        var prepared=validator.prepare(task,snapshot);
        if(prepared==null)return null;
        validator.validateResult(task,snapshot,prepared.messages().get(conversationId),trace,prepared);
        return prepared.businessContext(conversationId);
    }
    /** Full frozen input for human evidence edits; no current archive lookup or synthetic legacy capture. */
    public static JsonNode validatedExecutionInput(InspectionTask task,JsonNode snapshot,
            io.github.opensabre.iqc.pipelinebridge.PipelineConversationEvaluator.Evaluation trace,String conversationId,ObjectMapper json) {
        var validator=new ProductionInputSnapshotService(null,null,null,json);
        var prepared=validator.prepare(task,snapshot);
        if(prepared==null)throw IqcException.invalidState("该历史执行缺少冻结输入，不能新增人工事实证据");
        var frozen=prepared.originalMessages(conversationId);
        validator.validateResult(task,snapshot,frozen,trace,prepared);
        var input=json.createObjectNode();input.set("messages",json.valueToTree(frozen));
        input.set("businessContext",prepared.businessContext(conversationId));return input;
    }
    public Prepared prepare(InspectionTask task,JsonNode snapshot){
        if(!hasSnapshot(snapshot))return null;
        if(task==null)throw IqcException.invalidState("生产冻结输入缺少任务");
        try{
            // Decode the persisted original rather than a caller's double-based tree: upstream decimal context is exact.
            var original=exact().readTree(task.getRuleSnapshotJson());
            if(!hasSnapshot(original))throw IqcException.invalidState("任务缺少原始生产冻结输入");
            var envelope=original.path("productionInputs");var body=envelope.path("conversations");
            if(!"PIPELINE".equals(original.path("executionStrategy").path("mode").asText()) || original.has("evaluationSnapshot")
                    || !Set.of("PIPELINE","PIPELINE_TRIAL").contains(task.getTaskType())
                    || !Set.of(SCHEMA,ALIGNED_SCHEMA,AUDIO_SCHEMA).contains(envelope.path("schemaVersion").asText()) || !"selector-decimal-sha256-v1".equals(envelope.path("hashAlgorithm").asText())
                    || !body.isArray() || body.size()>1000 || envelope.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES
                    || !SelectorAssetService.hash(body.toString()).equals(envelope.path("contentHash").asText()))
                throw IqcException.invalidState("生产冻结输入协议或摘要无效");
            var mapper=exact();List<Input> values=mapper.readValue(body.toString(),mapper.getTypeFactory().constructCollectionType(List.class,Input.class));
            if(!AUDIO_SCHEMA.equals(envelope.path("schemaVersion").asText()) && values.stream().anyMatch(input->input.audio()!=null))
                throw IqcException.invalidState("旧生产协议不能携带音频身份");
            if(SCHEMA.equals(envelope.path("schemaVersion").asText()) && values.stream().flatMap(input->input.messages().stream())
                    .anyMatch(message->message.startMs()!=null || message.confidence()!=null))throw IqcException.invalidState("旧生产协议不能携带转写对齐字段");
            // Persisted integer tokens may decode as IntNode while Java Long fields serialize as LongNode.
            // Round-trip with the same exact parser before comparing shape and values; never use doubles.
            if(!exact().readTree(write(values)).equals(body))throw IqcException.invalidState("生产冻结输入不是规范表示");
            var ids=values.stream().map(Input::conversationId).toList();
            if(new HashSet<>(ids).size()!=ids.size() || !json.valueToTree(ids).equals(json.readTree(task.getConversationIdsJson())))
                throw IqcException.invalidState("生产冻结会话清单与任务不一致");
            int count=0;Set<String> messageIds=new HashSet<>();
            for(var input:values)for(var message:input.messages()){if(!messageIds.add(message.id()))throw IqcException.invalidState("生产冻结消息ID跨会话重复");count++;}
            if(count>100000 || task.getTotalMessages()==null || task.getTotalMessages()!=count
                    || (count==0 && !"NO_DATA".equals(task.getStatus())))throw IqcException.invalidState("生产冻结消息数与任务不一致");
            return new Prepared(values,task,original.path("pipelineSnapshot").path("release").path("schemaVersion")
                    .asText(io.github.opensabre.iqc.pipelineasset.PipelineRelease.SCHEMA));
        }catch(com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException failure){throw IqcException.invalidState("生产冻结输入结构无效");}
    }
}
