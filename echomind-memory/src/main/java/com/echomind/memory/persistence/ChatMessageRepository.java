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

    long countByUserId(String userId);

    @org.springframework.data.jpa.repository.Query("""
        select m.userId, count(m)
        from ChatMessageEntity m
        group by m.userId
        """)
    List<Object[]> countByUser();

    @org.springframework.data.jpa.repository.Query("""
        select m.id
        from ChatMessageEntity m
        where m.userId = :userId
        """)
    List<Long> findIdsByUserId(@org.springframework.data.repository.query.Param("userId") String userId);

    /** 删除指定会话的所有消息。 */
    void deleteBySessionId(String sessionId);

    void deleteByUserIdAndSessionId(String userId, String sessionId);

    long deleteByUserId(String userId);

    /** 统计指定会话消息数量。 */
    long countBySessionId(String sessionId);

    long countByUserIdAndSessionId(String userId, String sessionId);
}
