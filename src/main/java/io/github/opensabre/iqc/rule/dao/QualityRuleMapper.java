package io.github.opensabre.iqc.rule.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.rule.model.QualityRule;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QualityRuleMapper extends BaseMapper<QualityRule> {
    /** Lock and read the latest definition in the caller's reference-writing transaction. */
    @org.apache.ibatis.annotations.Select("SELECT * FROM iqc_quality_rule WHERE id = #{id} FOR UPDATE")
    QualityRule lockById(@org.apache.ibatis.annotations.Param("id") String id);
}
