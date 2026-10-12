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
    /** Called only after owning-service authorization; binds an unbound original revision without replacing bytes. */
    @org.apache.ibatis.annotations.Update("UPDATE iqc_conversation SET audio_sha256=#{hash},audio_size_bytes=#{size},audio_media_type=#{media},audio_duration_ms=#{duration} WHERE id=#{id} AND BINARY source_fingerprint=BINARY #{revision} AND audio_sha256 IS NULL AND audio_size_bytes IS NULL AND audio_media_type IS NULL AND audio_duration_ms IS NULL")
    int bindAudio(@org.apache.ibatis.annotations.Param("id") String id,
            @org.apache.ibatis.annotations.Param("revision") String revision,
            @org.apache.ibatis.annotations.Param("hash") String hash,
            @org.apache.ibatis.annotations.Param("size") long size,
            @org.apache.ibatis.annotations.Param("media") String media,
            @org.apache.ibatis.annotations.Param("duration") long duration);

}
