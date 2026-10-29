package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.finding.Finding;
import io.github.opensabre.iqc.quality.QualityOperationsService;
import io.github.opensabre.iqc.quality.evaluation.*;
import io.github.opensabre.iqc.quality.model.*;
import io.github.opensabre.iqc.rest.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Frozen gold and generic sample routes must share the same disclosure boundaries. */
class GoldSamplePrivacyTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final ResultReadProjection projection=new ResultReadProjection(json);
    private SensitiveReadAccessService.Access access(boolean full,boolean pii) {
        return new SensitiveReadAccessService.Access("test",full,pii,false,Instant.now());
    }
    private QualitySample sample() throws Exception {
        var messages=new ArrayList<GoldConversation.Message>();
        for(int i=0;i<5;i++)messages.add(new GoldConversation.Message("m"+i,"CUSTOMER","Call 13812345678 "+"x".repeat(220),i+1,null));
        var draft=new GoldDraft(GoldDraft.SCHEMA,"s","c",messages,null,
                List.of(new GoldFinding("FACT",Finding.ValueType.STRING,Finding.State.KNOWN,json.valueToTree("secret value"),List.of("m4"))),
                "annotator","candidate",List.of(),GoldConversation.Difficulty.BASIC,null);
        var row=new QualitySample();row.setId("s");row.setConversationId("c");row.setName("Call 13812345678");
        row.setContentSnapshot("candidate 13812345678");row.setExpectedJson("expected secret");row.setTagsJson("tags secret");
        row.setGoldDraftJson(json.writeValueAsString(draft));row.setGoldDraftRevision(7);return row;
    }
    @Test void fourGrantCombinationsUseFrozenPreviewAndGenericListNeverReturnsGold() throws Exception {
        var source=sample();String original=json.writeValueAsString(source);
        for(boolean full:List.of(false,true))for(boolean pii:List.of(false,true)) {
            var a=access(full,pii);var gold=projection.goldSample(source,a);var generic=projection.sample(source,a);
            assertEquals(full&&pii,gold.getGoldDraftJson()!=null);assertEquals(full&&pii,generic.getContentSnapshot()!=null);
            assertNull(generic.getGoldDraftJson());assertEquals("GOLD_ENDPOINT_REQUIRED",generic.getPrivacy().get("previewState"));
            assertEquals(7,gold.getGoldDraftRevision());assertNotSame(source,gold);
            if(!(full&&pii)) {
                assertEquals("AVAILABLE",gold.getPrivacy().get("previewState"));
                var preview=(Map<?,?>)gold.getPrivacy().get("preview");
                assertEquals("FROZEN_GOLD",preview.get("source"));
                var messages=(List<?>)preview.get("messages");assertEquals(full?5:3,messages.size());
                String visible=json.writeValueAsString(preview);assertFalse(visible.contains("13812345678"));
                assertFalse(visible.contains("secret value"));assertTrue(visible.contains("m4"));
            } else assertEquals(source.getGoldDraftJson(),gold.getGoldDraftJson());
        }
        assertEquals(original,json.writeValueAsString(source));
    }
    @Test void mismatchedOrCorruptFrozenGoldCannotProvidePreview() throws Exception {
        var row=sample();row.setId("another-sample");
        assertEquals("INVALID_GOLD",projection.goldSample(row,access(false,false)).getPrivacy().get("previewState"));
        row.setId("s");row.setConversationId("another-conversation");
        assertEquals("INVALID_GOLD",projection.goldSample(row,access(false,false)).getPrivacy().get("previewState"));
        row.setGoldDraftJson("broken 13812345678");
        var result=projection.goldSample(row,access(false,false));assertNull(result.getGoldDraftJson());
        assertFalse(json.writeValueAsString(result).contains("13812345678"));
    }
    @Test void historiesAndWriteReceiptsNeverReturnSnapshotsEvenWithBothGrants() throws Exception {
        var row=sample();var version=new QualitySampleVersion();version.setSampleId("s");version.setVersionNo(3);
        version.setSnapshotJson(row.getGoldDraftJson());version.setContentHash("original-hash");version.setReason("Call 13812345678");
        for(boolean full:List.of(false,true))for(boolean pii:List.of(false,true)) {
            var a=access(full,pii);var receipt=projection.sampleMetadata(row,a);
            assertNull(receipt.getContentSnapshot());assertNull(receipt.getGoldDraftJson());assertNull(receipt.getExpectedJson());assertNull(receipt.getTagsJson());
            var history=projection.sampleHistory(new io.github.opensabre.iqc.shared.IqcPage<>(List.of(version),2,10,12),a);
            assertEquals(12,history.total());assertEquals(2,history.current());assertEquals(10,history.size());
            assertNull(history.records().getFirst().getSnapshotJson());
            assertEquals("original-hash",history.records().getFirst().getContentHash());
            assertNull(projection.sampleVersionMetadata(version,a).getSnapshotJson());
            assertEquals(full&&pii,projection.sampleVersion(version,a).getSnapshotJson()!=null);
        }
        assertNotNull(version.getSnapshotJson());assertEquals(row.getGoldDraftJson(),version.getSnapshotJson());
    }
    @Test void sampleRoutesAuthorizeBeforeReadWriteAndCreateReturnsOnlyMetadata() throws Exception {
        var service=mock(QualityOperationsService.class);var grants=mock(SensitiveReadAccessService.class);
        var controller=new QualityOperationsController(service,null,null,null,null,grants,projection);
        when(grants.current(anySet())).thenReturn(access(true,true));var source=sample();
        when(service.samples(null,null)).thenReturn(List.of(source));
        when(service.createSample("r","name","POSITIVE","expected","tags")).thenReturn(source);
        assertNull(controller.samples(null,null).getFirst().getGoldDraftJson());
        var receipt=controller.createSample("r",new QualityOperationsController.SampleRequest("name","POSITIVE","expected","tags"));
        assertNull(receipt.getContentSnapshot());assertNull(receipt.getGoldDraftJson());
        verify(grants).current(Set.of("iqc:sample:view"));verify(grants).current(Set.of("iqc:sample:manage"));
        clearInvocations(service);when(grants.current(anySet())).thenThrow(new IllegalStateException("revoked"));
        assertThrows(IllegalStateException.class,()->controller.samples(null,null));
        assertThrows(IllegalStateException.class,()->controller.createSample("r",null));verifyNoInteractions(service);
        assertThrows(RuntimeException.class,()->new QualityOperationsController(service,null,null,null).samples(null,null));
    }
    @Test void allGoldRoutesUseTheirOwnFixedGrantAndDenyBeforeDomainAccess() {
        var service=mock(GoldSampleService.class);var grants=mock(SensitiveReadAccessService.class);
        var controller=new GoldSampleController(service,grants,projection);
        when(grants.current(anySet())).thenThrow(new IllegalStateException("revoked"));
        assertThrows(IllegalStateException.class,()->controller.get("s"));
        assertThrows(IllegalStateException.class,()->controller.annotate("s",null));
        assertThrows(IllegalStateException.class,()->controller.review("s",null));
        assertThrows(IllegalStateException.class,()->controller.history("s",1,20));
        assertThrows(IllegalStateException.class,()->controller.version("s",1));
        for(String code:List.of("iqc:gold:view","iqc:gold:annotate","iqc:gold:review","iqc:gold:versions:view","iqc:gold:version:view"))
            verify(grants).current(Set.of(code));
        verifyNoInteractions(service);
        assertThrows(RuntimeException.class,()->new GoldSampleController(service).get("s"));
    }
}
