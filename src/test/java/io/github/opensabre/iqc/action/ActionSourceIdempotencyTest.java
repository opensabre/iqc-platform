package io.github.opensabre.iqc.action;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActionSourceIdempotencyTest {
    private ActionExecutionContract.Source source(String task,String execution,String owner) {
        return new ActionExecutionContract.Source(task,execution,"conversation","result","decision","a".repeat(64),"save",owner);
    }
    @Test void hashesLengthFramedUtf8BytesRatherThanParsingThemAsJson() throws Exception {
        var source=source("task","execution","审核员");
        var text=new StringBuilder();
        for(var field:new String[]{"iqc-action-execution-v1","task","execution","conversation","result","decision","a".repeat(64),"save","审核员","SAVE_RESULT"})
            text.append(field.getBytes(StandardCharsets.UTF_8).length).append(':').append(field);
        String expected=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected,source.idempotencyKey(ActionExecutionContract.Type.SAVE_RESULT));
        assertTrue(expected.matches("[a-f0-9]{64}"));
        assertEquals(expected,source("task","execution","审核员").idempotencyKey(ActionExecutionContract.Type.SAVE_RESULT));
    }
    @Test void fieldBoundariesAndActionTypeRemainPartOfIdentity() {
        var left=source("ab","c","alice");var right=source("a","bc","alice");
        assertNotEquals(left.idempotencyKey(ActionExecutionContract.Type.SAVE_RESULT),right.idempotencyKey(ActionExecutionContract.Type.SAVE_RESULT));
        assertNotEquals(left.idempotencyKey(ActionExecutionContract.Type.SAVE_RESULT),left.idempotencyKey(ActionExecutionContract.Type.WEBHOOK));
        assertNotEquals(left.idempotencyKey(ActionExecutionContract.Type.SAVE_RESULT),source("ab","c","bob").idempotencyKey(ActionExecutionContract.Type.SAVE_RESULT));
    }
}
