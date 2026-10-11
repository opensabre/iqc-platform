package io.github.opensabre.iqc.risk;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RiskEventMapper extends BaseMapper<RiskEvent> {
    record Reference(String id,String sourceResultId) { }
    @org.apache.ibatis.annotations.Select("<script>SELECT e.id,e.source_result_id FROM iqc_risk_event e JOIN iqc_inspection_conversation_result r ON BINARY r.id=BINARY e.source_result_id JOIN iqc_inspection_task t ON BINARY t.id=BINARY r.task_id AND BINARY t.created_by=BINARY e.created_by WHERE (#{all}=1 OR BINARY t.created_by=BINARY #{owner} OR (#{groupId} IS NOT NULL AND BINARY t.owner_group_id=BINARY #{groupId}))<if test='after != null'> AND e.id &gt; #{after}</if><if test='status != null'> AND e.status=#{status}</if><if test='risk != null'> AND e.risk_level=#{risk}</if> ORDER BY e.id ASC LIMIT 21</script>")
    java.util.List<Reference> center(@org.apache.ibatis.annotations.Param("all") boolean all,
            @org.apache.ibatis.annotations.Param("owner") String owner,@org.apache.ibatis.annotations.Param("groupId") String groupId,
            @org.apache.ibatis.annotations.Param("after") String after,@org.apache.ibatis.annotations.Param("status") String status,
            @org.apache.ibatis.annotations.Param("risk") String risk);
    @org.apache.ibatis.annotations.Update("UPDATE iqc_risk_event SET status=#{next},version=version+1,updated_by=#{actor},updated_time=#{now} WHERE id=#{id} AND source_result_id=#{resultId} AND version=#{version} AND status=#{previous} AND version<9223372036854775807")
    int handle(@org.apache.ibatis.annotations.Param("id") String id,@org.apache.ibatis.annotations.Param("resultId") String resultId,
               @org.apache.ibatis.annotations.Param("version") long version,@org.apache.ibatis.annotations.Param("previous") String previous,
               @org.apache.ibatis.annotations.Param("next") String next,@org.apache.ibatis.annotations.Param("actor") String actor,
               @org.apache.ibatis.annotations.Param("now") java.time.LocalDateTime now);
}
