package io.github.opensabre.iqc.finding.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.finding.model.FindingCatalog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FindingCatalogMapper extends BaseMapper<FindingCatalog> { }
