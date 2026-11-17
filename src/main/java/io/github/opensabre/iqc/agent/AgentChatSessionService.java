package io.github.opensabre.iqc.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opensabre.iqc.agent.dao.AgentChatSessionMapper;
import io.github.opensabre.iqc.agent.dao.QualityAgentVersionMapper;
import io.github.opensabre.iqc.agent.model.AgentChatSession;
import io.github.opensabre.iqc.agent.model.QualityAgentVersion;
import io.github.opensabre.iqc.governance.IqcException;
import io.github.opensabre.iqc.shared.IqcDataScope;
import io.github.opensabre.iqc.shared.IqcPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Stores successful saved-version test conversations without holding a transaction during model calls. */
@Service
@RequiredArgsConstructor
public class AgentChatSessionService {
    private final AgentChatSessionMapper sessions;
    private final QualityAgentVersionMapper versions;
    private final AgentChatTestService chat;
    private final IqcDataScope scope;
    private final ObjectMapper json;

    /** Temporary configurations remain ephemeral; saved versions require server-verified history. */
    public ChatResult chat(AgentChatTestService.Request request) {
        if (request == null) throw IqcException.invalidArgument("请提供聊天请求");
        if (request.agentId() == null) {
            if (request.sessionId() != null) throw IqcException.invalidArgument("临时配置不能继续已保存会话");
            return result(chat.chat(request), null);
        }
        if (request.versionNo() == null || request.messages() == null || request.messages().isEmpty())
            throw IqcException.invalidArgument("请提供智能体版本和聊天消息");
        var version = versions.selectOne(Wrappers.<QualityAgentVersion>lambdaQuery()
                .eq(QualityAgentVersion::getAgentId, request.agentId())
                .eq(QualityAgentVersion::getVersionNo, request.versionNo()));
        if (version == null) throw IqcException.notFound("智能体版本不存在");
        String hash = configHash(version.getConfigJson());
        AgentChatSession session = request.sessionId() == null ? null : requireSession(request.agentId(), request.sessionId());
        List<SavedMessage> history = session == null ? new ArrayList<>() : new ArrayList<>(messages(session));
        if (session != null) {
            if (!Objects.equals(scope.owner(), session.getOwner())) throw IqcException.accessDenied("只能继续本人发起的会话");
            if (!Objects.equals(session.getVersionNo(), request.versionNo()) || !hash.equals(session.getConfigHash()))
                throw IqcException.invalidState("智能体配置已切换，请重新开始会话");
        }
        if (request.messages().size() != history.size() + 1)
            throw IqcException.invalidState("会话上下文不一致，请重新开始会话");
        for (int index = 0; index < history.size(); index++) {
            var saved = history.get(index); var supplied = request.messages().get(index);
            if (supplied == null || !Objects.equals(saved.role(), supplied.role()) || !Objects.equals(saved.content(), supplied.content()))
                throw IqcException.invalidState("会话上下文不一致，请重新开始会话");
        }
        LocalDateTime startedAt = LocalDateTime.now();
        var reply = chat.chat(request);
        LocalDateTime now = LocalDateTime.now();
        var user = request.messages().getLast();
        history.add(new SavedMessage("user", user.content(), startedAt.toString(), null, null, List.of()));
        history.add(new SavedMessage("assistant", reply.content(), now.toString(), reply.model(), reply.latencyMillis(), reply.calledTools()));
        String payload = writeMessages(history);
        if (session == null) {
            session = new AgentChatSession();
            session.setAgentId(request.agentId()); session.setVersionNo(request.versionNo()); session.setSource("TEST");
            String title = user.content().strip().replaceAll("\\s+", " ");
            // Truncate by code point to avoid splitting an emoji/supplementary character.
            session.setTitle(title.substring(0, title.offsetByCodePoints(0, Math.min(80, title.codePointCount(0, title.length())))));
            session.setOwner(scope.owner()); session.setOwnerGroupId(scope.groupId()); session.setConfigHash(hash);
            session.setMessageCount(history.size()); session.setMessagesJson(payload); session.setLastMessageTime(now);
            session.setCreatedTime(java.util.Date.from(startedAt.atZone(java.time.ZoneId.systemDefault()).toInstant()));
            session.setUpdatedTime(session.getCreatedTime());
            sessions.insert(session);
        } else {
            // One atomic update prevents concurrent replies from overwriting committed history.
            int updated = sessions.update(null, Wrappers.<AgentChatSession>lambdaUpdate()
                    .eq(AgentChatSession::getId, session.getId()).eq(AgentChatSession::getMessageCount, history.size() - 2)
                    .set(AgentChatSession::getMessagesJson, payload).set(AgentChatSession::getMessageCount, history.size())
                    .set(AgentChatSession::getLastMessageTime, now).set(AgentChatSession::getUpdatedTime, java.util.Date.from(now.atZone(java.time.ZoneId.systemDefault()).toInstant())));
            if (updated != 1) throw IqcException.invalidState("会话已更新，请重新开始会话");
        }
        return result(reply, session.getId());
    }

