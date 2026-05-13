package com.echomind.memory.persistence;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MessageAttachment;
import com.echomind.common.model.SessionSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
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
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<MessageAttachment>> ATTACHMENT_LIST_TYPE = new TypeReference<>() {};

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
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
        ChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseGet(() -> newSession(sessionId));
        if (agentId != null && !agentId.isBlank()) {
            session.setAgentId(agentId);
        }
        if ((session.getTitle() == null || session.getTitle().isBlank())
            && "user".equals(message.role())) {
            session.setTitle(truncate(message.content(), TITLE_MAX_CHARS));
        }
        session.setMessageCount((int) messageRepository.countBySessionId(sessionId) + 1);
        session.setLastActivity(message.timestamp() != null ? message.timestamp() : Instant.now());
        sessionRepository.save(session);

        ChatMessageEntity entity = toEntity(sessionId, message);
        ChatMessageEntity saved = messageRepository.save(entity);
        log.debug("Saved chat message session={} id={} role={}", sessionId, saved.getId(), saved.getRole());
        return saved;
    }

    /** 加载完整会话历史，按时间升序。 */
    @Transactional(readOnly = true)
    public List<AgentMessage> loadFullHistory(String sessionId) {
        return messageRepository.findBySessionIdOrderByTimestampAscIdAsc(sessionId).stream()
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
            messageRepository.findBySessionIdOrderByTimestampDescIdDesc(sessionId, PageRequest.of(0, limit)));
        Collections.reverse(rows);
        return rows.stream().map(this::toMessage).toList();
    }

    /** 读取会话摘要。 */
    @Transactional(readOnly = true)
    public String loadSummary(String sessionId) {
        return sessionRepository.findById(sessionId)
            .map(ChatSessionEntity::getSummary)
            .orElse(null);
    }

    /** 更新会话摘要。 */
    @Transactional
    public void updateSummary(String sessionId, String summary) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setSummary(summary);
            sessionRepository.save(session);
        });
    }

    /** 统计会话消息数量。 */
    @Transactional(readOnly = true)
    public long countMessages(String sessionId) {
        return messageRepository.countBySessionId(sessionId);
    }

    /** 删除会话和消息。向量索引由 MemoryEmbeddingService 统一清理。 */
    @Transactional
    public void deleteSession(String sessionId) {
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
    }

    /** 会话列表 DTO，供前端侧边栏展示。 */
    @Transactional(readOnly = true)
    public List<SessionSummary> listSessions() {
        return sessionRepository.findVisibleSessions("team-run-").stream()
            .map(this::toSummary)
            .toList();
    }

    private ChatSessionEntity newSession(String sessionId) {
        ChatSessionEntity entity = new ChatSessionEntity();
        entity.setSessionId(sessionId);
        entity.setMessageCount(0);
        entity.setLastActivity(Instant.now());
        return entity;
    }

    private ChatMessageEntity toEntity(String sessionId, AgentMessage message) {
        ChatMessageEntity entity = new ChatMessageEntity();
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
        String lastMessage = "";
        List<ChatMessageEntity> lastRows =
            messageRepository.findBySessionIdOrderByTimestampDescIdDesc(session.getSessionId(), PageRequest.of(0, 1));
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
}
