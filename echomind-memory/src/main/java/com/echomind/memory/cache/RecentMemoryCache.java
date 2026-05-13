package com.echomind.memory.cache;

import com.echomind.common.model.AgentMessage;

import java.util.List;
import java.util.Set;

/**
 * 近期记忆缓存。
 *
 * <p>缓存只保存最近 N 条消息，用来快速拼装提示词上下文；
 * 完整历史必须回 MySQL 读取，避免 Redis 又变成第二份长期数据。</p>
 */
public interface RecentMemoryCache {

    /** 追加一条消息，并由实现负责裁剪到最大窗口。 */
    void append(String sessionId, AgentMessage message);

    /** 读取最近消息，按时间升序返回。 */
    List<AgentMessage> recent(String sessionId);

    /** 清除一个会话的近期缓存。 */
    void clear(String sessionId);

    /** 当前缓存里可见的会话 ID。 */
    Set<String> sessionIds();

    /** 当前会话的缓存消息数量。 */
    int size(String sessionId);
}
