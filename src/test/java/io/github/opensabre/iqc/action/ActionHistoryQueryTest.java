package io.github.opensabre.iqc.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.task.dao.InspectionTaskMapper;
import io.github.opensabre.iqc.task.model.InspectionTask;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Authorization, bounded cursors and the public projection contract, independent of adapter execution. */
class ActionHistoryQueryTest {
    private final ConversationInspectionResultMapper results=mock(ConversationInspectionResultMapper.class);
    private final InspectionTaskMapper tasks=mock(InspectionTaskMapper.class);
    private final ActionExecutionMapper actions=mock(ActionExecutionMapper.class);
    private final IqcDataScope scope=mock(IqcDataScope.class);
    private final ActionStateEventMapper events=mock(ActionStateEventMapper.class);
    private final ActionReconciliationAttemptMapper observations=mock(ActionReconciliationAttemptMapper.class);
    private final ObjectMapper json=new ObjectMapper();
    private final ActionQueryService query=new ActionQueryService(observations,results,tasks,actions,scope,events,json);
    private final String id="a".repeat(64);
    @BeforeEach void setup() {
        for(var type:List.of(ActionExecution.class,ConversationInspectionResult.class,InspectionTask.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),type.getSimpleName()),type);
        var result=new ConversationInspectionResult();result.setId("result");result.setTaskId("task");result.setExecutionId("old-eid");result.setConversationId("conversation");
        when(results.selectOne(any())).thenReturn(result);
        var task=new InspectionTask();task.setId("task");task.setCreatedBy("alice");task.setOwnerGroupId("group");
        when(tasks.selectOne(any())).thenReturn(task);when(scope.canView("alice","group")).thenReturn(true);
        var action=new ActionExecution();action.setId(id);action.setVersion(3L);action.setActionId("review");action.setStatus("RUNNING");
        when(actions.selectList(any())).thenReturn(List.of(action));
    }
    private ActionStateEventProjection event(long version,Long previous) {
        var row=new ActionStateEventProjection();row.setVersion(version);row.setPreviousVersion(previous);
        row.setStateJson("{\"status\":\"RUNNING\",\"attemptCount\":1}");
        if(previous!=null)row.setPreviousStateJson("{\"status\":\"WAITING_RETRY\",\"attemptCount\":1}");return row;
    }
    @Test void ordinaryHistoryDoesNotReadTheJournal() {
        assertThat(query.history("result").getFirst().history()).isNull();verifyNoInteractions(events,observations);
    }
    @Test void deniedResultNeverReadsActionsOrJournal() {
        when(scope.canView("alice","group")).thenReturn(false);
        assertThatThrownBy(()->query.history("result",id,true,null)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(actions,events,observations);
    }
    @Test void foreignActionCannotReachItsJournal() {
        when(actions.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(()->query.history("result",id,true,null)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(events,observations);
    }
    @Test void invalidAndOverflowingCursorsFailBeforeDatabaseAccess() {
        for(String cursor:List.of("0","-1","1.2","001","9223372036854775808"))
            assertThatThrownBy(()->query.history("result",id,true,cursor)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->query.history("result",null,true,null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->query.history("result",id,false,"1")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(results,tasks,actions,events);
    }
    @Test void journalVersionAndCursorRemainExactBeyondJavascriptIntegerRange() throws Exception {
        long high=Long.MAX_VALUE-1;var rows=new ArrayList<ActionStateEventProjection>();
        for(int i=0;i<51;i++)rows.add(event(high-i,high-i-1));
        when(events.page(id,"result",Long.MAX_VALUE)).thenReturn(rows);
        var page=query.history("result",id,true,Long.toString(Long.MAX_VALUE)).getFirst().history();
        assertThat(page.events()).hasSize(50);assertThat(page.events().getFirst().version()).isEqualTo(Long.toString(high));
        assertThat(page.nextBeforeVersion()).isEqualTo(Long.toString(high-49));assertThat(page.earlierHistoryUnavailable()).isFalse();
        assertThat(json.writeValueAsString(page)).contains("\"version\":\"9223372036854775806\"").doesNotContain("claimToken","claim_token","snapshot");
    }
    @Test void observationsHaveIndependentBoundedCompositeCursorsAndStringVersions() throws Exception {
        var time=java.time.LocalDateTime.of(2026,10,11,0,0);
        var rows=new ArrayList<ActionReconciliationAttemptProjection>();
        for(int i=51;i>0;i--) {
            var row=new ActionReconciliationAttemptProjection();row.setId(String.format("%032x",i));
            row.setObservedVersion(Long.MAX_VALUE);row.setStartedAtUtc(time);row.setState("STARTED");row.setReason("<b>核对</b>");rows.add(row);
        }
        when(observations.page(id,"result",null,null)).thenReturn(rows);
        var page=query.history("result",id,false,null,true,null).getFirst().attempts();
        assertThat(page.attempts()).hasSize(50);
        assertThat(page.nextBeforeAttempt()).isEqualTo("2026-10-11T00:00:00.000~"+String.format("%032x",2));
        assertThat(page.attempts().getFirst().observedVersion()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(json.writeValueAsString(page.attempts().getFirst().observedVersion())).contains("9223372036854775807");
        query.history("result",id,false,null,true,page.nextBeforeAttempt());
        verify(observations).page(id,"result",time,String.format("%032x",2));verifyNoInteractions(events);
    }
    @Test void invalidObservationCursorsAndDeniedSourcesNeverReadObservations() {
        for(String cursor:List.of("bad","2026-02-30T00:00:00.000~"+"a".repeat(32),"2026-10-11T00:00:00.000~"+"A".repeat(32)))
            assertThatThrownBy(()->query.history("result",id,false,null,true,cursor)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->query.history("result",null,false,null,true,null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->query.history("result",id,false,null,false,"bad")).isInstanceOf(RuntimeException.class);
        when(scope.canView("alice","group")).thenReturn(false);
        assertThatThrownBy(()->query.history("result",id,false,null,true,null)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(observations);
    }
    @Test void endOfHistoryDistinguishesInitialObservationFromAnUpgradeGap() {
        when(events.page(id,"result",null)).thenReturn(List.of(event(3,2L)));
        assertThat(query.history("result",id,true,null).getFirst().history().earlierHistoryUnavailable()).isTrue();
        when(events.page(id,"result",null)).thenReturn(List.of(event(0,null)));
        var full=query.history("result",id,true,null).getFirst().history();
        assertThat(full.earlierHistoryUnavailable()).isFalse();assertThat(full.nextBeforeVersion()).isNull();
    }
}
