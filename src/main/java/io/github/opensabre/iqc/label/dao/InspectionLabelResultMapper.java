package io.github.opensabre.iqc.label.dao;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.label.model.InspectionLabelResult;
import org.apache.ibatis.annotations.Mapper;
@Mapper
public interface InspectionLabelResultMapper extends BaseMapper<InspectionLabelResult> {
    @org.apache.ibatis.annotations.Select("SELECT COALESCE(SUM(OCTET_LENGTH(COALESCE(value_json,''))),0) FROM iqc_inspection_label_result ${ew.customSqlSegment}")
    long selectSelectorPayloadBytes(@org.apache.ibatis.annotations.Param(com.baomidou.mybatisplus.core.toolkit.Constants.WRAPPER)
            com.baomidou.mybatisplus.core.conditions.Wrapper<InspectionLabelResult> wrapper);
}
