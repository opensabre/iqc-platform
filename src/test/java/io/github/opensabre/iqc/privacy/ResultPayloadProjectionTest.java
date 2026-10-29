package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.label.LabelResultQueryService.LabelResultView;
import io.github.opensabre.iqc.result.model.InspectionResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Arbitrary payload fields must not inherit exemptions intended for evidence identities. */
class ResultPayloadProjectionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ResultReadProjection projection = new ResultReadProjection(json);
    private final SensitiveReadAccessService.Access restricted =
            new SensitiveReadAccessService.Access("test", false, false, false, Instant.now());

    @Test
    void nestedStructuralNamesAndHashObjectsCannotBypassMasking() throws Exception {
        var source = new InspectionResult();
        source.setFindingJson("""
                {"id":"result-1","messageIds":["msg-99"],"inputHash":"%s",
                 "reference":{"id":"13812345678"},"sourceHash":{"status":"13812345678"},
                 "value":{"code":"13812345678","id":"13812345678","status":"13812345678"},
                 "evidence":[{"messageId":"msg-99","sequenceNo":99,"start":5,"end":16,"text":"13812345678"}]}
                """.formatted("a".repeat(64)));

        String projected = projection.result(source, restricted).getFindingJson();
        assertFalse(projected.contains("13812345678"));
        var tree = json.readTree(projected);
        assertEquals("result-1", tree.path("id").asText());
        assertEquals("msg-99", tree.path("messageIds").get(0).asText());
        assertEquals("a".repeat(64), tree.path("inputHash").asText());
        assertEquals("msg-99", tree.at("/evidence/0/messageId").asText());
        assertEquals(99, tree.at("/evidence/0/sequenceNo").intValue());
        assertEquals(5, tree.at("/evidence/0/start").intValue());
        assertEquals(16, tree.at("/evidence/0/end").intValue());
        assertTrue(source.getFindingJson().contains("13812345678"));
    }

    @Test
    void exactNumbersSurviveMaskingWithoutChangingTheSharedMapper() throws Exception {
        String decimal = "0.123456789012345678901234567890";
        var source = new InspectionResult();
        source.setSuggestionJson("{\"score\":" + decimal
                + ",\"sequenceNo\":9007199254740993,\"text\":\"13812345678\"}");
        String projected = projection.result(source, restricted).getSuggestionJson();

        assertTrue(projected.contains(decimal));
        assertTrue(projected.contains("9007199254740993"));
        assertFalse(projected.contains("13812345678"));
        var exact = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(projected);
        assertEquals(0, new BigDecimal(decimal).compareTo(exact.path("score").decimalValue()));
        assertFalse(json.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
        assertTrue(source.getSuggestionJson().contains("13812345678"));
    }

    @Test
    void labelBusinessValuesArePayloadsAndFilenamesRequireBothGrants() {
        String value = "{\"value\":{\"id\":\"13812345678\",\"messageId\":\"13812345678\"}}";
        var source = new LabelResultView("lr-1", "conv-1", "Alice-private-file.txt", "label-1",
                "Label", "L-1", "Group / Label", 1, "VALUE-1", value, BigDecimal.ONE,
                "FINDING", "rule-1", "[{\"messageId\":\"msg-99\"}]", "HIT", null);
        for (boolean full : List.of(false, true)) {
            var access = new SensitiveReadAccessService.Access("test", full, false, false, Instant.now());
            var projected = projection.labelResults(List.of(source), access).getFirst();
            assertNotEquals(source.sourceFileName(), projected.get("sourceFileName"));
            assertEquals(source.sourceFileName().length(), ((String) projected.get("sourceFileName")).length());
            assertFalse(((String) projected.get("valueJson")).contains("13812345678"));
            assertTrue(((String) projected.get("evidenceJson")).contains("msg-99"));
        }
        var fullPii = new SensitiveReadAccessService.Access("test", true, true, false, Instant.now());
        var disclosed = projection.labelResults(List.of(source), fullPii).getFirst();
        assertEquals(source.sourceFileName(), disclosed.get("sourceFileName"));
        assertEquals(value, disclosed.get("valueJson"));
        assertEquals(value, source.valueJson());
    }
}
