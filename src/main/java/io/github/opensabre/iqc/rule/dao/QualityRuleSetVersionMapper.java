package io.github.opensabre.iqc.rule.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.rule.model.QualityRuleSetVersion;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QualityRuleSetVersionMapper extends BaseMapper<QualityRuleSetVersion> { }
