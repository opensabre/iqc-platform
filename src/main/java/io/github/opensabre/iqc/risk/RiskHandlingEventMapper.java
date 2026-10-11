package io.github.opensabre.iqc.risk;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface RiskHandlingEventMapper {
    @Insert("INSERT INTO iqc_risk_handling_event(risk_event_id,version,previous_status,status,reason,actor,recorded_at_utc) VALUES(#{id},#{version},#{previous},#{next},#{reason},#{actor},#{now})")
    int append(@Param("id") String id,@Param("version") long version,@Param("previous") String previous,
               @Param("next") String next,@Param("reason") String reason,@Param("actor") String actor,@Param("now") LocalDateTime now);
}
