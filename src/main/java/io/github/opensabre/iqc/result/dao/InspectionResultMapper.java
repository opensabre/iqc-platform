package io.github.opensabre.iqc.result.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.result.model.InspectionResult;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InspectionResultMapper extends BaseMapper<InspectionResult> { }
