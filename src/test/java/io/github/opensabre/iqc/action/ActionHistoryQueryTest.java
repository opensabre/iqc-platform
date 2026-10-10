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
    private final ObjectMapper json=new ObjectMapper();
    private final ActionQueryService query=new ActionQueryService(results,tasks,actions,scope,events,json);
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
        assertThat(query.history("result").getFirst().history()).isNull();verifyNoInteractions(events);
    }
    @Test void deniedResultNeverReadsActionsOrJournal() {
        when(scope.canView("alice","group")).thenReturn(false);
        assertThatThrownBy(()->query.history("result",id,true,null)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(actions,events);
    }
    @Test void foreignActionCannotReachItsJournal() {
        when(actions.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(()->query.history("result",id,true,null)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(events);
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
    @Test void endOfHistoryDistinguishesInitialObservationFromAnUpgradeGap() {
        when(events.page(id,"result",null)).thenReturn(List.of(event(3,2L)));
        assertThat(query.history("result",id,true,null).getFirst().history().earlierHistoryUnavailable()).isTrue();
        when(events.page(id,"result",null)).thenReturn(List.of(event(0,null)));
        var full=query.history("result",id,true,null).getFirst().history();
        assertThat(full.earlierHistoryUnavailable()).isFalse();assertThat(full.nextBeforeVersion()).isNull();
    }
}
