package io.github.opensabre.iqc.conversation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Millisecond coordinates require integer JSON tokens; no floating point truncation or text coercion. */
public final class ExactMillisecondsDeserializer extends JsonDeserializer<Long> {
    @Override public Long deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        if(parser.currentToken()!=JsonToken.VALUE_NUMBER_INT)
            return (Long)context.handleUnexpectedToken(Long.class,parser);
        long value=parser.getLongValue();
        if(value<0 || value>9007199254740991L)
            return (Long)context.handleWeirdNumberValue(Long.class,value,"Milliseconds exceed the exact nonnegative range");
        return value;
    }
}
