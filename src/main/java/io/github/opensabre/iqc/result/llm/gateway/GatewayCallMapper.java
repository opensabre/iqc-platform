package io.github.opensabre.iqc.result.llm.gateway;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface GatewayCallMapper extends BaseMapper<GatewayCall> {
    @lombok.Data
    class CostGroup {
        private String currency;
        private Long callCount;
        private Long knownCount;
        private java.math.BigDecimal knownSubtotal;
    }
    record RunReference(String taskId,String executionId,String conversationId) { }
    String RUN_SCOPE="""
        AND (1=0 <foreach collection="runs" item="run">
          OR (task_id=#{run.taskId} AND execution_id=#{run.executionId} AND conversation_id=#{run.conversationId})
        </foreach>)
        """;
    @lombok.Data
    class LatencyGroup {
        private Long callCount;
        private Long measuredCount;
        private java.math.BigDecimal averageMillis;
        private Long inputKnown,outputKnown,totalKnown;
        private java.math.BigDecimal inputSubtotal,outputSubtotal,totalSubtotal;
    }
    String COST_GROUP_QUERY="""
        <script>
        SELECT pricing_currency AS currency, COUNT(*) AS call_count,
          SUM(CASE WHEN status='CONFIRMED' AND cost_status='CALCULATED_FROM_DECLARED_PRICE' AND calculated_cost IS NOT NULL
            AND pricing_currency IS NOT NULL THEN 1 ELSE 0 END) AS known_count,
          SUM(CASE WHEN status='CONFIRMED' AND cost_status='CALCULATED_FROM_DECLARED_PRICE' AND calculated_cost IS NOT NULL
            AND pricing_currency IS NOT NULL THEN calculated_cost ELSE NULL END) AS known_subtotal
        FROM iqc_model_gateway_call
        <where>
          <if test="_parameter.containsKey('modelKey') and modelKey != null">AND model_key=#{modelKey}</if>
          <if test="_parameter.containsKey('taskId') and taskId != null">AND task_id=#{taskId}</if>
          <if test="_parameter.containsKey('executionId') and executionId != null">AND execution_id=#{executionId}</if>
          <if test="_parameter.containsKey('status') and status != null">AND status=#{status}</if>
          <if test="_parameter.containsKey('conversationId') and conversationId != null">AND conversation_id=#{conversationId}</if>
          <if test="_parameter.containsKey('runs')">
        """+RUN_SCOPE+"""
          </if>
        </where>
        GROUP BY pricing_currency ORDER BY pricing_currency
        </script>
        """;
    @Select(COST_GROUP_QUERY)
    @Results(id="costGroupRow",value={@Result(column="currency",property="currency"),@Result(column="call_count",property="callCount"),
        @Result(column="known_count",property="knownCount"),@Result(column="known_subtotal",property="knownSubtotal")})
    List<CostGroup> costGroups(@Param("modelKey") String modelKey,@Param("taskId") String taskId,
        @Param("executionId") String executionId,@Param("status") String status);
    @Select(COST_GROUP_QUERY)
    @ResultMap("costGroupRow")
    List<CostGroup> conversationCostGroups(@Param("modelKey") String modelKey,@Param("taskId") String taskId,
        @Param("executionId") String executionId,@Param("status") String status,@Param("conversationId") String conversationId);
    @Select(COST_GROUP_QUERY)
    @ResultMap("costGroupRow")
    List<CostGroup> runCostGroups(@Param("runs") List<RunReference> runs);
    @Select("""
        <script>
        SELECT COUNT(*) AS call_count,
          COALESCE(SUM(CASE WHEN status='CONFIRMED' AND reserved_at_utc IS NOT NULL AND settled_at_utc &gt;= reserved_at_utc THEN 1 ELSE 0 END),0) AS measured_count,
          AVG(CASE WHEN status='CONFIRMED' AND reserved_at_utc IS NOT NULL AND settled_at_utc &gt;= reserved_at_utc
            THEN TIMESTAMPDIFF(MICROSECOND,reserved_at_utc,settled_at_utc)/1000 ELSE NULL END) AS average_millis,
          COUNT(CASE WHEN status='CONFIRMED' AND usage_status IN ('COMPLETE','PARTIAL') AND input_tokens &gt;= 0 THEN 1 END) AS input_known,
          COUNT(CASE WHEN status='CONFIRMED' AND usage_status IN ('COMPLETE','PARTIAL') AND output_tokens &gt;= 0 THEN 1 END) AS output_known,
          COUNT(CASE WHEN status='CONFIRMED' AND usage_status IN ('COMPLETE','PARTIAL') AND total_tokens &gt;= 0 THEN 1 END) AS total_known,
          SUM(CASE WHEN status='CONFIRMED' AND usage_status IN ('COMPLETE','PARTIAL') AND input_tokens &gt;= 0 THEN input_tokens ELSE NULL END) AS input_subtotal,
          SUM(CASE WHEN status='CONFIRMED' AND usage_status IN ('COMPLETE','PARTIAL') AND output_tokens &gt;= 0 THEN output_tokens ELSE NULL END) AS output_subtotal,
          SUM(CASE WHEN status='CONFIRMED' AND usage_status IN ('COMPLETE','PARTIAL') AND total_tokens &gt;= 0 THEN total_tokens ELSE NULL END) AS total_subtotal
        FROM iqc_model_gateway_call WHERE 1=1
        """+RUN_SCOPE+"""
        </script>
        """)
    @Results(value={@Result(column="call_count",property="callCount"),@Result(column="measured_count",property="measuredCount"),
        @Result(column="average_millis",property="averageMillis"),@Result(column="input_known",property="inputKnown"),
        @Result(column="output_known",property="outputKnown"),@Result(column="total_known",property="totalKnown"),
        @Result(column="input_subtotal",property="inputSubtotal"),@Result(column="output_subtotal",property="outputSubtotal"),
        @Result(column="total_subtotal",property="totalSubtotal")})
    LatencyGroup runLatency(@Param("runs") List<RunReference> runs);


}
