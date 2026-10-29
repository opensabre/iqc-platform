package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.quality.QualityOperationsService;
import io.github.opensabre.iqc.quality.model.ResultFeedback;
import io.github.opensabre.iqc.rest.QualityOperationsController;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Feedback projection must preserve stored evidence and authorize before domain access. */
class FeedbackPrivacyTest {
    private final ResultReadProjection projection = new ResultReadProjection(new ObjectMapper());
    private SensitiveReadAccessService.Access access(boolean full, boolean pii) {
        return new SensitiveReadAccessService.Access("v1", full, pii, false, Instant.now());
    }
    private ResultFeedback row() {
        var row = new ResultFeedback(); row.setId("feedback-1"); row.setResultId("result-1");
        row.setComment("Call 13812345678");
        row.setEvidenceJson("{\"id\":\"13812345678\",\"value\":0.1234567890123456789,\"count\":9007199254740993}");
        return row;
    }
    @Test void allGrantCombinationsPreserveOriginalAndExactNumbers() {
        for (boolean full : List.of(false,true)) for (boolean pii : List.of(false,true)) {
            var source = row(); var result = projection.feedback(source, access(full,pii));
            assertNotSame(source,result); assertEquals("feedback-1",result.getId());
            assertEquals("result-1",result.getResultId());
            assertEquals(full && pii,result.getComment().contains("13812345678"));
            assertEquals(full && pii,result.getEvidenceJson().contains("13812345678"));
            assertTrue(result.getEvidenceJson().contains("0.1234567890123456789"));
            assertTrue(result.getEvidenceJson().contains("9007199254740993"));
            assertEquals(row().getEvidenceJson(), source.getEvidenceJson());
            assertEquals(row().getComment(), source.getComment());
        }
    }
    @Test void invalidJsonIsWithheldUnlessBothGrantsPresent() {
        var source=row(); source.setEvidenceJson("broken 13812345678");
        assertFalse(projection.feedback(source,access(true,false)).getEvidenceJson().contains("13812345678"));
        assertEquals(source.getEvidenceJson(),projection.feedback(source,access(true,true)).getEvidenceJson());
    }
    @Test void deniedGrantPrecedesReadsAndWritesAndMissingWiringFailsClosed() {
        var service=mock(QualityOperationsService.class); var grants=mock(SensitiveReadAccessService.class);
        var controller=new QualityOperationsController(service,null,null,null,null,grants,projection);
        when(grants.current(anySet())).thenThrow(new IllegalStateException("revoked"));
        assertThrows(IllegalStateException.class,()->controller.feedbacks(null,null));
        assertThrows(IllegalStateException.class,()->controller.feedback("result-1",new QualityOperationsController.FeedbackRequest("CORRECT","text",null)));
        verifyNoInteractions(service);
        var legacy=new QualityOperationsController(service,null,null,null);
        assertThrows(RuntimeException.class,()->legacy.feedbacks(null,null));
        verifyNoInteractions(service);
    }
    @Test void fixedWriteAndReadGrantsKeepStoredTextAndMaskBothResponses() {
        var service=mock(QualityOperationsService.class); var grants=mock(SensitiveReadAccessService.class);
        var controller=new QualityOperationsController(service,null,null,null,null,grants,projection);
        var source=row(); when(grants.current(anySet())).thenReturn(access(false,false));
        when(service.feedback("result-1","CORRECT",source.getComment(),source.getEvidenceJson())).thenReturn(source);
        when(service.feedbacks("result-1",null)).thenReturn(List.of(source));
        var posted=controller.feedback("result-1",new QualityOperationsController.FeedbackRequest("CORRECT",source.getComment(),source.getEvidenceJson()));
        assertFalse(posted.getComment().contains("13812345678"));
        assertFalse(controller.feedbacks("result-1",null).getFirst().getEvidenceJson().contains("13812345678"));
        verify(grants).current(Set.of("iqc:result:feedback")); verify(grants).current(Set.of("iqc:result:feedback:view"));
        verify(service).feedback("result-1","CORRECT",source.getComment(),source.getEvidenceJson());
        assertEquals(row().getComment(),source.getComment());
    }
}
