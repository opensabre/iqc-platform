package io.github.opensabre.iqc.action;

import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Exercises actual MyBatis record traversal and JDBC bindings without claiming database execution. */
class ActionReconciliationBindingTest {
    @Test void completeFrozenRequestReachesJdbcWithItsActualEnumAndSourceValues() throws Exception {
        var configuration=new Configuration();
        configuration.addMapper(ActionExecutionMapper.class);
        var source=new ActionExecutionContract.Source("Task-A","Execution-A","Conversation-A","Result-A","Decision-A",
                io.github.opensabre.iqc.selector.SelectorAssetService.hash("{}"),"Action-A","Owner-A");
        var request=new ActionAdapter.Request("ledger",source.idempotencyKey(ActionExecutionContract.Type.SEND_ALERT),source,
                new ActionAdapter.Definition(ActionExecutionContract.Type.SEND_ALERT,1,false),1,"{}",
                io.github.opensabre.iqc.selector.SelectorAssetService.hash("{}"),"{}");
        var method=ActionExecutionMapper.class.getMethod("reconcileSuccessBound",ActionAdapter.Request.class,long.class,
                String.class,LocalDateTime.class,String.class,String.class,String.class);
        var now=LocalDateTime.of(2026,10,11,0,0);
        var parameters=new ParamNameResolver(configuration,method).getNamedParams(
                new Object[]{request,2L,"a".repeat(32),now,"proof","artifact","Owner-A"});
        var statement=configuration.getMappedStatement(ActionExecutionMapper.class.getName()+".reconcileSuccessBound");
        var sql=statement.getBoundSql(parameters);
        Map<Integer,Object> bound=new HashMap<>();
        var jdbc=mock(PreparedStatement.class,invocation->{
            if(invocation.getMethod().getName().startsWith("set") && invocation.getArguments().length>=2)
                bound.put((Integer)invocation.getArgument(0),invocation.getArgument(1));
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        new DefaultParameterHandler(statement,parameters,sql).setParameters(jdbc);
        Map<String,Object> values=new HashMap<>();
        for(int i=0;i<sql.getParameterMappings().size();i++)
            values.put(sql.getParameterMappings().get(i).getProperty(),bound.get(i+1));
        assertThat(values).containsEntry("request.executionId","ledger")
                .containsEntry("request.idempotencyKey",request.idempotencyKey())
                .containsEntry("request.source.taskId","Task-A")
                .containsEntry("request.source.executionId","Execution-A")
                .containsEntry("request.source.conversationId","Conversation-A")
                .containsEntry("request.source.resultId","Result-A")
                .containsEntry("request.source.decisionReference","Decision-A")
                .containsEntry("request.source.actionPolicyHash",source.actionPolicyHash())
                .containsEntry("request.source.actionId","Action-A")
                .containsEntry("request.source.owner","Owner-A")
                .containsEntry("request.adapter.type","SEND_ALERT")
                .containsEntry("request.inputSnapshotJson","{}")
                .containsEntry("request.inputSnapshotHash",request.inputSnapshotHash())
                .containsEntry("request.policySnapshotJson","{}")
                .containsEntry("request.attempt",1).containsEntry("version",2L);
        assertThat(bound).hasSize(sql.getParameterMappings().size());
    }
}
