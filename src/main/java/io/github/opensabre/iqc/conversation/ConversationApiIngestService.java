package io.github.opensabre.iqc.conversation;

import io.github.opensabre.governance.usage.UsageCounterRecorder;
import io.github.opensabre.governance.usage.UsageOutcome;
import io.github.opensabre.governance.usage.UsageRecord;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.governance.IqcException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Reuses API import validation, legacy canonical fingerprinting and metering for all structured ingress. */
@Service @RequiredArgsConstructor
public class ConversationApiIngestService {
    private static final long MAXIMUM_BYTES=16L*1024*1024;
    private final ConversationImportService importService;
    private final UsageCounterRecorder usageCounterRecorder;

    private record Prepared(ConversationMetadata metadata,ConversationParseResult parsed,String fingerprint) { }
    public void validate(ApiConversationInput request){prepare(request,null);}
    private Prepared prepare(ApiConversationInput request,String batchOverride) {
        if(request==null || request.messages()==null || request.messages().isEmpty())throw IqcException.invalidArgument("messages 不能为空");
        if(request.messages().size()>5000)throw IqcException.invalidArgument("单个会话最多 5000 条消息");
        sized("externalId",request.externalId(),128);sized("title",request.title(),255);
        sized("batchNo",request.batchNo(),64);sized("batchOverride",batchOverride,64);
        long total=bytes(request.externalId())+bytes(request.title())+bytes(request.batchNo())+bytes(batchOverride);
        ConversationMetadata metadata=request.metadata();
        if(metadata!=null){
            total+=bytes(metadata.toString());requireBudget(total);
            metadata=metadata.normalized();
            for(var tag:metadata.tags())sized("tag",tag,128);
            if(metadata.businessFields()!=null)importService.serializeFields(metadata.businessFields());
        }
        List<ConversationMessageDraft> messages=new ArrayList<>();
        // Preserve the original API hash: raw external ID, raw role/content, normalized LocalTime and metadata fingerprintPart.
        StringBuilder canonical=new StringBuilder(request.externalId()==null?"":request.externalId());
        for(int index=0;index<request.messages().size();index++) {
            var item=request.messages().get(index);
            if(item==null || item.role()==null || item.role().isBlank() || item.content()==null || item.content().isBlank())
                throw IqcException.invalidArgument("第 "+(index+1)+" 条消息缺少 role 或 content");
            sized("role",item.role(),128);sized("canonical role",ConversationSpeakerRole.canonical(item.role()),32);sized("time",item.time(),32);
            int contentBytes=bytes(item.content());
            if(contentBytes>65535)throw IqcException.invalidArgument("单条消息 content 超过65535 UTF-8字节");
            total+=contentBytes+bytes(item.role())+bytes(item.time());requireBudget(total);
            LocalTime time;
            try{time=item.time()==null || item.time().isBlank()?LocalTime.MIDNIGHT.plusSeconds(index):LocalTime.parse(item.time());}
            catch(RuntimeException exception){throw IqcException.invalidArgument("第 "+(index+1)+" 条消息 time 格式应为 HH:mm:ss");}
            canonical.append('|').append(item.role()).append('|').append(time).append('|').append(item.content());
            messages.add(new ConversationMessageDraft(index+1,ConversationSpeakerRole.canonical(item.role()),time,item.content().trim(),item.content(),index+1));
        }
        if(metadata!=null)canonical.append('|').append(metadata.fingerprintPart());
        byte[] canonicalBytes=canonical.toString().getBytes(StandardCharsets.UTF_8);requireBudget(canonicalBytes.length);
        return new Prepared(metadata,new ConversationParseResult(messages,List.of(),0),fingerprint(canonicalBytes));
    }
    public Conversation ingest(ApiConversationInput request,String batchOverride) {
        var prepared=prepare(request,batchOverride);String fingerprint=prepared.fingerprint(),usageRecordId="conversation-ingest:"+fingerprint;
        record(usageRecordId,fingerprint,UsageOutcome.ATTEMPT);
        var parsed=prepared.parsed();var metadata=prepared.metadata();
        try {
            var conversation=importService.persistVerified(request.title()==null || request.title().isBlank()?"API会话":request.title().trim(),
                    fingerprint,parsed,batchOverride==null?request.batchNo():batchOverride,"API",request.externalId(),metadata);
            record(usageRecordId,fingerprint,UsageOutcome.SUCCESS);return conversation;
        }catch(RuntimeException exception){record(usageRecordId,fingerprint,UsageOutcome.FAILURE);throw exception;}
    }
    private void record(String id,String fingerprint,UsageOutcome outcome){
        String suffix=outcome==UsageOutcome.ATTEMPT?":attempt":outcome==UsageOutcome.SUCCESS?":success":":failure";
        usageCounterRecorder.record(new UsageRecord(id+suffix,null,"iqc-platform","CONVERSATION_API",fingerprint,"INGEST",outcome));
    }
    private static int bytes(String value){return value==null?0:value.getBytes(StandardCharsets.UTF_8).length;}
    private static void sized(String field,String value,int maximum){if(value!=null && value.length()>maximum)throw IqcException.invalidArgument(field+" 超过长度限制 "+maximum);}
    private static void requireBudget(long bytes){if(bytes>MAXIMUM_BYTES)throw IqcException.invalidArgument("单个API会话超过16MiB");}
    private static String fingerprint(byte[] content){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));}catch(NoSuchAlgorithmException exception){throw new IllegalStateException("SHA-256 is unavailable",exception);}}
}
