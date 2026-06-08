package com.echomind.console.deadletter;

import com.echomind.agent.messaging.RabbitReliableMessaging;
import com.echomind.common.messaging.ChatMemoryShardSupport;
import com.echomind.common.model.ChatMemoryPersistEvent;
import com.echomind.common.model.ChatRequest;
import com.echomind.common.model.ChatStreamEvent;
import com.echomind.common.model.UserMemoryEvent;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.config.RabbitMQConfig;
import com.echomind.console.reservation.TokenEstimator;
import com.echomind.console.service.ChatGovernanceService;
import com.echomind.console.service.SsePushService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

/**
 * RabbitMQ 死信队列处理服务
 *
 * <p>负责处理进入死信队列（DLQ）的消息：
 * <ol>
 *   <li>归档：将死信消息持久化到数据库，便于追溯和分析</li>
 *   <li>补偿：对聊天请求类型的死信进行资源释放（如用户配额）</li>
 *   <li>重放：支持将死信消息重新发送到原始队列进行重试</li>
 * </ol>
 *
 * <h2>死信类型</h2>
 * <ul>
 *   <li>{@code CHAT_REQUEST} - 聊天请求消息</li>
 *   <li>{@code CHAT_MEMORY} - 聊天记忆持久化消息</li>
 *   <li>{@code USER_MEMORY} - 用户记忆消息</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RabbitDeadLetterService {

    // ==================== 死信类型常量 ====================

    /** 聊天请求类型死信 */
    static final String TYPE_CHAT_REQUEST = "CHAT_REQUEST";

    /** 聊天记忆类型死信 */
    static final String TYPE_CHAT_MEMORY = "CHAT_MEMORY";

    /** 用户记忆类型死信 */
    static final String TYPE_USER_MEMORY = "USER_MEMORY";

    /** 未知类型死信 */
    static final String TYPE_UNKNOWN = "UNKNOWN";

    /** DLQ 失败消息提示 */
    private static final String DLQ_FAILURE_MESSAGE = "消息处理失败，已进入 RabbitMQ DLQ，等待运维处理";

    // ==================== 依赖注入 ====================

    /** 死信数据访问层 */
    private final RabbitDeadLetterMapper mapper;

    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper;

    /** 聊天治理服务（用于配额管理） */
    private final ChatGovernanceService governanceService;

    /** SSE 推送服务（用于通知前端） */
    private final SsePushService ssePushService;

    /** RabbitMQ 模板（用于消息重放） */
    private final RabbitTemplate rabbitTemplate;

    // ==================== 配置参数 ====================

    /** 聊天记忆持久化队列名称 */
    @Value(RabbitMQConfig.CHAT_MEMORY_PERSIST_QUEUE_PROPERTY)
    private String chatMemoryQueueName;

    /** 聊天记忆持久化交换机名称 */
    @Value("${echomind.memory.persist-exchange-name:" + ChatMemoryShardSupport.DEFAULT_EXCHANGE_NAME + "}")
    private String chatMemoryExchangeName;

    /** 聊天记忆分片数量 */
    @Value(RabbitMQConfig.CHAT_MEMORY_PERSIST_SHARDS_PROPERTY)
    private int chatMemoryShardCount;

    /** 用户记忆队列名称 */
    @Value("${echomind.user-memory.queue-name:echomind.user-memory.requests}")
    private String userMemoryQueueName;

    // ==================== 公共方法 ====================

    /**
     * 归档死信消息并进行补偿处理
     *
     * <p>将死信消息持久化到数据库，并对聊天请求类型进行资源补偿。
     *
     * @param dlqName 死信队列名称
     * @param message RabbitMQ 消息对象
     * @return 归档后的实体
     */
    public RabbitDeadLetterEntity archiveAndCompensate(String dlqName, Message message) {
        // 归档死信消息
        RabbitDeadLetterEntity archived = archive(dlqName, message);
        
        // 如果是聊天请求类型且未补偿过，则进行补偿
        if (TYPE_CHAT_REQUEST.equals(archived.getMessageType())
            && !RabbitDeadLetterStatus.COMPENSATED.equals(archived.getStatus())) {
            compensateChatRequest(archived);
            mapper.updateStatus(archived.getId(), RabbitDeadLetterStatus.COMPENSATED);
            archived.setStatus(RabbitDeadLetterStatus.COMPENSATED);
        }
        return archived;
    }

    /**
     * 列出死信消息列表
     *
     * @param status 状态过滤（可选）
     * @param limit 返回数量限制（默认100，最大500）
     * @return 死信消息列表响应
     */
    public RabbitDeadLetterDtos.DeadLetterListResponse list(String status, Integer limit) {
        // 安全限制：最小1，最大500，默认100
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
        
        // 查询并转换为视图对象
        List<RabbitDeadLetterDtos.DeadLetterView> items = mapper.selectLatest(status, safeLimit)
            .stream()
            .map(RabbitDeadLetterDtos::view)
            .toList();
        return new RabbitDeadLetterDtos.DeadLetterListResponse(items);
    }

    /**
     * 重放死信消息
     *
     * <p>将死信消息重新发送到原始队列进行处理。
     * 同一个死信消息只能重放一次。
     *
     * @param id 死信消息ID
     * @return 重放结果响应
     * @throws IllegalArgumentException 如果死信不存在或已重放过
     */
    public RabbitDeadLetterDtos.DeadLetterReplayResponse replay(long id) {
        RabbitDeadLetterEntity entity = mapper.selectById(id);
        
        // 检查死信是否存在
        if (entity == null) {
            throw new IllegalArgumentException("dead letter not found: " + id);
        }
        
        // 检查是否已重放过
        if (RabbitDeadLetterStatus.REPLAYED.equals(entity.getStatus())) {
            throw new IllegalArgumentException("dead letter already replayed: " + id);
        }
        
        try {
            // 执行重放
            replayInternal(entity);
            
            // 更新状态为已重放
            mapper.markReplaySuccess(id, RabbitDeadLetterStatus.REPLAYED, Instant.now());
            
            // 返回更新后的实体
            return new RabbitDeadLetterDtos.DeadLetterReplayResponse(
                RabbitDeadLetterDtos.view(mapper.selectById(id)));
        } catch (RuntimeException e) {
            // 重放失败，记录失败状态
            mapper.markReplayFailure(id, RabbitDeadLetterStatus.REPLAY_FAILED, truncate(e.getMessage(), 1000));
            throw e;
        }
    }

    // ==================== 私有方法：归档 ====================

    /**
     * 归档死信消息到数据库
     *
     * <p>提取消息内容、计算消息哈希、解析业务字段，并插入数据库。
     * 支持幂等插入（通过消息哈希去重）。
     *
     * @param dlqName 死信队列名称
     * @param message RabbitMQ 消息对象
     * @return 归档后的实体
     */
    private RabbitDeadLetterEntity archive(String dlqName, Message message) {
        byte[] body = message == null ? new byte[0] : message.getBody();
        String payloadJson = payloadJson(body);
        
        // 构建实体
        RabbitDeadLetterEntity entity = new RabbitDeadLetterEntity();
        entity.setMessageHash(messageHash(dlqName, message, body));  // 用于去重
        entity.setDlqName(dlqName);
        entity.setMessageType(messageType(dlqName));
        entity.setPayloadJson(payloadJson);
        entity.setErrorHeadersJson(errorHeadersJson(message));
        entity.setStatus(RabbitDeadLetterStatus.ARCHIVED);
        entity.setArchivedAt(Instant.now());
        
        // 填充业务字段（requestId, sessionId, traceId等）
        fillBusinessFields(entity, payloadJson);
        
        // 幂等插入（如果已存在则忽略）
        mapper.insertIgnore(entity);
        
        // 查询已保存的实体
        RabbitDeadLetterEntity saved = mapper.selectByMessageHash(entity.getMessageHash());
        if (saved == null) {
            throw new IllegalStateException("failed to archive rabbit dead letter");
        }
        return saved;
    }

    /**
     * 补偿聊天请求死信
     *
     * <p>释放用户配额预留，并通知前端请求失败。
     *
     * @param entity 死信实体
     */
    private void compensateChatRequest(RabbitDeadLetterEntity entity) {
        ChatRequest request = read(entity.getPayloadJson(), ChatRequest.class);
        
        // 释放预留的用户配额和 Provider 预算
        if (request.userReservationIds() != null && !request.userReservationIds().isEmpty()) {
            governanceService.releaseReservations(request.userReservationIds());
        }
        if (request.providerReservationIds() != null && !request.providerReservationIds().isEmpty()) {
            governanceService.releaseReservations(request.providerReservationIds());
        }
        
        // 通知前端请求失败
        if (request.requestId() == null || request.requestId().isBlank()) {
            return;
        }
        ssePushService.pushEvent(ChatStreamEvent
            .failure(request.requestId(), DLQ_FAILURE_MESSAGE, request.traceId())
            .withTrace(request.traceId(), request.traceparent()));
    }

    // ==================== 私有方法：重放 ====================

    /**
     * 根据消息类型执行重放
     *
     * @param entity 死信实体
     */
    private void replayInternal(RabbitDeadLetterEntity entity) {
        switch (entity.getMessageType()) {
            case TYPE_CHAT_REQUEST -> replayChatRequest(entity);
            case TYPE_CHAT_MEMORY -> replayChatMemory(entity);
            case TYPE_USER_MEMORY -> replayUserMemory(entity);
            default -> throw new IllegalArgumentException("unsupported dead letter type: " + entity.getMessageType());
        }
    }

    /**
     * 重放聊天请求消息
     *
     * <p>重新预留用户配额，然后发送到聊天请求队列。
     *
     * @param entity 死信实体
     */
    private void replayChatRequest(RabbitDeadLetterEntity entity) {
        ChatRequest request = read(entity.getPayloadJson(), ChatRequest.class);
        
        // 重新预留用户配额（基于消息长度动态估算）
        long estimatedTokens = TokenEstimator.estimate(request.message());
        List<String> reservations = governanceService.reserveUserQuota(
            authUser(request.userId()), request.requestId(), estimatedTokens);
        if (reservations == null) {
            reservations = List.of();
        }
        
        // 构建重放请求（包含新的预留ID）
        ChatRequest replay = new ChatRequest(
            request.requestId(),
            request.userId(),
            request.agentId(),
            request.sessionId(),
            request.message(),
            request.modelId(),
            request.traceId(),
            request.traceparent(),
            request.attachments(),
            reservations
        );
        
        try {
            // 注册请求到 SSE 服务
            ssePushService.registerRequest(request.requestId(), request.userId());
            
            // 发送到聊天请求队列
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.QUEUE_CHAT_REQUESTS,
                replay,
                RabbitReliableMessaging.persistentMessage(),
                RabbitReliableMessaging.correlation("chat-request-replay", request.requestId())
            );
        } catch (RuntimeException e) {
            // 发送失败，清理资源
            ssePushService.discardRequest(request.requestId());
            governanceService.releaseReservations(reservations);
            throw e;
        }
    }

    /**
     * 重放聊天记忆持久化消息
     *
     * <p>根据 sessionId 计算分片，发送到对应的分片队列。
     *
     * @param entity 死信实体
     */
    private void replayChatMemory(RabbitDeadLetterEntity entity) {
        ChatMemoryPersistEvent event = read(entity.getPayloadJson(), ChatMemoryPersistEvent.class);
        
        // 验证 sessionId
        if (event.sessionId() == null || event.sessionId().isBlank()) {
            throw new IllegalArgumentException("chat memory dead letter missing sessionId");
        }
        
        // 计算分片索引
        int shard = ChatMemoryShardSupport.shardIndex(event.sessionId(), chatMemoryShardCount);
        
        // 发送到对应的分片队列
        rabbitTemplate.convertAndSend(
            chatMemoryExchangeName,
            ChatMemoryShardSupport.routingKey(shard),
            event,
            RabbitReliableMessaging.persistentMessage(),
            RabbitReliableMessaging.correlation("chat-memory-replay", event.sessionId())
        );
    }

    /**
     * 重放用户记忆消息
     *
     * @param entity 死信实体
     */
    private void replayUserMemory(RabbitDeadLetterEntity entity) {
        UserMemoryEvent event = read(entity.getPayloadJson(), UserMemoryEvent.class);
        
        // 发送到用户记忆队列
        rabbitTemplate.convertAndSend(
            userMemoryQueueName,
            event,
            RabbitReliableMessaging.persistentMessage(),
            RabbitReliableMessaging.correlation("user-memory-replay", event.sessionId())
        );
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 填充业务字段（requestId, sessionId, traceId）
     *
     * @param entity      死信实体
     * @param payloadJson 消息体 JSON
     */
    private void fillBusinessFields(RabbitDeadLetterEntity entity, String payloadJson) {
        try {
            switch (entity.getMessageType()) {
                case TYPE_CHAT_REQUEST -> {
                    ChatRequest request = objectMapper.readValue(payloadJson, ChatRequest.class);
                    entity.setBusinessKey(request.requestId());
                    entity.setTraceId(request.traceId());
                }
                case TYPE_CHAT_MEMORY -> {
                    ChatMemoryPersistEvent event = objectMapper.readValue(payloadJson, ChatMemoryPersistEvent.class);
                    entity.setBusinessKey(event.sessionId());
                    entity.setTraceId(event.traceId());
                }
                case TYPE_USER_MEMORY -> {
                    UserMemoryEvent event = objectMapper.readValue(payloadJson, UserMemoryEvent.class);
                    entity.setBusinessKey(event.sessionId());
                    entity.setTraceId(event.traceId());
                }
                default -> {
                    // 未知类型，不处理
                }
            }
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Failed to extract rabbit dead-letter business fields dlq={}: {}", entity.getDlqName(),
                e.getMessage());
        }
    }

    /**
     * 根据死信队列名称判断消息类型
     *
     * @param dlqName 死信队列名称
     * @return 消息类型
     */
    private String messageType(String dlqName) {
        if (RabbitReliableMessaging.CHAT_REQUESTS_DLQ.equals(dlqName)) {
            return TYPE_CHAT_REQUEST;
        }
        if (RabbitReliableMessaging.CHAT_MEMORY_PERSIST_DLQ.equals(dlqName)) {
            return TYPE_CHAT_MEMORY;
        }
        if (RabbitReliableMessaging.USER_MEMORY_DLQ.equals(dlqName)) {
            return TYPE_USER_MEMORY;
        }
        return TYPE_UNKNOWN;
    }

    /**
     * 将消息体转换为 JSON 字符串
     *
     * <p>如果已经是 JSON 格式直接返回，否则进行 Base64 编码。
     *
     * @param body 消息体字节数组
     * @return JSON 字符串
     */
    private String payloadJson(byte[] body) {
        if (body == null || body.length == 0) {
            return "{}";
        }
        String value = new String(body, StandardCharsets.UTF_8);
        
        // 如果已经是 JSON 格式，直接返回
        if (value.startsWith("{") || value.startsWith("[")) {
            return value;
        }
        
        // 否则进行 Base64 编码
        try {
            return objectMapper.writeValueAsString(Map.of("rawBase64", Base64.getEncoder().encodeToString(body)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize dead-letter payload", e);
        }
    }

    /**
     * 将消息头信息转换为 JSON 字符串
     *
     * @param message RabbitMQ 消息对象
     * @return JSON 字符串
     */
    private String errorHeadersJson(Message message) {
        try {
            return objectMapper.writeValueAsString(headerSnapshot(message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize dead-letter headers", e);
        }
    }

    /**
     * 提取消息头快照
     *
     * <p>包含 exchange、routingKey、messageId、correlationId 等关键信息。
     *
     * @param message RabbitMQ 消息对象
     * @return 消息头快照映射
     */
    private Map<String, Object> headerSnapshot(Message message) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (message == null || message.getMessageProperties() == null) {
            return snapshot;
        }
        
        MessageProperties properties = message.getMessageProperties();
        snapshot.put("receivedExchange", safe(properties.getReceivedExchange()));
        snapshot.put("receivedRoutingKey", safe(properties.getReceivedRoutingKey()));
        snapshot.put("consumerQueue", safe(properties.getConsumerQueue()));
        snapshot.put("messageId", safe(properties.getMessageId()));
        snapshot.put("correlationId", safe(properties.getCorrelationId()));
        snapshot.put("contentType", safe(properties.getContentType()));
        snapshot.put("redelivered", properties.isRedelivered());
        
        // 处理自定义 headers
        Map<String, Object> headers = new LinkedHashMap<>();
        properties.getHeaders().forEach((key, value) -> headers.put(key, safeHeaderValue(value)));
        snapshot.put("headers", headers);
        
        return snapshot;
    }

    /**
     * 计算消息哈希（用于去重）
     *
     * <p>基于 dlqName、messageId、correlationId 和消息体计算 SHA-256 哈希。
     *
     * @param dlqName 死信队列名称
     * @param message RabbitMQ 消息对象
     * @param body    消息体字节数组
     * @return SHA-256 哈希值（十六进制）
     */
    private String messageHash(String dlqName, Message message, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            
            // 添加 dlqName
            digest.update(safe(dlqName).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);  // 分隔符
            
            // 添加 messageId 和 correlationId
            if (message != null && message.getMessageProperties() != null) {
                MessageProperties properties = message.getMessageProperties();
                digest.update(safe(properties.getMessageId()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(safe(properties.getCorrelationId()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            
            // 添加消息体
            digest.update(body == null ? new byte[0] : body);
            
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("failed to hash dead-letter message", e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为指定类型的对象
     *
     * @param payloadJson JSON 字符串
     * @param type        目标类型
     * @return 反序列化后的对象
     */
    private <T> T read(String payloadJson, Class<T> type) {
        try {
            return objectMapper.readValue(payloadJson, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("dead-letter payload cannot be decoded as " + type.getSimpleName(), e);
        }
    }

    /**
     * 构建 AuthUser 对象
     *
     * @param userId 用户ID
     * @return AuthUser 对象
     */
    private AuthUser authUser(String userId) {
        String owner = userId == null || userId.isBlank() ? AuthUser.DEFAULT_USER_ID : userId;
        return new AuthUser(owner, owner, !AuthUser.DEFAULT_USER_ID.equals(owner));
    }

    /**
     * 安全处理消息头值
     *
     * <p>将复杂类型转换为可序列化的类型。
     *
     * @param value 原始值
     * @return 安全的值
     */
    private Object safeHeaderValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::safeHeaderValue).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, nested) -> safe.put(String.valueOf(key), safeHeaderValue(nested)));
            return safe;
        }
        return String.valueOf(value);
    }

    /**
     * 安全处理字符串（null 转为空字符串）
     *
     * @param value 原始值
     * @return 安全的字符串
     */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 截断字符串到指定长度
     *
     * @param value     原始字符串
     * @param maxLength 最大长度
     * @return 截断后的字符串
     */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
