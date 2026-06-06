package com.echomind.memory.persistence.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import com.echomind.memory.persistence.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 会话消息 Mapper，对应表 {@code echomind_chat_messages}。 */
@Mapper
public interface ChatMessageMapper extends MybatisPlusMapper<ChatMessageEntity> {

    /** 加载完整历史，供前端历史记录接口使用。 */
    default List<ChatMessageEntity> selectBySessionIdOrderByTimestampAscIdAsc(String sessionId) {
        return selectList(Wrappers.lambdaQuery(ChatMessageEntity.class)
            .eq(ChatMessageEntity::getSessionId, sessionId)
            .orderByAsc(ChatMessageEntity::getTimestamp)
            .orderByAsc(ChatMessageEntity::getId));
    }

    default List<ChatMessageEntity> selectByUserIdAndSessionIdOrderByTimestampAscIdAsc(String userId, String sessionId) {
        return selectList(Wrappers.lambdaQuery(ChatMessageEntity.class)
            .eq(ChatMessageEntity::getUserId, userId)
            .eq(ChatMessageEntity::getSessionId, sessionId)
            .orderByAsc(ChatMessageEntity::getTimestamp)
            .orderByAsc(ChatMessageEntity::getId));
    }

    /** 加载最近 N 条消息，供 Redis 缓存缺失时回源。 */
    default List<ChatMessageEntity> selectBySessionIdOrderByTimestampDescIdDesc(String sessionId, int limit) {
        return selectList(Wrappers.lambdaQuery(ChatMessageEntity.class)
            .eq(ChatMessageEntity::getSessionId, sessionId)
            .orderByDesc(ChatMessageEntity::getTimestamp)
            .orderByDesc(ChatMessageEntity::getId)
            .last("limit " + Math.max(1, limit)));
    }

    default List<ChatMessageEntity> selectByUserIdAndSessionIdOrderByTimestampDescIdDesc(String userId, String sessionId,
                                                                                       int limit) {
        return selectList(Wrappers.lambdaQuery(ChatMessageEntity.class)
            .eq(ChatMessageEntity::getUserId, userId)
            .eq(ChatMessageEntity::getSessionId, sessionId)
            .orderByDesc(ChatMessageEntity::getTimestamp)
            .orderByDesc(ChatMessageEntity::getId)
            .last("limit " + Math.max(1, limit)));
    }

    default List<ChatMessageEntity> selectByUserIdAndSessionIdAndRoleNotOrderByTimestampDescIdDesc(
        String userId, String sessionId, String excludedRole, int limit) {
        return selectList(Wrappers.lambdaQuery(ChatMessageEntity.class)
            .eq(ChatMessageEntity::getUserId, userId)
            .eq(ChatMessageEntity::getSessionId, sessionId)
            .ne(ChatMessageEntity::getRole, excludedRole)
            .orderByDesc(ChatMessageEntity::getTimestamp)
            .orderByDesc(ChatMessageEntity::getId)
            .last("limit " + Math.max(1, limit)));
    }

    default long countByUserId(String userId) {
        return selectCount(Wrappers.lambdaQuery(ChatMessageEntity.class)
            .eq(ChatMessageEntity::getUserId, userId));
    }

    @Select("""
        select user_id as userId, count(*) as countValue
        from echomind_chat_messages
        group by user_id
        """)
    List<Map<String, Object>> countByUserRows();

    default List<Object[]> countByUser() {
        return countByUserRows().stream()
            .map(row -> new Object[]{row.get("userId"), row.get("countValue")})
            .toList();
    }

    default List<Long> selectIdsByUserId(String userId) {
        return selectList(Wrappers.lambdaQuery(ChatMessageEntity.class)
            .select(ChatMessageEntity::getId)
            .eq(ChatMessageEntity::getUserId, userId))
            .stream()
            .map(ChatMessageEntity::getId)
            .toList();
    }

    /** 删除指定会话的所有消息。 */
    default void deleteBySessionId(String sessionId) {
        delete(Wrappers.lambdaQuery(ChatMessageEntity.class)
            .eq(ChatMessageEntity::getSessionId, sessionId));
    }

    default void deleteByUserIdAndSessionId(String userId, String sessionId) {
        delete(Wrappers.lambdaQuery(ChatMessageEntity.class)
            .eq(ChatMessageEntity::getUserId, userId)
            .eq(ChatMessageEntity::getSessionId, sessionId));
    }

    default long deleteByUserId(String userId) {
        return delete(Wrappers.lambdaQuery(ChatMessageEntity.class)
            .eq(ChatMessageEntity::getUserId, userId));
    }

    /** 统计指定会话消息数量。 */
    default long countBySessionId(String sessionId) {
        return selectCount(Wrappers.lambdaQuery(ChatMessageEntity.class)
            .eq(ChatMessageEntity::getSessionId, sessionId));
    }

    default long countByUserIdAndSessionId(String userId, String sessionId) {
        return selectCount(Wrappers.lambdaQuery(ChatMessageEntity.class)
            .eq(ChatMessageEntity::getUserId, userId)
            .eq(ChatMessageEntity::getSessionId, sessionId));
    }
}
