package io.github.opensabre.iqc.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentVersionMapper;
import io.github.opensabre.iqc.agent.model.QualityAgent;
import io.github.opensabre.iqc.agent.model.QualityAgentVersion;
import io.github.opensabre.iqc.governance.IqcException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QualityAgentServiceTest {
    @Mock QualityAgentMapper agentMapper;
    @Mock QualityAgentVersionMapper versionMapper;
    @Mock AgentAssetReferenceValidator assetReferenceValidator;

    @Test
    void rollbackRejectsHistoricalRuleConfiguration() {
        QualityAgent agent = agent("agent-1", "PUBLISHED", 3);
        QualityAgentVersion source = version("agent-1", 1, "PUBLISHED", "{\"schemaVersion\":\"2.0\",\"mode\":\"RULE_ONLY\"}");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        when(versionMapper.selectList(any())).thenReturn(List.of(source));
        when(versionMapper.selectOne(any())).thenReturn(source);
        assertThatThrownBy(() -> service().rollback("agent-1", 1)).hasMessageContaining("纯大模型");
    }

    @Test
    void rollbackRejectsWhenAnotherDraftIsOpen() {
        QualityAgent agent = agent("agent-1", "DRAFT", 2);
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        when(versionMapper.selectList(any())).thenReturn(List.of(version("agent-1", 2, "DRAFT", "{}")));

        assertThatThrownBy(() -> service().rollback("agent-1", 1))
                .isInstanceOf(IqcException.class)
                .hasMessageContaining("当前草稿");
    }

    @Test
    void compareReportsFieldAndNestedConfigurationChanges() {
        QualityAgent agent = agent("agent-1", "PUBLISHED", 2);
        QualityAgentVersion from = version("agent-1", 1, "PUBLISHED", "{\"model\":{\"temperature\":0.1},\"rules\":[\"r1\"]}");
        QualityAgentVersion to = version("agent-1", 2, "PUBLISHED", "{\"model\":{\"temperature\":0.3},\"rules\":[\"r1\",\"r2\"]}");
        to.setDescription("new description");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);
        when(versionMapper.selectOne(any())).thenReturn(from, to);

        QualityAgentService.AgentVersionComparison comparison = service().compare("agent-1", 1, 2);

        assertThat(comparison.changedFields()).containsExactly("description", "configJson");
        assertThat(comparison.changedConfigPaths()).containsExactly("$.model.temperature", "$.rules[1]");
    }

    @Test
    void disableOnlyAllowsPublishedAgent() {
        QualityAgent agent = agent("agent-1", "PUBLISHED", 1);
        when(agentMapper.selectById("agent-1")).thenReturn(agent);

        QualityAgent disabled = service().disable("agent-1");

        assertThat(disabled.getStatus()).isEqualTo("DISABLED");
        verify(agentMapper).updateById(agent);
    }

    @Test
    void createCanonicalizesMultiModelMcpSkillAndPromptConfiguration() throws Exception {
        String config = """
                {"schemaVersion":"3.0","systemPrompt":"默认提示词","primaryModelProfileId":"primary",
                 "fallbackModelProfileIds":["fallback"],"mcpServerIds":["knowledge"],"skillIds":["compliance"],"assetSnapshots":{"primaryModel":{"id":"untrusted"}}}
                """;
        QualityAgent created = service().create("多模型 Agent", "MULTI", "test", config);
        var json = new ObjectMapper().readTree(created.getConfigJson());
        assertThat(json.path("fallbackModelProfileIds")).hasSize(1);
        assertThat(json.path("mcpServerIds").get(0).asText()).isEqualTo("knowledge");
        assertThat(json.path("skillIds").get(0).asText()).isEqualTo("compliance");
        assertThat(json.path("assetSnapshots").isNull()).isTrue();
        verify(agentMapper).insert(created);
    }

    @Test
    void createRejectsLegacyConfiguration() {
        String config = """
                {"schemaVersion":"1.0","systemPrompt":"prompt","primaryModel":"missing",
                 "models":[{"id":"one","provider":"OPENAI","model":"gpt-5","temperature":0.2,"enabled":true}],
                 "mcpServers":[],"skills":[]}
                """;

        assertThatThrownBy(() -> service().create("Agent", "AGENT", null, config))
                .isInstanceOf(IqcException.class).hasMessageContaining("纯大模型");
    }

    private QualityAgentService service() {
        return new QualityAgentService(agentMapper, versionMapper, new ObjectMapper(), assetReferenceValidator);
    }

    private QualityAgent agent(String id, String status, int versionNo) {
        QualityAgent agent = new QualityAgent(); agent.setId(id); agent.setName("Agent"); agent.setCode("AGENT");
        agent.setDescription("old description"); agent.setStatus(status); agent.setVersionNo(versionNo); return agent;
    }

    private QualityAgentVersion version(String agentId, int versionNo, String status, String configJson) {
        QualityAgentVersion version = new QualityAgentVersion(); version.setAgentId(agentId); version.setVersionNo(versionNo);
        version.setName("Agent"); version.setCode("AGENT"); version.setDescription("old description");
        version.setStatus(status); version.setConfigJson(configJson); return version;
    }
}
