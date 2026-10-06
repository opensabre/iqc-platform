package io.github.opensabre.iqc.selector;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.conversation.dao.ConversationMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.label.dao.InspectionLabelResultMapper;
import io.github.opensabre.iqc.label.model.InspectionLabelResult;
import io.github.opensabre.iqc.pipelineasset.PipelineAssetService;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper.SelectorSource;
import io.github.opensabre.iqc.shared.IqcDataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Complete, bounded authorized corpus; no early LIMIT masquerading as fair full-pool sampling. */
@Service @RequiredArgsConstructor
public class ConversationSelectionService {
    public static final int MAX_CANDIDATES=20000;
    private final ConversationMapper conversations;
    private final ConversationInspectionResultMapper results;
    private final InspectionLabelResultMapper labels;
    private final IqcDataScope scope;
    private final ObjectMapper json;
    private volatile com.fasterxml.jackson.databind.ObjectReader exactReader;
    public record ResolvedWindow(SelectorDefinition.TimeField field,LocalDateTime from,LocalDateTime to,String zoneId,String storageZoneId) { }
    public record Distribution(Map<String,Integer> employees,Map<String,Integer> teams,Map<String,Integer> departments) { }
    public record PreviewConversation(String conversationId,String fileName,String employeeId,String employeeGroupId,
                                      String employeeDepartmentId,String channel,LocalDateTime startedTime,String riskLevel) { }
    public record Preview(String schemaVersion,Instant anchor,ResolvedWindow resolvedWindow,int candidateCount,int matchedCount,
                          int sampledCount,int selectedCount,boolean limited,Distribution matchedDistribution,
                          Distribution selectedDistribution,List<PreviewConversation> preview,String definitionHash) { }
    public record SelectedEvidence(String conversationId,JsonNode metadata,String metadataHash,String resultId,
                                   String executionId,String sourceOwnerGroupId,Map<String,String> labelResultIds,Map<String,JsonNode> values) { }
    public record SelectionScope(boolean all,String owner,String groupId) { }
    public record Selection(String schemaVersion,SelectorDefinition definition,String definitionHash,Instant anchor,SelectionScope sourceScope,String evidenceGroupId,
                            ResolvedWindow resolvedWindow,int candidateCount,int matchedCount,int sampledCount,
                            boolean limited,Distribution matchedDistribution,Distribution selectedDistribution,
                            List<String> selectedConversationIds,List<SelectedEvidence> selectedEvidence) { }
    private record Candidate(Conversation conversation,Map<String,JsonNode> values,SelectorSource result,
                             Map<String,String> labelResultIds) { }
    private enum Truth { TRUE,FALSE,UNKNOWN }
    private static final Set<String> STRING_FIELDS=Set.of("id","employeeId","employeeGroupId","employeeDepartmentId","channel","businessType",
            "businessNo","sourceType","externalId","fileName","status","asrStatus","customerLevel","riskLevel");
    private static final Set<String> NUMBER_FIELDS=Set.of("durationSeconds","messageCount","errorCount");
    private static final Set<String> TIME_FIELDS=Set.of("startedTime","endedTime","createdTime");

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Preview preview(SelectorDefinition definition,int previewSize) {
        if(previewSize<1 || previewSize>50) throw IqcException.invalidArgument("随机预览数量必须在1到50之间");
        var selection=select(definition);
        var evidence=selection.selectedEvidence().stream().sorted(Comparator.comparing(e->sampleKey(definition.sampling().seed()+":preview",e.conversationId())))
                .limit(previewSize).map(e->new PreviewConversation(e.conversationId(),text(e.values(),"fileName"),text(e.values(),"employeeId"),
                        text(e.values(),"employeeGroupId"),text(e.values(),"employeeDepartmentId"),text(e.values(),"channel"),localTime(e.values().get("startedTime")),text(e.values(),"riskLevel"))).toList();
        return new Preview("iqc-selector-preview-v1",selection.anchor(),selection.resolvedWindow(),selection.candidateCount(),
                selection.matchedCount(),selection.sampledCount(),selection.selectedConversationIds().size(),selection.limited(),
                selection.matchedDistribution(),selection.selectedDistribution(),evidence,selection.definitionHash());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Selection materialize(SelectorDefinition definition) {return select(definition);}
    /** Internal periodic trigger boundary: relative windows are anchored to the immutable planned tick. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Selection materializeAt(SelectorDefinition definition,Instant plannedAt) {
        if(plannedAt==null)throw IqcException.invalidArgument("周期选数缺少计划时刻");
        return select(definition,plannedAt);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Selection materializeTargetsAt(SelectorDefinition definition,Instant plannedAt,List<String> targets) {
        if(plannedAt==null || targets==null || targets.isEmpty() || targets.size()>1000
                || targets.stream().anyMatch(id->id==null || !id.matches("[A-Za-z0-9:_-]{1,64}")))
            throw IqcException.invalidArgument("事件选数必须提供明确的有效目标会话");
        return select(definition,plannedAt,targets.stream().distinct().toList());
    }
    public void validate(SelectorDefinition definition) {
        if(definition==null) throw IqcException.invalidArgument("缺少Selector定义");
        if(json.valueToTree(definition).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>128*1024) throw IqcException.invalidArgument("Selector超过128KiB");
        int[] count={0};validateCondition(definition.condition(),0,count);validateCondition(definition.excludeCondition(),0,count);
    }
    private Selection select(SelectorDefinition definition) {
        return select(definition,Instant.now());
    }
    private Selection select(SelectorDefinition definition,Instant plannedAt) {return select(definition,plannedAt,null);}
    private Selection select(SelectorDefinition definition,Instant plannedAt,List<String> targets) {
        validate(definition);
        Instant anchor=plannedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);var window=resolve(definition.timeWindow(),anchor);
        boolean all=scope.canViewAll();String owner=scope.owner(),group=all?null:scope.groupId();
        var query=Wrappers.<Conversation>lambdaQuery().in(Conversation::getStatus,List.of("IMPORTED","IMPORTED_WITH_ERRORS")).gt(Conversation::getMessageCount,0);
        if(!all) query.and(q->q.eq(Conversation::getCreatedBy,owner).or(group!=null,n->n.eq(Conversation::getOwnerGroupId,group)));
        if(targets!=null)query.in(Conversation::getId,targets);
        if(!definition.includeIds().isEmpty()) query.in(Conversation::getId,definition.includeIds());
        if(!definition.excludeIds().isEmpty()) query.notIn(Conversation::getId,definition.excludeIds());
        if(window!=null) {
            if(window.field()==SelectorDefinition.TimeField.CREATED) query.ge(Conversation::getCreatedTime,Date.from(window.from().atZone(ZoneId.of("Asia/Shanghai")).toInstant()))
                    .lt(Conversation::getCreatedTime,Date.from(window.to().atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
            else query.ge(Conversation::getStartedTime,window.from()).lt(Conversation::getStartedTime,window.to());
        }
        long candidateCount=conversations.selectCount(query);
        if(candidateCount>MAX_CANDIDATES) throw IqcException.invalidArgument("授权候选池超过20000，请缩小Selector时间窗或include范围；未截断抽样");
        if(conversations.selectSelectorPayloadBytes(query)>64L*1024*1024) throw IqcException.invalidArgument("Selector候选元数据超过64MiB，请缩小范围");
        var corpus=conversations.selectList(query.orderByAsc(Conversation::getCreatedTime).orderByAsc(Conversation::getId).last("LIMIT 20001"));
        if(corpus.size()>MAX_CANDIDATES) throw IqcException.invalidArgument("授权候选池超过20000，请缩小Selector时间窗或include范围；未截断抽样");
        var fields=new LinkedHashSet<String>();collectFields(definition.condition(),fields);collectFields(definition.excludeCondition(),fields);
        boolean needResult=fields.contains("riskLevel") || fields.stream().anyMatch(f->f.startsWith("label:"))
                || definition.sampling().mode()==SelectorDefinition.SamplingMode.RISK_PRIORITY;
        var sources=new HashMap<String,SelectorSource>();var labelValues=new HashMap<String,Map<String,JsonNode>>();
        var labelIds=new HashMap<String,Map<String,String>>();long totalLabelBytes=0,totalLabelRows=0;
        if(needResult) for(int start=0;start<corpus.size();start+=500) {
            var ids=corpus.subList(start,Math.min(corpus.size(),start+500)).stream().map(Conversation::getId).toList();
            var sourceRows=results.selectLatestVisibleForSelector(ids,all,owner,group);
            sourceRows.forEach(row->sources.put(row.getConversationId(),row));
            if(fields.stream().noneMatch(f->f.startsWith("label:")) || sourceRows.isEmpty()) continue;
            var labelQuery=Wrappers.<InspectionLabelResult>lambdaQuery().in(InspectionLabelResult::getConversationResultId,
                    sourceRows.stream().map(SelectorSource::getId).toList());
            var requestedLabels=fields.stream().filter(f->f.startsWith("label:")).toList();
            labelQuery.and(q->{for(String field:requestedLabels){String[] key=field.split(":",3);
                q.or(n->n.eq(InspectionLabelResult::getLabelId,key[1]).eq(InspectionLabelResult::getValueCode,key[2]));}});
            long labelBytes=labels.selectSelectorPayloadBytes(labelQuery),labelCount=labels.selectCount(labelQuery);
            totalLabelBytes+=labelBytes;totalLabelRows+=labelCount;
            if(labelBytes>16L*1024*1024 || totalLabelBytes>32L*1024*1024 || totalLabelRows>200000)
                throw IqcException.invalidArgument("Selector标签源单批超过16MiB或累计超过32MiB/200000行，请缩小范围");
            var labelRows=labels.selectList(labelQuery.orderByAsc(InspectionLabelResult::getId).last("LIMIT 50001"));
            if(labelRows.size()>50000) throw IqcException.invalidState("Selector标签源超过安全上限");
            for(var row:labelRows) {
                String field="label:"+row.getLabelId()+":"+row.getValueCode();if(!fields.contains(field)) continue;
                var node=parse(row.getValueJson(),"既有标签值");
                if(!"iqc-label-result-v2".equals(node.path("schemaVersion").asText()) || !"KNOWN".equals(node.path("status").asText())) continue;
                var value=node.get("value");if(value==null || value.isNull()) continue;
                var values=labelValues.computeIfAbsent(row.getConversationResultId(),ignored->new HashMap<>());
                if(values.putIfAbsent(field,value.deepCopy())!=null) throw IqcException.invalidState("Selector标签源值重复");
                labelIds.computeIfAbsent(row.getConversationResultId(),ignored->new TreeMap<>()).put(field,row.getId());
            }
        }
        var matched=new ArrayList<Candidate>();
        for(var conversation:corpus) {
            var source=sources.get(conversation.getId());var values=values(conversation,source);
            if(source!=null) values.putAll(labelValues.getOrDefault(source.getId(),Map.of()));
            var candidate=new Candidate(conversation,values,source,source==null?Map.of():labelIds.getOrDefault(source.getId(),Map.of()));
            if(evaluate(definition.condition(),values)==Truth.TRUE && (definition.excludeCondition()==null
                    || evaluate(definition.excludeCondition(),values)==Truth.FALSE)) matched.add(candidate);
        }
        var sampled=sample(matched,definition.sampling());boolean limited=sampled.size()>definition.maximumCount();
        var selected=sampled.stream().limit(definition.maximumCount()).toList();
        var evidence=selected.stream().map(c->{var metadata=json.valueToTree(new TreeMap<>(c.values()));return new SelectedEvidence(c.conversation().getId(),metadata,
                PipelineAssetService.hash(metadata.toString()),c.result()==null?null:c.result().getId(),c.result()==null?null:c.result().getExecutionId(),c.result()==null?null:c.result().getSourceOwnerGroupId(),
                Collections.unmodifiableMap(new TreeMap<>(c.labelResultIds())),Collections.unmodifiableMap(new TreeMap<>(c.values())));}).toList();
        String commonGroup=corpus.isEmpty()?null:corpus.getFirst().getOwnerGroupId();
        if(commonGroup!=null) {
            String expectedGroup=commonGroup;
            if(corpus.stream().anyMatch(c->!expectedGroup.equals(c.getOwnerGroupId()))
                    || sources.values().stream().anyMatch(r->!expectedGroup.equals(r.getSourceOwnerGroupId()))) commonGroup=null;
        }
        return new Selection("iqc-selector-selection-v1",definition,PipelineAssetService.hash(json.valueToTree(definition).toString()),anchor,
                new SelectionScope(all,owner,group),commonGroup,window,
                corpus.size(),matched.size(),sampled.size(),limited,distribution(matched),distribution(selected),
                selected.stream().map(c->c.conversation().getId()).toList(),evidence);
    }
    private ResolvedWindow resolve(SelectorDefinition.TimeWindow input,Instant anchor) {
        if(input==null) return null;
        var zone=ZoneId.of(input.zoneId());var now=anchor.atZone(zone);ZonedDateTime from,to;
        switch(input.mode()) {
            case ABSOLUTE -> {from=input.from().atZone(zone);to=input.to().atZone(zone);}
            case LAST_HOURS -> {from=now.minusHours(input.hours());to=now;}
            case PREVIOUS_DAY -> {to=now.toLocalDate().atStartOfDay(zone);from=now.toLocalDate().minusDays(1).atStartOfDay(zone);}
            case PREVIOUS_WEEK -> {var monday=now.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                to=monday.atStartOfDay(zone);from=monday.minusWeeks(1).atStartOfDay(zone);}
            default -> throw IqcException.invalidArgument("无效时间窗");
        }
        if(!from.toInstant().isBefore(to.toInstant())) throw IqcException.invalidArgument("时区解析后时间窗必须递增");
        var storageZone=ZoneId.of("Asia/Shanghai");
        return new ResolvedWindow(input.field(),ceilMillis(from.toInstant()).atZone(storageZone).toLocalDateTime(),
                ceilMillis(to.toInstant()).atZone(storageZone).toLocalDateTime(),input.zoneId(),storageZone.getId());
    }
    /** Date query parameters have millisecond precision; ceiling preserves [from,to) over stored second-resolution instants. */
    private Instant ceilMillis(Instant input) {
        var floor=input.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        return floor.equals(input)?floor:floor.plusMillis(1);
    }
    private Map<String,JsonNode> values(Conversation c,SelectorSource source) {
        var values=new TreeMap<String,JsonNode>();
        put(values,"id",c.getId());put(values,"employeeId",c.getEmployeeId());put(values,"employeeGroupId",c.getEmployeeGroupId());put(values,"employeeDepartmentId",c.getEmployeeDepartmentId());
        put(values,"channel",c.getChannel());put(values,"businessType",c.getBusinessType());put(values,"businessNo",c.getBusinessNo());
        put(values,"sourceType",c.getSourceType());put(values,"externalId",c.getExternalId());put(values,"fileName",c.getSourceFileName());put(values,"status",c.getStatus());
        put(values,"asrStatus",c.getAsrStatus());put(values,"customerLevel",c.getCustomerLevel());put(values,"complaint",c.getComplaint());
        put(values,"messageCount",c.getMessageCount());put(values,"errorCount",c.getErrorCount());
        put(values,"startedTime",c.getStartedTime()==null?null:c.getStartedTime().toString());put(values,"endedTime",c.getEndedTime()==null?null:c.getEndedTime().toString());
        put(values,"createdTime",c.getCreatedTime()==null?null:c.getCreatedTime().toInstant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime().toString());
        if(c.getStartedTime()!=null && c.getEndedTime()!=null && !c.getEndedTime().isBefore(c.getStartedTime()))
            put(values,"durationSeconds",BigDecimal.valueOf(Duration.between(c.getStartedTime(),c.getEndedTime()).toMillis(),3));
        if(c.getTagsJson()!=null) {var tags=parse(c.getTagsJson(),"会话标签");if(!tags.isArray()) throw IqcException.invalidState("会话标签必须为数组");values.put("tags",tags);}
        if(c.getBusinessFieldsJson()!=null) {var business=parse(c.getBusinessFieldsJson(),"会话自定义字段");if(!business.isObject()) throw IqcException.invalidState("会话自定义字段必须为对象");business.fields().forEachRemaining(e->{if(!e.getValue().isNull()) values.put("business:"+e.getKey(),e.getValue());});}
        if(source!=null) put(values,"riskLevel",source.getRiskLevel());
        return values;
    }
    private void put(Map<String,JsonNode> values,String key,Object value){if(value!=null)values.put(key,json.valueToTree(value));}
    private com.fasterxml.jackson.databind.ObjectReader exactReader() {
        var reader=exactReader;
        if(reader==null) synchronized(this) {
            reader=exactReader;
            if(reader==null) exactReader=reader=json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true)).readerFor(JsonNode.class);
        }
        return reader;
    }
    private JsonNode parse(String body,String field) {try{var result=exactReader().readTree(body);if(result==null)throw IqcException.invalidState(field+"为空");return result;}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw IqcException.invalidState(field+"JSON无效");}}
    private void collectFields(SelectorDefinition.Condition c,Set<String> fields){if(c==null)return;if(c.junction()!=null)c.children().forEach(n->collectFields(n,fields));else fields.add(c.field());}
    private void validateCondition(SelectorDefinition.Condition c,int depth,int[] count) {
        if(c==null) return;
        if(depth>8 || ++count[0]>100) throw IqcException.invalidArgument("Selector条件树最多8层/100节点");
        if(c.junction()!=null) {
            if(c.children().isEmpty() || c.children().size()>50 || c.field()!=null || c.operator()!=null || c.value()!=null) throw IqcException.invalidArgument("条件组必须只有AND/OR与非空children");
            c.children().forEach(n->validateCondition(n,depth+1,count));return;
        }
        String field=c.field();var value=c.value();
        if(!c.children().isEmpty() || field==null || c.operator()==null || !(STRING_FIELDS.contains(field)||NUMBER_FIELDS.contains(field)||TIME_FIELDS.contains(field)
                || field.equals("tags") || field.equals("complaint") || field.matches("business:[A-Za-z][A-Za-z0-9_]{0,63}")
                || field.matches("label:[A-Za-z0-9_-]{1,64}:[A-Za-z0-9_-]{1,64}"))) throw IqcException.invalidArgument("Selector字段或叶条件无效");
        if(c.operator()==SelectorDefinition.Operator.EXISTS) {if(value==null || !value.isBoolean())throw IqcException.invalidArgument("EXISTS需显式布尔值");return;}
        if(field.equals("tags")) {if(c.operator()!=SelectorDefinition.Operator.CONTAINS || value==null || !value.isTextual() || value.asText().isBlank())throw IqcException.invalidArgument("tags仅支持CONTAINS字符串或EXISTS");return;}
        if(c.operator()==SelectorDefinition.Operator.CONTAINS) throw IqcException.invalidArgument("CONTAINS仅适用于tags");
        if(c.operator()==SelectorDefinition.Operator.IN || c.operator()==SelectorDefinition.Operator.NOT_IN) {
            if(value==null || !value.isArray() || value.isEmpty() || value.size()>100)throw IqcException.invalidArgument("IN/NOT_IN需1到100个值");
            for(var item:value)validateValue(field,item);return;
        }
        validateValue(field,value);
        if(Set.of(SelectorDefinition.Operator.GT,SelectorDefinition.Operator.GE,SelectorDefinition.Operator.LT,SelectorDefinition.Operator.LE).contains(c.operator())
                && !NUMBER_FIELDS.contains(field) && !TIME_FIELDS.contains(field) && !((field.startsWith("business:")||field.startsWith("label:"))&&value.isNumber()))
            throw IqcException.invalidArgument("大小比较仅支持数值/时间字段");
    }
    private void validateValue(String field,JsonNode value) {
        if(value==null || value.isNull() || !(value.isTextual()||value.isNumber()||value.isBoolean()) || value.toString().length()>4096)
            throw IqcException.invalidArgument("Selector比较值必须是有效标量");
        if(STRING_FIELDS.contains(field) && !value.isTextual() || NUMBER_FIELDS.contains(field) && !value.isNumber()
                || field.equals("complaint") && !value.isBoolean())throw IqcException.invalidArgument("Selector比较值类型与字段不符");
        if(TIME_FIELDS.contains(field)) {if(!value.isTextual())throw IqcException.invalidArgument("时间比较需ISO本地时间");localTime(value);}
    }
    private Truth evaluate(SelectorDefinition.Condition c,Map<String,JsonNode> values) {
        if(c==null)return Truth.TRUE;
        if(c.junction()!=null) {
            boolean unknown=false;
            for(var child:c.children()) {var result=evaluate(child,values);if(c.junction()==SelectorDefinition.Junction.AND&&result==Truth.FALSE)return Truth.FALSE;
                if(c.junction()==SelectorDefinition.Junction.OR&&result==Truth.TRUE)return Truth.TRUE;unknown|=result==Truth.UNKNOWN;}
            return unknown?Truth.UNKNOWN:c.junction()==SelectorDefinition.Junction.AND?Truth.TRUE:Truth.FALSE;
        }
        var actual=values.get(c.field());var expected=c.value();
        if(c.operator()==SelectorDefinition.Operator.EXISTS)return (actual!=null && !actual.isNull())==expected.asBoolean()?Truth.TRUE:Truth.FALSE;
        if(actual==null || actual.isNull())return Truth.UNKNOWN;
        boolean matched;
        switch(c.operator()) {
            case EQ -> {if(!sameType(actual,expected))return Truth.UNKNOWN;matched=equal(actual,expected,c.field());}
            case NE -> {if(!sameType(actual,expected))return Truth.UNKNOWN;matched=!equal(actual,expected,c.field());}
            case IN,NOT_IN -> {boolean comparable=false,found=false;for(var item:expected){comparable|=sameType(actual,item);found|=equal(actual,item,c.field());}if(!comparable)return Truth.UNKNOWN;matched=c.operator()==SelectorDefinition.Operator.IN?found:!found;}
            case CONTAINS -> {matched=false;if(actual.isArray())for(var item:actual)matched|=equal(item,expected,c.field());else return Truth.UNKNOWN;}
            case GT,GE,LT,LE -> {int cmp;if(actual.isNumber()&&expected.isNumber())cmp=actual.decimalValue().compareTo(expected.decimalValue());
                else if(TIME_FIELDS.contains(c.field())&&actual.isTextual()&&expected.isTextual())cmp=localTime(actual).compareTo(localTime(expected));else return Truth.UNKNOWN;
                matched=switch(c.operator()){case GT->cmp>0;case GE->cmp>=0;case LT->cmp<0;default->cmp<=0;};}
            default -> throw IqcException.invalidArgument("无效Selector操作符");
        }
        return matched?Truth.TRUE:Truth.FALSE;
    }
    private boolean sameType(JsonNode a,JsonNode b){return a.isNumber()&&b.isNumber()||a.isTextual()&&b.isTextual()||a.isBoolean()&&b.isBoolean();}
    private boolean equal(JsonNode a,JsonNode b,String field){return sameType(a,b)&&(a.isNumber()?a.decimalValue().compareTo(b.decimalValue())==0:
            TIME_FIELDS.contains(field)?localTime(a).equals(localTime(b)):a.equals(b));}
    private List<Candidate> sample(List<Candidate> matched,SelectorDefinition.Sampling sampling) {
        var randomOrder=Comparator.comparing((Candidate c)->sampleKey(sampling.seed(),c.conversation().getId()));
        int size=matched.size();int count=sampling.count()==null?size:Math.min(size,sampling.count());
        return switch(sampling.mode()) {
            case ALL -> List.copyOf(matched);
            case FIXED_COUNT -> matched.stream().limit(count).toList();
            case RANDOM -> matched.stream().sorted(randomOrder).limit(count).toList();
            case RATIO -> matched.stream().sorted(randomOrder).limit(BigDecimal.valueOf(size).multiply(sampling.ratio()).setScale(0,RoundingMode.CEILING).longValueExact()).toList();
            case EMPLOYEE_UNIFORM -> uniform(matched,count,sampling.seed(),c->bucket(c.conversation().getEmployeeId()));
            case TEAM_STRATIFIED -> stratified(matched,count,sampling.seed(),c->bucket(c.conversation().getEmployeeGroupId()));
            case RISK_PRIORITY -> matched.stream().sorted(Comparator.comparingInt((Candidate c)->riskPriority(text(c.values(),"riskLevel"))).reversed().thenComparing(randomOrder)).limit(count).toList();
        };
    }
    private List<Candidate> uniform(List<Candidate> input,int count,String seed,Function<Candidate,String> bucket) {
        var groups=groups(input,seed,bucket);var output=new ArrayList<Candidate>();int index=0;
        while(output.size()<count){boolean added=false;for(var group:groups.values()){if(index<group.size()){output.add(group.get(index));added=true;if(output.size()==count)break;}}if(!added)break;index++;}
        return List.copyOf(output);
    }
    private List<Candidate> stratified(List<Candidate> input,int count,String seed,Function<Candidate,String> bucket) {
        if(input.isEmpty())return List.of();var groups=groups(input,seed,bucket);var quotas=new HashMap<String,Integer>();
        int allocated=0;for(var entry:groups.entrySet()){int quota=(int)((long)count*entry.getValue().size()/input.size());quotas.put(entry.getKey(),quota);allocated+=quota;}
        var remainderOrder=groups.keySet().stream().sorted(Comparator.comparingLong((String key)->(long)count*groups.get(key).size()%input.size()).reversed()
                .thenComparing(key->sampleKey(seed,key))).toList();
        for(int i=0;i<count-allocated;i++)quotas.compute(remainderOrder.get(i),(key,value)->value+1);
        var selected=new ArrayList<Candidate>();groups.forEach((key,group)->selected.addAll(group.subList(0,quotas.get(key))));
        return selected.stream().sorted(Comparator.comparing(c->sampleKey(seed,c.conversation().getId()))).toList();
    }
    private LinkedHashMap<String,List<Candidate>> groups(List<Candidate> input,String seed,Function<Candidate,String> bucket) {
        var unordered=input.stream().collect(Collectors.groupingBy(bucket));var groups=new LinkedHashMap<String,List<Candidate>>();
        unordered.keySet().stream().sorted(Comparator.comparing(key->sampleKey(seed,key))).forEach(key->groups.put(key,unordered.get(key).stream()
                .sorted(Comparator.comparing(c->sampleKey(seed,c.conversation().getId()))).toList()));return groups;
    }
    private Distribution distribution(List<Candidate> candidates) {
        var employees=new TreeMap<String,Integer>();var teams=new TreeMap<String,Integer>();var departments=new TreeMap<String,Integer>();
        candidates.forEach(c->{employees.merge(bucket(c.conversation().getEmployeeId()),1,Integer::sum);teams.merge(bucket(c.conversation().getEmployeeGroupId()),1,Integer::sum);departments.merge(bucket(c.conversation().getEmployeeDepartmentId()),1,Integer::sum);});
        return new Distribution(Collections.unmodifiableMap(employees),Collections.unmodifiableMap(teams),Collections.unmodifiableMap(departments));
    }
    private String bucket(String value){return value==null||value.isBlank()?"missing:":"id:"+value;}
    private int riskPriority(String level){if(level==null)return 0;return switch(level){case "CRITICAL"->5;case "HIGH"->4;case "MEDIUM"->3;case "LOW"->2;case "NONE"->1;default->0;};}
    private String sampleKey(String seed,String id){return PipelineAssetService.hash(json.createArrayNode().add(seed).add(id).toString());}
    private static String text(Map<String,JsonNode> values,String key){var node=values.get(key);return node==null?null:node.asText();}
    private static LocalDateTime localTime(JsonNode value){if(value==null)return null;try{return LocalDateTime.parse(value.asText());}catch(java.time.format.DateTimeParseException invalid){throw IqcException.invalidArgument("Selector时间需ISO本地格式");}}
}
