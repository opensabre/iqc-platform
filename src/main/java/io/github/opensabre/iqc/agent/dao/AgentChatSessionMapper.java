package io.github.opensabre.iqc.agent.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.opensabre.iqc.agent.model.AgentChatSession;
import org.apache.ibatis.annotations.Mapper;

/** IQC-owned persistence for agent chat sessions. */
@Mapper
public interface AgentChatSessionMapper extends BaseMapper<AgentChatSession> { }
