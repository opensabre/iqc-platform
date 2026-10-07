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
    @Select("""
        <script>
        SELECT pricing_currency AS currency, COUNT(*) AS call_count,
          SUM(CASE WHEN status='CONFIRMED' AND cost_status='CALCULATED_FROM_DECLARED_PRICE' AND calculated_cost IS NOT NULL
            AND pricing_currency IS NOT NULL THEN 1 ELSE 0 END) AS known_count,
          SUM(CASE WHEN status='CONFIRMED' AND cost_status='CALCULATED_FROM_DECLARED_PRICE' AND calculated_cost IS NOT NULL
            AND pricing_currency IS NOT NULL THEN calculated_cost ELSE NULL END) AS known_subtotal
        FROM iqc_model_gateway_call
        <where>
          <if test="modelKey != null">AND model_key=#{modelKey}</if>
          <if test="taskId != null">AND task_id=#{taskId}</if>
          <if test="executionId != null">AND execution_id=#{executionId}</if>
          <if test="status != null">AND status=#{status}</if>
        </where>
        GROUP BY pricing_currency ORDER BY pricing_currency
        </script>
        """)
    @Results({@Result(column="currency",property="currency"),@Result(column="call_count",property="callCount"),
        @Result(column="known_count",property="knownCount"),@Result(column="known_subtotal",property="knownSubtotal")})
    List<CostGroup> costGroups(@Param("modelKey") String modelKey,@Param("taskId") String taskId,
        @Param("executionId") String executionId,@Param("status") String status);
}