    /** Queries only summary columns; message bodies are loaded by the authorized detail endpoint. */
    public IqcPage<Summary> page(String agentId, long current, long size, String title, String owner,
                                 LocalDateTime startedFrom, LocalDateTime startedTo) {
        if (startedFrom != null && startedTo != null && startedFrom.isAfter(startedTo))
            throw IqcException.invalidArgument("开始时间不能晚于结束时间");
        var query = Wrappers.<AgentChatSession>lambdaQuery()
                .select(AgentChatSession::getId, AgentChatSession::getAgentId, AgentChatSession::getVersionNo,
                        AgentChatSession::getTitle, AgentChatSession::getSource, AgentChatSession::getOwner,
                        AgentChatSession::getMessageCount, AgentChatSession::getCreatedTime, AgentChatSession::getLastMessageTime)
                .eq(agentId != null && !agentId.isBlank(), AgentChatSession::getAgentId, agentId)
                .like(title != null && !title.isBlank(), AgentChatSession::getTitle, title)
                .like(owner != null && !owner.isBlank(), AgentChatSession::getOwner, owner)
                .ge(startedFrom != null, AgentChatSession::getCreatedTime, startedFrom)
                .le(startedTo != null, AgentChatSession::getCreatedTime, startedTo);
        if (!scope.canViewAll()) {
            String group = scope.groupId();
            query.and(q -> q.eq(AgentChatSession::getOwner, scope.owner())
                    .or(group != null, nested -> nested.eq(AgentChatSession::getOwnerGroupId, group)));
        }
        var page = sessions.selectPage(new Page<>(Math.max(1, current), Math.min(100, Math.max(1, size))),
                query.orderByDesc(AgentChatSession::getLastMessageTime).orderByDesc(AgentChatSession::getId));
        return new IqcPage<>(page.getRecords().stream().map(this::summary).toList(), page.getCurrent(), page.getSize(), page.getTotal());
    }

    /** Checks both the route's agent and the current user's data scope before returning any messages. */
    public Detail detail(String agentId, String sessionId) {
        var session = requireSession(agentId, sessionId);
        return new Detail(summary(session), messages(session));
    }

    private AgentChatSession requireSession(String agentId, String sessionId) {
        var session = sessions.selectById(sessionId);
        if (session == null || !Objects.equals(agentId, session.getAgentId())) throw IqcException.notFound("会话不存在");
        if (!scope.canView(session.getOwner(), session.getOwnerGroupId())) throw IqcException.accessDenied("无权查看该会话");
        return session;
    }

    private List<SavedMessage> messages(AgentChatSession session) {
        try { return json.readValue(session.getMessagesJson(), new TypeReference<>() {}); }
        catch (Exception error) { throw IqcException.invalidState("会话记录读取失败"); }
    }

    private String writeMessages(List<SavedMessage> messages) {
        try { return json.writeValueAsString(messages); }
        catch (Exception error) { throw IqcException.invalidState("会话记录保存失败"); }
    }

    private String configHash(String config) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(config.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw IqcException.invalidState("智能体配置读取失败"); }
    }

    private Summary summary(AgentChatSession s) {
        return new Summary(s.getId(), s.getAgentId(), s.getVersionNo(), s.getTitle(), s.getSource(), s.getOwner(),
                s.getMessageCount(), s.getCreatedTime() == null ? null : s.getCreatedTime().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(), s.getLastMessageTime());
    }

    private ChatResult result(AgentChatTestService.Result reply, String sessionId) {
        return new ChatResult(reply.content(), reply.latencyMillis(), reply.model(), reply.calledTools(), sessionId);
    }

    /** Public views intentionally omit credential-bearing configuration and internal persistence fields. */
    public record Summary(String id, String agentId, Integer versionNo, String title, String source, String owner,
                          Integer messageCount, LocalDateTime createdTime, LocalDateTime lastMessageTime) { }
    public record SavedMessage(String role, String content, String time, String model, Long latencyMillis, List<String> calledTools) { }
    public record Detail(Summary session, List<SavedMessage> messages) { }
    public record ChatResult(String content, long latencyMillis, String model, List<String> calledTools, String sessionId) { }
}
