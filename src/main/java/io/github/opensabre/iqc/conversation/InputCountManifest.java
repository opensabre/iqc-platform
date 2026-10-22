package io.github.opensabre.iqc.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opensabre.iqc.governance.IqcException;
import java.util.List;
import java.util.Map;

/** Explicit, upstream-declared message counts. Never infers a denominator from stored messages. */
public final class InputCountManifest {
    private InputCountManifest() { }
    public static final String VERSION_KEY="iqcInputManifestVersion";
    public static final String COUNT_KEY="iqcExpectedMessageCount";
    public static final String REFERENCE_KEY="iqcInputManifestRef";
    public static final String VERSION="iqc-input-count-manifest-v1";
    public enum State { UNMEASURED, INVALID, COMPLETE, INCOMPLETE, EXCESS }
    public record Observation(State state,long expectedMessages,long observedMessages) { }
    public static Observation observe(JsonNode fields,long observed){
        if(observed<0)throw new IllegalArgumentException("Negative observed message count");
        if(fields==null||!fields.isObject()||!fields.has(VERSION_KEY))return new Observation(State.UNMEASURED,0,observed);
        JsonNode count=fields.path(COUNT_KEY),reference=fields.path(REFERENCE_KEY);
        if(!VERSION.equals(fields.path(VERSION_KEY).asText())||!count.isIntegralNumber()||!count.canConvertToInt()
                ||count.intValue()<1||!reference.isTextual()||reference.textValue().isBlank()
                ||reference.textValue().length()>128||!reference.textValue().equals(reference.textValue().strip())
                ||reference.textValue().codePoints().anyMatch(Character::isISOControl))return new Observation(State.INVALID,0,observed);
        long expected=count.longValue();
        return new Observation(observed==expected?State.COMPLETE:observed<expected?State.INCOMPLETE:State.EXCESS,expected,observed);
    }
    public static void validate(Map<String,JsonNode> fields){
        if(fields==null||!fields.containsKey(VERSION_KEY))return;
        ObjectNode tree=JsonNodeFactory.instance.objectNode();tree.setAll(fields);
        if(observe(tree,0).state()==State.INVALID)throw IqcException.invalidArgument("上游消息清单声明无效，需要版本、正整数预期消息数及清单引用");
    }
    /** Only explicitly activated source metadata is copied; no source text or arbitrary annotations. */
    public static ObjectNode frozenFields(JsonNode fields){
        if(fields==null||!fields.isObject()||!fields.has(VERSION_KEY))return null;
        ObjectNode copy=JsonNodeFactory.instance.objectNode();
        for(String key:List.of(VERSION_KEY,COUNT_KEY,REFERENCE_KEY))if(fields.has(key))copy.set(key,fields.get(key).deepCopy());
        return copy;
    }
}
