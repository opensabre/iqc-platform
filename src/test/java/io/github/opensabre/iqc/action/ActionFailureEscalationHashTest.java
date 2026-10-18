package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.quality.BusinessItemReviewService;
import io.github.opensabre.iqc.schedule.ScheduledActorAccessService;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import io.github.opensabre.iqc.shared.IqcDataScope;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the owning escalation/lookup paths with real frozen-source proof and hashing. */
class ActionFailureEscalationHashTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final ActionExecutionMapper rows=mock(ActionExecutionMapper.class);
    private final ActionProductionSourceValidator sources=mock(ActionProductionSourceValidator.class);
    private final BusinessItemReviewService reviews=mock(BusinessItemReviewService.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private final ActionFailureEscalationService service=new ActionFailureEscalationService(rows,sources,reviews,scope,json);
    private final ScheduledActorAccessService.CreationBound bound=new ScheduledActorAccessService.CreationBound("iqc-schedule-owner-scope-v1","user","alice",false,"group");
    private ActionExecution row;
    private ActionProductionSourceValidator.Validated validated;
    @BeforeEach void fixture()throws Exception{
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"escalation"),ActionExecution.class);
        var condition=new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS,null,null,List.of());
        var action=new ActionPolicy.Action("save",ActionExecutionContract.Type.SAVE_RESULT,condition,ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,null,ActionPolicy.FailureHandling.ESCALATE_REVIEW,json.createObjectNode());
        var policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of(action));String body=json.writeValueAsString(policy);
        var reference=new ActionProductionSourceValidator.Reference("task","execution","conversation","result","alice");
        validated=new ActionProductionSourceValidator.Validated(reference,"group","result","{}",SelectorAssetService.hash("{}"),policy,
                new ActionPolicy.Context(Map.of(),Map.of(),io.github.opensabre.iqc.scoring.InspectionScoring.ScoreStatus.FINAL,null,"UNKNOWN",null),bound);
        var source=new ActionExecutionContract.Source("task","execution","conversation","result","result",SelectorAssetService.hash(body),"save","alice");
        row=new ActionExecution();row.setId(source.idempotencyKey(action.type()));row.setIdempotencyKey(row.getId());
        row.setTaskId("task");row.setExecutionId("execution");row.setConversationId("conversation");row.setSourceResultId("result");row.setDecisionReference("result");
        row.setActionPolicyHash(source.actionPolicyHash());row.setActionId("save");row.setActionType("SAVE_RESULT");row.setOwner("alice");row.setOwnerGroupId("group");
        row.setPolicySnapshotJson(body);row.setInputSnapshotJson("{}");row.setInputSnapshotHash(validated.inputSnapshotHash());
        row.setStatus("FAILED");row.setOutcome("CONFIRMED_FINAL_FAILURE");row.setVersion(2L);row.setAttemptCount(1);row.setClaimToken("a".repeat(32));
        row.setClaimedAtUtc(LocalDateTime.of(2030,1,1,0,0));row.setSettledAtUtc(row.getClaimedAtUtc().plusSeconds(1));
        when(scope.owner()).thenReturn("alice");when(scope.canView("alice","group")).thenReturn(true);
        when(rows.selectById(row.getId())).thenReturn(row);when(rows.selectOne(any())).thenReturn(row);
        when(sources.validate(reference)).thenReturn(validated);when(sources.validateHistorical(reference)).thenReturn(validated);
    }
    private String expectedToken()throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(("iqc-action-failure-review-v1:"+row.getIdempotencyKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    @Test void escalationAndHistoryUseSameStableNonJsonDigestWithoutChangingAction()throws Exception{
        String original=json.writeValueAsString(row);String reason="动作失败升级复核，原动作记录："+row.getId();
        assertEquals("WAITING_REVIEW",service.escalate(row.getId(),2).state());
        assertEquals("UNCONFIRMED",service.lookup("result",row.getId(),bound).state());
        verify(reviews).requestEscalation("result",expectedToken(),reason);verify(reviews).lookupEscalation("result",expectedToken(),reason);
        assertEquals(original,json.writeValueAsString(row));verify(rows,never()).updateById(any(ActionExecution.class));
    }
    @Test void repeatedEscalationKeepsSameReviewRequestIdentity()throws Exception{
        service.escalate(row.getId(),2);service.escalate(row.getId(),2);
        verify(reviews,times(2)).requestEscalation(eq("result"),eq(expectedToken()),anyString());
    }
    @Test void staleObservationCannotRequestReview(){
        assertEquals("STALE_OBSERVATION",service.escalate(row.getId(),1).state());verifyNoInteractions(reviews);
    }
    @Test void tamperedFrozenSourceCannotRequestReview(){
        row.setInputSnapshotJson("{\"changed\":true}");assertThrows(IqcException.class,()->service.escalate(row.getId(),2));verifyNoInteractions(reviews);
    }
    @Test void revokedReviewGrantCannotReachSourceOrRequest(){
        doThrow(IqcException.accessDenied("revoked")).when(scope).requireScheduledResources(Set.of("iqc:review:create"));
        assertThrows(IqcException.class,()->service.escalate(row.getId(),2));verifyNoInteractions(rows,sources,reviews);
    }
}
