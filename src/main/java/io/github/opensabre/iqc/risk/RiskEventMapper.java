package io.github.opensabre.iqc.risk;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RiskEventMapper extends BaseMapper<RiskEvent> {
    @org.apache.ibatis.annotations.Update("UPDATE iqc_risk_event SET status=#{next},version=version+1,updated_by=#{actor},updated_time=#{now} WHERE id=#{id} AND source_result_id=#{resultId} AND version=#{version} AND status=#{previous} AND version<9223372036854775807")
    int handle(@org.apache.ibatis.annotations.Param("id") String id,@org.apache.ibatis.annotations.Param("resultId") String resultId,
               @org.apache.ibatis.annotations.Param("version") long version,@org.apache.ibatis.annotations.Param("previous") String previous,
               @org.apache.ibatis.annotations.Param("next") String next,@org.apache.ibatis.annotations.Param("actor") String actor,
               @org.apache.ibatis.annotations.Param("now") java.time.LocalDateTime now);
}
