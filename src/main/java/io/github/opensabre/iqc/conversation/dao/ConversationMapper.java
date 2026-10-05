package io.github.opensabre.iqc.conversation.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.conversation.model.Conversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
    /** Uses only the service-owned wrapper, including its independently applied authorization scope. */
    @org.apache.ibatis.annotations.Select("SELECT COALESCE(SUM(OCTET_LENGTH(COALESCE(tags_json,''))+OCTET_LENGTH(COALESCE(business_fields_json,''))),0) FROM iqc_conversation ${ew.customSqlSegment}")
    long selectSelectorPayloadBytes(@org.apache.ibatis.annotations.Param(com.baomidou.mybatisplus.core.toolkit.Constants.WRAPPER)
            com.baomidou.mybatisplus.core.conditions.Wrapper<Conversation> wrapper);
}
