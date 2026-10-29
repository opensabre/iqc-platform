package io.github.opensabre.iqc.result.llm.gateway;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
@Mapper public interface GatewayCapacityMapper extends BaseMapper<GatewayCapacity> {
    @Insert("INSERT INTO iqc_model_gateway_capacity(model_key,occupied,concurrency_limit) VALUES(#{key},0,#{limit}) ON DUPLICATE KEY UPDATE model_key=model_key")
    int ensure(@Param("key") String key,@Param("limit") int limit);
}
