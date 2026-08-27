package io.github.opensabre.iqc.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.agent.model.QualityAgentVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QualityAgentVersionMapper extends BaseMapper<QualityAgentVersion> { }
