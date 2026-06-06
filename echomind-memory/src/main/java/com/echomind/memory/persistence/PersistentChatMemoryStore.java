package com.echomind.memory.persistence;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MessageAttachment;
import com.echomind.common.model.SessionSummary;
import com.echomind.memory.persistence.entity.ChatMessageEntity;
import com.echomind.memory.persistence.entity.ChatSessionEntity;
import com.echomind.memory.persistence.mapper.ChatMessageMapper;
import com.echomind.memory.persistence.mapper.ChatSessionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MySQL 会话记忆存储。
 *
 * <p>这个类只关心“正式历史怎么落库/怎么读回”，不参与提示词拼装。
 * 这样可以把页面历史、Redis 近期缓存、向量检索的职责拆开，避免所有模块互相调用。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class PersistentChatMemoryStore {

    private static final int TITLE_MAX_CHARS = 60;
    private static final int LAST_MESSAGE_MAX_CHARS = 100;
    private static final String TOOL_ROLE = "tool";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<MessageAttachment>> ATTACHMENT_LIST_TYPE = new TypeReference<>() {};

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ObjectMapper mapper;

    /**
     * 保存单条消息，并同步更新会话摘要字段之外的会话元数据。
     *
     * @param sessionId 会话 ID
     * @param agentId   Agent ID，可为空
     * @param message   要保存的消息
     * @return 入库后的消息实体，包含自增 ID
     */
    @Transactional
    public ChatMessageEntity saveMessage(String sessionId, String agentId, AgentMessage message) {
        return saveMessage("default", sessionId, agentId, message);
    }

    /** 保存单条用户会话消息，并同步更新会话元数据。 */
    @Transactional
    public ChatMessageEntity saveMessage(String userId, String sessionId, String agentId, AgentMessage message) {
        String owner = normalizeUserId(userId);
        ChatSessionEntity session = sessionMapper.selectByUserIdAndSessionId(owner, sessionId)
            .orElseGet(() -> newSession(owner, sessionId));
        session.setUserId(owner);
        if (agentId != null && !agentId.isBlank()) {
            session.setAgentId(agentId);
        }
        if ((session.getTitle() == null || session.getTitle().isBlank())
            && "user".equals(message.role())) {
            session.setTitle(truncate(message.content(), TITLE_MAX_CHARS));
        }
        session.setLastActivity(message.timestamp() != null ? message.timestamp() : Instant.now());

        ChatMessageEntity entity = toEntity(owner, sessionId, message);
        ChatMessageEntity saved = messageMapper.upsertById(entity);
        session.setMessageCount((int) messageMapper.countByUserIdAndSessionId(owner, sessionId));
        sessionMapper.upsertByUserIdAndSessionId(session);
        log.debug("Saved chat message user={} session={} id={} role={}", owner, sessionId, saved.getId(), saved.getRole());
        return saved;
    }

    /** 加载完整会话历史，按时间升序。 */
    @Transactional(readOnly = true)
    public List<AgentMessage> loadFullHistory(String sessionId) {
        return messageMapper.selectBySessionIdOrderByTimestampAscIdAsc(sessionId).stream()
            .map(this::toMessage)
            .toList();
    }

    /** 加载指定用户的完整会话历史，按时间升序。 */
    @Transactional(readOnly = true)
    public List<AgentMessage> loadFullHistory(String userId, String sessionId) {
        return messageMapper.selectByUserIdAndSessionIdOrderByTimestampAscIdAsc(normalizeUserId(userId), sessionId)
            .stream()
            .map(this::toMessage)
            .toList();
    }

    /** 加载最近 N 条消息，返回值按时间升序。 */
    @Transactional(readOnly = true)
    public List<AgentMessage> loadRecent(String sessionId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<ChatMessageEntity> rows = new ArrayList<>(
            messageMapper.selectBySessionIdOrderByTimestampDescIdDesc(sessionId, limit));
        Collections.reverse(rows);
        return rows.stream().map(this::toMessage).toList();
    }

    /** 读取会话摘要。 */
    @Transactional(readOnly = true)
    public String loadSummary(String sessionId) {
        return sessionMapper.selectByUserIdAndSessionId("default", sessionId)
            .map(ChatSessionEntity::getSummary)
            .orElse(null);
    }

    /** 更新会话摘要。 */
    @Transactional
    public void updateSummary(String sessionId, String summary) {
        sessionMapper.selectByUserIdAndSessionId("default", sessionId).ifPresent(session -> {
            session.setSummary(summary);
            sessionMapper.upsertByUserIdAndSessionId(session);
        });
    }

    /** 更新指定用户会话摘要。 */
    @Transactional
    public void updateSummary(String userId, String sessionId, String summary) {
        String owner = normalizeUserId(userId);
        sessionMapper.selectByUserIdAndSessionId(owner, sessionId)
            .ifPresent(session -> {
                session.setSummary(summary);
                sessionMapper.upsertByUserIdAndSessionId(session);
            });
    }

    /** 统计会话消息数量。 */
    @Transactional(readOnly = true)
    public long countMessages(String sessionId) {
        return messageMapper.countBySessionId(sessionId);
    }

    /** 统计指定用户会话消息数量。 */
    @Transactional(readOnly = true)
    public long countMessages(String userId, String sessionId) {
        return messageMapper.countByUserIdAndSessionId(normalizeUserId(userId), sessionId);
    }

    /** 删除会话和消息。Redis 近期缓存由 MemoryManager 统一清理。 */
    @Transactional
    public void deleteSession(String sessionId) {
        deleteSession("default", sessionId);
    }

    /** 删除指定用户的会话和消息。 */
    @Transactional
    public void deleteSession(String userId, String sessionId) {
        String owner = normalizeUserId(userId);
        messageMapper.deleteByUserIdAndSessionId(owner, sessionId);
        sessionMapper.deleteByUserIdAndSessionId(owner, sessionId);
    }

    /** 会话列表 DTO，供前端侧边栏展示。 */
    @Transactional(readOnly = true)
    public List<SessionSummary> listSessions() {
        return sessionMapper.selectVisibleSessions("team-run-").stream()
            .map(this::toSummary)
            .toList();
    }

    /** 用户会话列表 DTO，供前端侧边栏展示。 */
    @Transactional(readOnly = true)
    public List<SessionSummary> listSessions(String userId) {
        return sessionMapper.selectVisibleSessionsByUserId(normalizeUserId(userId), "team-run-").stream()
            .map(session -> toSummary(normalizeUserId(userId), session))
            .toList();
    }

    private ChatSessionEntity newSession(String userId, String sessionId) {
        ChatSessionEntity entity = new ChatSessionEntity();
        entity.setUserId(normalizeUserId(userId));
        entity.setSessionId(sessionId);
        entity.setMessageCount(0);
        entity.setLastActivity(Instant.now());
        return entity;
    }

    private ChatMessageEntity toEntity(String userId, String sessionId, AgentMessage message) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setUserId(normalizeUserId(userId));
        entity.setSessionId(sessionId);
        entity.setRole(message.role());
        entity.setContent(message.content());
        entity.setTimestamp(message.timestamp() != null ? message.timestamp() : Instant.now());
        entity.setMetadataJson(toJson(message.metadata()));
        entity.setAttachmentsJson(toJson(message.attachments()));
        return entity;
    }

    private AgentMessage toMessage(ChatMessageEntity entity) {
        return new AgentMessage(
            entity.getRole(),
            entity.getContent(),
            entity.getTimestamp(),
            readMetadata(entity.getMetadataJson()),
            readAttachments(entity.getAttachmentsJson())
        );
    }

    private SessionSummary toSummary(ChatSessionEntity session) {
        return toSummary(session.getUserId(), session);
    }

    private SessionSummary toSummary(String userId, ChatSessionEntity session) {
        String lastMessage = "";
        List<ChatMessageEntity> lastRows =
            messageMapper.selectByUserIdAndSessionIdAndRoleNotOrderByTimestampDescIdDesc(
                normalizeUserId(userId), session.getSessionId(), TOOL_ROLE, 1);
        if (!lastRows.isEmpty()) {
            lastMessage = truncate(lastRows.get(0).getContent(), LAST_MESSAGE_MAX_CHARS);
        }
        return new SessionSummary(
            session.getSessionId(),
            session.getMessageCount(),
            lastMessage,
            session.getLastActivity()
        );
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            ObjectMapper safeMapper = mapper.copy()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            return safeMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize memory field: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse message metadata json: {}", e.getMessage());
            return null;
        }
    }

    private List<MessageAttachment> readAttachments(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<MessageAttachment> attachments = mapper.readValue(json, ATTACHMENT_LIST_TYPE);
            return attachments == null || attachments.isEmpty() ? null : attachments;
        } catch (Exception e) {
            log.warn("Failed to parse message attachments json: {}", e.getMessage());
            return null;
        }
    }

    private String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }
}
