package io.github.opensabre.iqc.scheme.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.scheme.model.InspectionScheme;
import org.apache.ibatis.annotations.Mapper;

/** Persistence for business scheme drafts and their active release pointers. */
@Mapper
public interface InspectionSchemeMapper extends BaseMapper<InspectionScheme> { }
