package io.github.opensabre.iqc.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.action.*;
import io.github.opensabre.iqc.result.dao.ConversationInspectionResultMapper;
import io.github.opensabre.iqc.result.model.ConversationInspectionResult;
import io.github.opensabre.iqc.selector.SelectorAssetService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IsolatedMysqlSavedResultAdapterIT {
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static ConversationInspectionResultMapper rows;
    private static SavedResultActionAdapter adapter;
    private final ObjectMapper json=new ObjectMapper();
    private ActionAdapter.Request request;
    @BeforeAll static void start() throws Exception {
        IsolatedMysqlTaskExecutionStateIT.start();
        context=new AnnotationConfigApplicationContext(IsolatedMysqlTaskExecutionStateIT.DatabaseConfiguration.class);
        jdbc=new JdbcTemplate(context.getBean(DataSource.class));
        var factory=context.getBean(SqlSessionFactory.class);
        factory.getConfiguration().addMapper(ConversationInspectionResultMapper.class);
        rows=new SqlSessionTemplate(factory).getMapper(ConversationInspectionResultMapper.class);
        adapter=new SavedResultActionAdapter(rows,new ObjectMapper());
    }
    @AfterAll static void close() {if(context!=null)context.close();IsolatedMysqlTaskExecutionStateIT.close();}
    @BeforeEach void fixture() throws Exception {
        jdbc.update("DELETE FROM iqc_inspection_conversation_result");
        var row=new ConversationInspectionResult();row.setId("result");row.setTaskId("task");row.setExecutionId("execution");row.setConversationId("conversation");
        row.setReason("isolated saved-result confirmation");row.setAggregationMode("PIPELINE");row.setResultStatus("QUALIFIED");row.setRiskLevel("NONE");row.setScoreStatus("FINAL");row.setFinalScore(new BigDecimal("90.00"));
        row.setExecutionTraceJson("{\"trace\":true}");row.setScoringResultJson("{\"scoreStatus\":\"FINAL\",\"finalScore\":90.0}");row.setBusinessItemResultsJson("[]");
        assertEquals(1,rows.insert(row));
        var policy=new ActionPolicy(ActionPolicy.SCHEMA,List.of(new ActionPolicy.Action("save",ActionExecutionContract.Type.SAVE_RESULT,
                new ActionPolicy.Condition(ActionPolicy.Kind.ALWAYS,null,null,List.of()),ActionPolicy.Idempotency.PRODUCTION_SOURCE_V1,
                null,ActionPolicy.FailureHandling.RECORD_ONLY,json.createObjectNode())));
        String body=json.writeValueAsString(policy);
        var source=new ActionExecutionContract.Source("task","execution","conversation","result","result",SelectorAssetService.hash(body),"save","alice");
        var frozen=json.createObjectNode().put("schemaVersion","iqc-action-production-input-v1").put("taskId","task").put("executionId","execution")
                .put("conversationId","conversation").put("resultId","result").put("owner","alice").put("traceJson",row.getExecutionTraceJson())
                .put("scoringResultJson",row.getScoringResultJson()).put("businessItemResultsJson",row.getBusinessItemResultsJson())
                .put("resultStatus",row.getResultStatus()).put("riskLevel",row.getRiskLevel());
        String input=json.writeValueAsString(frozen);String key=source.idempotencyKey(ActionExecutionContract.Type.SAVE_RESULT);
        request=new ActionAdapter.Request(key,key,source,adapter.definition(),1,input,SelectorAssetService.hash(input),body);
    }
    private void unknown(String error) {
        var receipt=adapter.execute(request);assertEquals(ActionExecutionContract.Outcome.UNKNOWN,receipt.outcome());
        assertEquals(error,receipt.errorCode());assertNull(receipt.evidenceReference());assertNull(receipt.resultReference());
    }
    @Test void actualPersistedResultConfirmsRepeatedlyWithoutCreatingOrUpdatingRows() {
        var before=jdbc.queryForMap("SELECT * FROM iqc_inspection_conversation_result WHERE id='result'");
        for(int i=0;i<2;i++) {
            var receipt=adapter.execute(request);assertEquals(ActionExecutionContract.Outcome.CONFIRMED_SUCCESS,receipt.outcome());
            assertEquals("result",receipt.evidenceReference());assertEquals("result",receipt.resultReference());receipt.requireRequest(request);
        }
        assertEquals(before,jdbc.queryForMap("SELECT * FROM iqc_inspection_conversation_result WHERE id='result'"));
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM iqc_inspection_conversation_result",Long.class));
    }
    @Test void missingPersistedResultIsUnknown() {
        jdbc.update("DELETE FROM iqc_inspection_conversation_result WHERE id='result'");unknown("SAVED_RESULT_UNAVAILABLE");
    }
    @Test void changedTraceCannotConfirmFrozenResult() {
        jdbc.update("UPDATE iqc_inspection_conversation_result SET execution_trace_json='{}' WHERE id='result'");unknown("SOURCE_RESULT_MISMATCH");
    }
    @Test void changedCanonicalScoreCannotConfirmEvenWithSameScoringJson() {
        jdbc.update("UPDATE iqc_inspection_conversation_result SET final_score=89 WHERE id='result'");unknown("SOURCE_RESULT_MISMATCH");
    }
    @Test void foreignExecutionCannotUseOriginalReceipt() {
        jdbc.update("UPDATE iqc_inspection_conversation_result SET execution_id='foreign' WHERE id='result'");unknown("SOURCE_RESULT_MISMATCH");
    }
}
