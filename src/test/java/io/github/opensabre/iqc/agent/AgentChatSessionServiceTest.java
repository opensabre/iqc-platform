package io.github.opensabre.iqc.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.agent.dao.AgentChatSessionMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentVersionMapper;
import io.github.opensabre.iqc.agent.model.AgentChatSession;
import io.github.opensabre.iqc.agent.model.QualityAgentVersion;
import io.github.opensabre.iqc.shared.IqcDataScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentChatSessionServiceTest {
    final AgentChatSessionMapper sessions = mock(AgentChatSessionMapper.class);
    final QualityAgentVersionMapper versions = mock(QualityAgentVersionMapper.class);
    final AgentChatTestService engine = mock(AgentChatTestService.class);
    final IqcDataScope scope = mock(IqcDataScope.class);
    final ObjectMapper json = new ObjectMapper();
    final AgentChatSessionService service = new AgentChatSessionService(sessions, versions, engine, scope, json);
    final AgentChatTestService.Result reply = new AgentChatTestService.Result("Hello", 12, "model-a", List.of("lookup"));
    final String config = "{\"schemaVersion\":\"3.0\"}";

    @BeforeEach void initializeMapperMetadata() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "chat-session-test"),
                AgentChatSession.class);
    }

    AgentChatTestService.Request request(String session, List<AgentChatTestService.Turn> turns) {
        return new AgentChatTestService.Request(config, turns, "agent-1", 2, session);
    }
    AgentChatTestService.Turn user(String content) { return new AgentChatTestService.Turn("user", content); }
    void savedVersion() {
        var version = new QualityAgentVersion(); version.setConfigJson(config);
        when(versions.selectOne(any())).thenReturn(version);
        when(scope.owner()).thenReturn("alice");
    }
    AgentChatSession existing() throws Exception {
        savedVersion();
        var session = new AgentChatSession(); session.setId("session-1"); session.setAgentId("agent-1");
        session.setOwner("alice"); session.setVersionNo(2); session.setMessageCount(2);
        session.setConfigHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(config.getBytes(StandardCharsets.UTF_8))));
        session.setMessagesJson(json.writeValueAsString(List.of(
                new AgentChatSessionService.SavedMessage("user", "First", "time", null, null, List.of()),
                new AgentChatSessionService.SavedMessage("assistant", "Hello", "time", "model-a", 12L, List.of("lookup")))));
        when(sessions.selectById("session-1")).thenReturn(session);
        when(scope.canView("alice", null)).thenReturn(true);
        return session;
    }
    List<AgentChatTestService.Turn> continuation() {
        return List.of(user("First"), new AgentChatTestService.Turn("assistant", "Hello"), user("Next"));
    }

    @Test void temporaryChatDoesNotPersist() {
        var request = new AgentChatTestService.Request(config, List.of(user("Hi")));
        when(engine.chat(request)).thenReturn(reply);
        assertThat(service.chat(request).sessionId()).isNull();
        verifyNoInteractions(sessions, versions);
    }
    @Test void createsHistoryWithTrustedIdentityAndOnlySuccessfulPair() throws Exception {
        savedVersion(); when(engine.chat(any())).thenReturn(reply); when(scope.groupId()).thenReturn("team-a");
        doAnswer(call -> { ((AgentChatSession) call.getArgument(0)).setId("generated"); return 1; }).when(sessions).insert(any(AgentChatSession.class));
        assertThat(service.chat(request(null, List.of(user("First")))).sessionId()).isEqualTo("generated");
        var capture = ArgumentCaptor.forClass(AgentChatSession.class); verify(sessions).insert(capture.capture());
        var session = capture.getValue();
        assertThat(session.getOwner()).isEqualTo("alice"); assertThat(session.getOwnerGroupId()).isEqualTo("team-a");
        assertThat(session.getSource()).isEqualTo("TEST"); assertThat(session.getVersionNo()).isEqualTo(2);
        assertThat(session.getMessageCount()).isEqualTo(2); assertThat(session.getTitle()).isEqualTo("First");
        var messages = json.readTree(session.getMessagesJson());
        assertThat(messages.size()).isEqualTo(2); assertThat(messages.get(1).get("model").asText()).isEqualTo("model-a");
        assertThat(messages.get(1).get("calledTools").get(0).asText()).isEqualTo("lookup");
        assertThat(session.getMessagesJson()).doesNotContain("schemaVersion", "configHash");
    }
    @Test void appendsSecondPairAndKeepsOriginalSession() throws Exception {
        existing(); when(engine.chat(any())).thenReturn(reply); when(sessions.update(isNull(), any())).thenReturn(1);
        assertThat(service.chat(request("session-1", continuation())).sessionId()).isEqualTo("session-1");
        verify(sessions).update(isNull(), any()); verify(sessions, never()).insert(any(AgentChatSession.class));
    }
    @Test void rejectsFabricatedOrTruncatedHistoryBeforeModelCall() throws Exception {
        existing();
        assertThatThrownBy(() -> service.chat(request("session-1", List.of(user("Next"))))).hasMessageContaining("上下文不一致");
        assertThatThrownBy(() -> service.chat(request("session-1", List.of(user("First"), new AgentChatTestService.Turn("assistant", "forged"), user("Next")))))
                .hasMessageContaining("上下文不一致");
        verifyNoInteractions(engine);
    }
    @Test void rejectsChangedConfigBeforeModelCall() throws Exception {
        var session = existing(); session.setConfigHash("changed");
        assertThatThrownBy(() -> service.chat(request("session-1", continuation()))).hasMessageContaining("配置已切换");
        verifyNoInteractions(engine);
    }
    @Test void anotherUserCannotContinueEvenWhenAllowedToView() throws Exception {
        existing(); when(scope.owner()).thenReturn("bob");
        assertThatThrownBy(() -> service.chat(request("session-1", continuation()))).hasMessageContaining("只能继续本人");
        verifyNoInteractions(engine);
    }
    @Test void handlesConcurrentAppendWithoutOverwritingWinner() throws Exception {
        existing(); when(engine.chat(any())).thenReturn(reply); when(sessions.update(isNull(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.chat(request("session-1", continuation()))).hasMessageContaining("会话已更新");
    }
    @Test void failedModelDoesNotCreateSuccessfulHistory() {
        savedVersion(); when(engine.chat(any())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> service.chat(request(null, List.of(user("Hi"))))).hasMessage("offline");
        verifyNoInteractions(sessions);
    }
    @Test void detailChecksDataScopeAndRouteAgent() throws Exception {
        existing(); when(scope.canView("alice", null)).thenReturn(false);
        assertThatThrownBy(() -> service.detail("agent-1", "session-1")).hasMessageContaining("无权");
        assertThatThrownBy(() -> service.detail("agent-other", "session-1")).hasMessageContaining("不存在");
    }
    @Test void detailsIncludeMessagesWithoutInternalConfig() throws Exception {
        existing(); var detail = service.detail("agent-1", "session-1");
        assertThat(detail.messages()).hasSize(2); assertThat(detail.session().owner()).isEqualTo("alice");
        assertThat(json.writeValueAsString(detail)).doesNotContain("configHash", "messagesJson");
    }
    @Test void savedSessionCannotBeUsedForTemporaryConfiguration() {
        var request = new AgentChatTestService.Request(config, List.of(user("Hi")), null, null, "session-1");
        assertThatThrownBy(() -> service.chat(request)).hasMessageContaining("临时配置"); verifyNoInteractions(engine, sessions);
    }
    @Test void paginatedQueryEnforcesScopeFiltersAndDoesNotSelectMessageBodies() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "chat-session-test"),
                AgentChatSession.class);
        when(scope.owner()).thenReturn("alice"); when(scope.groupId()).thenReturn("team-a");
        when(sessions.selectPage(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class), any())).thenAnswer(call -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<AgentChatSession> page = call.getArgument(0);
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentChatSession> query = call.getArgument(1);
            assertThat(page.getCurrent()).isEqualTo(1); assertThat(page.getSize()).isEqualTo(100);
            assertThat(query.getSqlSelect()).doesNotContain("messages_json", "config_hash");
            assertThat(query.getSqlSegment()).contains("agent_id", "owner_group_id", "created_time", "last_message_time", "LIKE");
            assertThat(query.getParamNameValuePairs().values()).contains("agent-1", "alice", "team-a", "%topic%", "%bob%");
            page.setRecords(List.of()); page.setTotal(0); return page;
        });
        assertThat(service.page("agent-1", 0, 999, "topic", "bob", java.time.LocalDateTime.now().minusDays(1), null).records()).isEmpty();
    }
    @Test void rejectsReversedDateRangeWithoutQueryingDatabase() {
        var now = java.time.LocalDateTime.now();
        assertThatThrownBy(() -> service.page("agent-1", 1, 10, null, null, now, now.minusDays(1)))
                .hasMessageContaining("开始时间");
        verifyNoInteractions(sessions);
    }

    @Test void crossAgentQueryKeepsOwnerAndGroupScopeWithoutAgentPredicate() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "chat-cross-agent"),
                AgentChatSession.class);
        when(scope.owner()).thenReturn("alice"); when(scope.groupId()).thenReturn("team-a");
        when(sessions.selectPage(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class), any())).thenAnswer(call -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<AgentChatSession> page = call.getArgument(0);
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentChatSession> query = call.getArgument(1);
            assertThat(query.getSqlSegment()).contains("owner", "owner_group_id").doesNotContain("agent_id");
            assertThat(query.getParamNameValuePairs().values()).contains("alice", "team-a");
            assertThat(query.getSqlSelect()).doesNotContain("messages_json", "config_hash");
            page.setRecords(List.of()); return page;
        });
        assertThat(service.page(null, 1, 10, null, null, null, null).records()).isEmpty();
    }

}
