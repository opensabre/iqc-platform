package io.github.opensabre.iqc.label.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.label.model.LabelActionDefinition;
import org.apache.ibatis.annotations.Mapper;

/** Persistence for existing-label action configuration. */
@Mapper
public interface LabelActionDefinitionMapper extends BaseMapper<LabelActionDefinition> { }
