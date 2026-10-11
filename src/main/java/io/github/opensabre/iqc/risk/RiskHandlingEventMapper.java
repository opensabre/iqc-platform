package io.github.opensabre.iqc.risk;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface RiskHandlingEventMapper {
    record Event(String version,String previousStatus,String status,String reason,String actor,LocalDateTime recordedAtUtc) { }
    @Select("<script>SELECT CAST(version AS CHAR) AS version,previous_status,status,reason,actor,recorded_at_utc FROM iqc_risk_handling_event WHERE risk_event_id=#{id}<if test='before != null'> AND version &lt; #{before}</if> ORDER BY version DESC LIMIT 51</script>")
    java.util.List<Event> page(@Param("id") String id,@Param("before") Long before);
    @Insert("INSERT INTO iqc_risk_handling_event(risk_event_id,version,previous_status,status,reason,actor,recorded_at_utc) VALUES(#{id},#{version},#{previous},#{next},#{reason},#{actor},#{now})")
    int append(@Param("id") String id,@Param("version") long version,@Param("previous") String previous,
               @Param("next") String next,@Param("reason") String reason,@Param("actor") String actor,@Param("now") LocalDateTime now);
}
