package io.github.opensabre.iqc.privacy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.result.BatchResultQueryService;
import io.github.opensabre.iqc.rest.InspectionResultController;
import io.github.opensabre.iqc.shared.IqcPage;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Summary projections preserve canonical statistics and authorize before domain access. */
class BusinessSummaryPrivacyTest {
    private final ResultReadProjection projection=new ResultReadProjection(new ObjectMapper());
    @Test void preservesAllStatisticsAndSourceAcrossGrantCombinations() {
        var row=new BatchResultQueryService.BusinessConversationRow("r","task","Call 13812345678","conv",
                "13812345678.txt","exec","FINAL",BigDecimal.ZERO,5,2,1,"HIGH");
        var page=new IqcPage<>(List.of(row),2,10,23);
        var conversation=new BatchResultQueryService.ConversationResultSummary("conv","13812345678.txt",9,7,null,3,2,1,"PENDING");
        var source=new BatchResultQueryService.BatchResultSummary("task","RUNNING",1,9,7,1,BigDecimal.ZERO,3,2,List.of(conversation));
        for(boolean full:List.of(false,true)) for(boolean pii:List.of(false,true)) {
            var access=new SensitiveReadAccessService.Access("test",full,pii,false,Instant.now());
            var projected=projection.businessPage(page,access); var actual=projected.records().getFirst();
            assertEquals(2,projected.current());assertEquals(10,projected.size());assertEquals(23,projected.total());
            assertEquals(full&&pii,actual.sourceFileName().contains("13812345678"));
            assertEquals(full&&pii,actual.taskName().contains("13812345678"));
            assertEquals(new BatchResultQueryService.BusinessConversationRow(row.id(),row.taskId(),actual.taskName(),row.conversationId(),actual.sourceFileName(),row.executionId(),row.scoreStatus(),row.finalScore(),row.itemCount(),row.failureCount(),row.errorCount(),row.riskLevel()),actual);
            var result=projection.batchSummary(source,access);var detail=result.conversations().getFirst();
            assertEquals(full&&pii,detail.sourceFileName().contains("13812345678"));
            assertEquals(new BatchResultQueryService.ConversationResultSummary(conversation.conversationId(),detail.sourceFileName(),9,7,null,3,2,1,"PENDING"),detail);
            assertEquals(new BatchResultQueryService.BatchResultSummary("task","RUNNING",1,9,7,1,BigDecimal.ZERO,3,2,List.of(detail)),result);
        }
        assertEquals("13812345678.txt",row.sourceFileName());assertEquals("13812345678.txt",conversation.sourceFileName());
    }
    @Test void revokedGrantPrecedesSecondQueryAndSummaryRequiresItsOwnGrant() {
        var batch=mock(BatchResultQueryService.class);var grants=mock(SensitiveReadAccessService.class);
        var controller=new InspectionResultController(null,null,batch,null,null,null,null,grants,projection);
        var access=new SensitiveReadAccessService.Access("test",false,false,false,Instant.now());
        when(grants.current(Set.of("iqc:result:business:view"))).thenReturn(access).thenThrow(new IllegalStateException("revoked"));
        when(batch.businessPage(1,20,null,null,null,null,null,null)).thenReturn(new IqcPage<>(List.of(),1,20,0));
        assertEquals(0,controller.businessResults(1,20,null,null,null,null,null).total());
        assertThrows(IllegalStateException.class,()->controller.businessResults(1,20,null,null,null,null,null));
        verify(batch,times(1)).businessPage(1,20,null,null,null,null,null,null);
        when(grants.current(Set.of("iqc:result:summary:view"))).thenThrow(new IllegalStateException("denied"));
        assertThrows(IllegalStateException.class,()->controller.batchSummary("task"));verify(batch,never()).summary(anyString());
    }
}
