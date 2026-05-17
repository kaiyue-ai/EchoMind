package com.echomind.memory.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 会话消息 Repository。 */
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    /** 加载完整历史，供前端历史记录接口使用。 */
    List<ChatMessageEntity> findBySessionIdOrderByTimestampAscIdAsc(String sessionId);

    List<ChatMessageEntity> findByUserIdAndSessionIdOrderByTimestampAscIdAsc(String userId, String sessionId);

    /** 加载最近 N 条消息，供 Redis 缓存缺失时回源。 */
    List<ChatMessageEntity> findBySessionIdOrderByTimestampDescIdDesc(String sessionId, Pageable pageable);

    List<ChatMessageEntity> findByUserIdAndSessionIdOrderByTimestampDescIdDesc(String userId, String sessionId,
                                                                               Pageable pageable);

    /** 删除指定会话的所有消息。 */
    void deleteBySessionId(String sessionId);

    void deleteByUserIdAndSessionId(String userId, String sessionId);

    /** 统计指定会话消息数量。 */
    long countBySessionId(String sessionId);

    long countByUserIdAndSessionId(String userId, String sessionId);
}
