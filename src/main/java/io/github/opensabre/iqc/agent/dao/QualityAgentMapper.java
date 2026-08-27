package io.github.opensabre.iqc.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.agent.model.QualityAgent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QualityAgentMapper extends BaseMapper<QualityAgent> { }
