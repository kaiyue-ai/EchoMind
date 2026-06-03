package com.echomind.memory.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.memory.persistence.entity.ChatSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 会话记忆主表 Mapper，对应表 {@code echomind_chat_sessions}。 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSessionEntity> {

    default ChatSessionEntity upsertByUserIdAndSessionId(ChatSessionEntity entity) {
        if (entity.getUserId() == null || entity.getUserId().isBlank()) {
            entity.setUserId("default");
        }
        Instant now = Instant.now();
        if (entity.getLastActivity() == null) {
            entity.setLastActivity(now);
        }
        Optional<ChatSessionEntity> existing = selectByUserIdAndSessionId(entity.getUserId(), entity.getSessionId());
        if (existing.isPresent()) {
            update(entity, Wrappers.lambdaUpdate(ChatSessionEntity.class)
                .eq(ChatSessionEntity::getUserId, entity.getUserId())
                .eq(ChatSessionEntity::getSessionId, entity.getSessionId()));
        } else {
            insert(entity);
        }
        return entity;
    }

    /** 会话列表按最近活跃时间倒序展示。 */
    default List<ChatSessionEntity> selectAllOrderByLastActivityDesc() {
        return selectList(Wrappers.lambdaQuery(ChatSessionEntity.class)
            .orderByDesc(ChatSessionEntity::getLastActivity));
    }

    default List<ChatSessionEntity> selectByUserIdOrderByLastActivityDesc(String userId) {
        return selectList(Wrappers.lambdaQuery(ChatSessionEntity.class)
            .eq(ChatSessionEntity::getUserId, userId)
            .orderByDesc(ChatSessionEntity::getLastActivity));
    }

    default Optional<ChatSessionEntity> selectByUserIdAndSessionId(String userId, String sessionId) {
        return Optional.ofNullable(selectOne(Wrappers.lambdaQuery(ChatSessionEntity.class)
            .eq(ChatSessionEntity::getUserId, userId)
            .eq(ChatSessionEntity::getSessionId, sessionId)
            .last("limit 1")));
    }

    default long countByUserId(String userId) {
        return selectCount(Wrappers.lambdaQuery(ChatSessionEntity.class)
            .eq(ChatSessionEntity::getUserId, userId));
    }

    @Select("""
        select user_id as userId, count(*) as countValue
        from echomind_chat_sessions
        group by user_id
        """)
    List<Map<String, Object>> countByUserRows();

    default List<Object[]> countByUser() {
        return countByUserRows().stream()
            .map(row -> new Object[]{row.get("userId"), row.get("countValue")})
            .toList();
    }

    default List<String> selectSessionIdsByUserId(String userId) {
        return selectList(Wrappers.lambdaQuery(ChatSessionEntity.class)
            .select(ChatSessionEntity::getSessionId)
            .eq(ChatSessionEntity::getUserId, userId))
            .stream()
            .map(ChatSessionEntity::getSessionId)
            .toList();
    }

    default void deleteByUserIdAndSessionId(String userId, String sessionId) {
        delete(Wrappers.lambdaQuery(ChatSessionEntity.class)
            .eq(ChatSessionEntity::getUserId, userId)
            .eq(ChatSessionEntity::getSessionId, sessionId));
    }

    default long deleteByUserId(String userId) {
        return delete(Wrappers.lambdaQuery(ChatSessionEntity.class)
            .eq(ChatSessionEntity::getUserId, userId));
    }

    /** 会话列表按最近活跃时间倒序展示，排除指定前缀的内部会话。 */
    default List<ChatSessionEntity> selectVisibleSessions(String prefix) {
        return selectList(Wrappers.lambdaQuery(ChatSessionEntity.class)
            .notLikeRight(ChatSessionEntity::getSessionId, prefix)
            .orderByDesc(ChatSessionEntity::getLastActivity));
    }

    /** 用户会话列表按最近活跃时间倒序展示，排除指定前缀的内部会话。 */
    default List<ChatSessionEntity> selectVisibleSessionsByUserId(String userId, String prefix) {
        return selectList(Wrappers.lambdaQuery(ChatSessionEntity.class)
            .eq(ChatSessionEntity::getUserId, userId)
            .notLikeRight(ChatSessionEntity::getSessionId, prefix)
            .orderByDesc(ChatSessionEntity::getLastActivity));
    }
}
