package io.github.opensabre.iqc.privacy;

import io.github.opensabre.boot.sensitive.rule.DefaultSensitiveRule;
import io.github.opensabre.iqc.conversation.model.Conversation;
import io.github.opensabre.iqc.conversation.model.ConversationMessage;
import io.github.opensabre.iqc.governance.IqcException;
import java.util.*;
import org.springframework.beans.BeanUtils;

/** Explicit conversation presentation; original entities, business JSON and source fingerprints are never edited. */
public final class ConversationReadProjection {
    public enum Mode {PREVIEW,FULL,PII}
    private ConversationReadProjection() { }
    public static Conversation summary(Conversation source,boolean pii){
        var copy=new Conversation();BeanUtils.copyProperties(Objects.requireNonNull(source),copy);
        copy.setAudioSha256(null);copy.setAudioSizeBytes(null);copy.setAudioMediaType(null);copy.setAudioDurationMs(null);
        if(!pii){
            copy.setCustomerName(field(DefaultSensitiveRule.NAME,source.getCustomerName()));
            copy.setEmployeeName(field(DefaultSensitiveRule.NAME,source.getEmployeeName()));
            copy.setCustomerExternalId(field(DefaultSensitiveRule.ACCOUNT_NO,source.getCustomerExternalId()));
            copy.setExternalId(field(DefaultSensitiveRule.ACCOUNT_NO,source.getExternalId()));
            copy.setEmployeeId(field(DefaultSensitiveRule.ACCOUNT_NO,source.getEmployeeId()));
            copy.setBusinessNo(field(DefaultSensitiveRule.ACCOUNT_NO,source.getBusinessNo()));
            copy.setCustomerContactMasked(field(DefaultSensitiveRule.MOBILE,source.getCustomerContactMasked()));
            copy.setSourceFileName(field(DefaultSensitiveRule.CUSTOM,source.getSourceFileName()));
            // Unknown business JSON cannot be presented as safely classified PII by a text regex.
            copy.setBusinessFieldsJson(null);copy.setTagsJson(null);
        }
        return copy;
    }
    public static Map<String,Object> detail(Map<String,Object> original,SensitiveReadAccessService.Access access,Mode mode){
        if(original==null||access==null||mode==null||!(original.get("conversation") instanceof Conversation conversation)
                ||!(original.get("messages") instanceof List<?> source))throw IqcException.invalidState("会话读取投影来源无效");
        if(mode==Mode.FULL)access.requireFullConversation();if(mode==Mode.PII)access.requirePii();
        boolean pii=mode==Mode.PII,full=mode==Mode.FULL||pii&&access.fullConversation();
        var messages=new ArrayList<ConversationMessage>();
        for(int i=0;i<source.size()&&(full||i<3);i++){
            if(!(source.get(i) instanceof ConversationMessage message)||!Objects.equals(conversation.getId(),message.getConversationId()))
                throw IqcException.invalidState("会话消息投影跨来源");
            var copy=new ConversationMessage();BeanUtils.copyProperties(message,copy);
            String text=pii?message.getContent():text(message.getContent(),conversation);
            if(!full&&text!=null&&text.length()>200){int end=200;if(Character.isHighSurrogate(text.charAt(end-1))&&Character.isLowSurrogate(text.charAt(end)))end--;text=text.substring(0,end);}
            copy.setContent(text);if(!full||!pii)copy.setRawLine(null);messages.add(copy);
        }
        var metadata=new LinkedHashMap<String,Object>();metadata.put("policyVersion","iqc-conversation-read-v1");metadata.put("mode",mode.name());
        metadata.put("contentLimited",!full);metadata.put("piiMasked",!pii);metadata.put("visibleMessageCount",messages.size());metadata.put("totalMessageCount",source.size());
        metadata.put("canReadFull",access.fullConversation());metadata.put("canReadPii",access.pii());
        var withheld=new ArrayList<String>();if(!pii||!full){withheld.add("businessFieldsJson");withheld.add("tagsJson");}if(!full||!pii)withheld.add("rawLine");metadata.put("withheldFields",List.copyOf(withheld));
        var presented=summary(conversation,pii);if(!full){presented.setBusinessFieldsJson(null);presented.setTagsJson(null);}
        var result=new LinkedHashMap<String,Object>();result.put("conversation",presented);result.put("messages",List.copyOf(messages));result.put("privacy",metadata);return result;
    }
    private static String text(String value,Conversation source){
        if(value==null)return null;
        var known=new ArrayList<Map.Entry<String,String>>();
        add(known,source.getCustomerName(),DefaultSensitiveRule.NAME);add(known,source.getEmployeeName(),DefaultSensitiveRule.NAME);
        add(known,source.getCustomerExternalId(),DefaultSensitiveRule.ACCOUNT_NO);add(known,source.getEmployeeId(),DefaultSensitiveRule.ACCOUNT_NO);
        add(known,source.getBusinessNo(),DefaultSensitiveRule.ACCOUNT_NO);add(known,source.getExternalId(),DefaultSensitiveRule.ACCOUNT_NO);
        known.sort((left,right)->Integer.compare(right.getKey().length(),left.getKey().length()));
        String projected=value;for(var entry:known)projected=projected.replace(entry.getKey(),entry.getValue());
        return SensitiveTextProjection.mask(projected).text();
    }
    private static void add(List<Map.Entry<String,String>> entries,String value,DefaultSensitiveRule rule){
        if(value!=null&&!value.isEmpty())entries.add(Map.entry(value,field(rule,value)));
    }
    private static String field(DefaultSensitiveRule rule,String value){
        if(value==null||value.isEmpty())return value;String masked;
        try{masked=rule.replace(value);}catch(RuntimeException invalidRange){masked=null;}
        return masked==null||masked.length()!=value.length()||masked.equals(value)?"*".repeat(value.length()):masked;
    }
}
