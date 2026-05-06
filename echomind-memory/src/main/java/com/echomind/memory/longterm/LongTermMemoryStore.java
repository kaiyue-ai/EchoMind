package com.echomind.memory.longterm;

import com.echomind.common.model.AgentMessage;
import java.util.List;

/**
 * 长期记忆存储接口 —— 定义 Agent 长期记忆的持久化契约。
 *
 * <p>此接口是记忆系统与外部持久化层的边界（SPI），所有长期记忆实现
 * 都必须实现此接口。平台提供两种内置实现：
 * <ul>
 *   <li>{@link FileLongTermStore} —— 基于本地 JSON 文件存储</li>
 *   <li>{@code RedisLongTermStore} —— 基于 Redis 的分布式存储（可选模块）</li>
 * </ul>
 *
 * <p>数据流：
 * 当 {@link com.echomind.memory.shortterm.ConversationWindow} 满时，
 * {@link com.echomind.memory.MemoryManager} 调用 {@link #save(String, List)}
 * 将驱逐的消息持久化到长期存储中。
 *
 * <p>设计决策：
 * <ul>
 *   <li>接口式设计 —— 用户可根据需求切换存储后端（文件、Redis、数据库等），
 *       无需修改记忆管理逻辑。</li>
 *   <li>批量保存 —— save 方法接收消息列表而非单条消息，
 *       支持驱逐策略的批量写入优化。</li>
 *   <li>关键词查询 —— query 方法提供基于内容关键词的简单检索能力，
 *       便于 Agent 回忆历史信息。</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see FileLongTermStore
 * @see com.echomind.memory.MemoryManager
 * @since 1.0
 */
public interface LongTermMemoryStore {

    /**
     * 将消息列表保存到长期存储中。
     *
     * <p>实现应采用追加模式：如果该会话已有历史数据，新消息应追加到已有数据之后，
     * 而不是覆盖已有数据。
     *
     * @param sessionId 会话唯一标识，用于隔离不同会话的存储空间
     * @param messages  要持久化的消息列表
     */
    void save(String sessionId, List<AgentMessage> messages);

    /**
     * 加载指定会话的全部长期记忆。
     *
     * @param sessionId 会话唯一标识
     * @return 该会话的全部历史消息列表；如果会话不存在或存储为空，返回空列表
     */
    List<AgentMessage> load(String sessionId);

    /**
     * 按关键词查询指定会话的长期记忆。
     *
     * <p>匹配逻辑：对消息内容进行大小写不敏感的包含匹配。
     * 具体实现可根据存储后端特性使用全文索引等优化手段。
     *
     * @param sessionId 会话唯一标识
     * @param keyword   搜索关键词（大小写不敏感）
     * @return 匹配的消息列表；无匹配项时返回空列表
     */
    List<AgentMessage> query(String sessionId, String keyword);

    /**
     * 删除指定会话的全部长期记忆数据。
     *
     * <p>操作应为幂等的：对不存在的会话不抛出异常。
     *
     * @param sessionId 要删除的会话唯一标识
     */
    void delete(String sessionId);
}
