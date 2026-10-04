package io.github.opensabre.iqc.scheme.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.scheme.model.InspectionSchemeVersion;
import org.apache.ibatis.annotations.Mapper;

/** Version rows are inserted once and never overwritten by application services. */
@Mapper
public interface InspectionSchemeVersionMapper extends BaseMapper<InspectionSchemeVersion> { }
