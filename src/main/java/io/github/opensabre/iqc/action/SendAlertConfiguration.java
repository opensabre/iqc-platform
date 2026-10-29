package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.opensabre.iqc.shared.IqcNotificationClient;
import java.util.*;

/** Frozen declared notification arguments; actual template content is owned by notification service. */
public record SendAlertConfiguration(String sceneCode,String channel,String target,Map<String,String> args) {
    public static SendAlertConfiguration parse(JsonNode config) {
        if(config==null || !config.isObject())throw new IllegalArgumentException("SEND_ALERT requires an object");
        var fields=config.fieldNames();
        while(fields.hasNext())if(!Set.of("sceneCode","channel","target","args").contains(fields.next()))
            throw new IllegalArgumentException("Unknown SEND_ALERT configuration field");
        String scene=text(config,"sceneCode",64),channel=text(config,"channel",16),target=text(config,"target",255);
        if(!scene.matches("[A-Za-z0-9:_-]{1,64}") || !Set.of("SMS","EMAIL").contains(channel))
            throw new IllegalArgumentException("Invalid alert scene or channel");
        if(target.chars().anyMatch(c->Character.isWhitespace(c) || Character.isISOControl(c)))
            throw new IllegalArgumentException("Invalid alert recipient");
        if(channel.equals("SMS") && !target.matches("\\+?[0-9]{6,20}"))throw new IllegalArgumentException("Invalid SMS recipient");
        if(channel.equals("EMAIL") && (target.indexOf('@')<1 || target.indexOf('@')!=target.lastIndexOf('@') || target.endsWith("@")))
            throw new IllegalArgumentException("Invalid email recipient");
        var args=new TreeMap<String,String>();
        var values=config.get("args");
        if(values!=null) {
            if(!values.isObject() || values.size()>50)throw new IllegalArgumentException("Invalid alert arguments");
            values.fields().forEachRemaining(entry->{
                if(!entry.getKey().matches("[A-Za-z0-9_]{1,64}") || !entry.getValue().isTextual() || entry.getValue().asText().length()>1000)
                    throw new IllegalArgumentException("Invalid alert argument");
                args.put(entry.getKey(),entry.getValue().asText());
            });
        }
        return new SendAlertConfiguration(scene,channel,target,Collections.unmodifiableMap(args));
    }
    private static String text(JsonNode node,String field,int max) {
        var value=node.get(field);
        if(value==null || !value.isTextual() || value.asText().isBlank() || value.asText().length()>max)
            throw new IllegalArgumentException("Invalid SEND_ALERT "+field);
        return value.asText();
    }
    public IqcNotificationClient.Send send() { return new IqcNotificationClient.Send(target,sceneCode,channel,args); }
    /** Independent implementation of owning-service notification-declared-request-v1 protocol. */
    public String declaredHash() {
        try {
            var bytes=new java.io.ByteArrayOutputStream(); var output=new java.io.DataOutputStream(bytes);
            field(output,"notification-declared-request-v1");field(output,sceneCode);field(output,channel);field(output,target);
            output.writeInt(args.size());
            for(var entry:new TreeMap<>(args).entrySet()) {field(output,entry.getKey());field(output,entry.getValue());}
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(java.io.IOException | java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static void field(java.io.DataOutputStream output,String value) throws java.io.IOException {
        byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);output.writeInt(bytes.length);output.write(bytes);
    }
}
