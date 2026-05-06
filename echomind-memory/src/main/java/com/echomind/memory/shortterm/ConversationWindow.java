package com.echomind.memory.shortterm;

import com.echomind.common.model.AgentMessage;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 对话窗口 —— Agent 短期记忆的核心数据结构。
 *
 * <p>职责：
 * 维护一个固定容量的双端队列（Deque），存储最近的对话消息。
 * 当消息数超过 {@link WindowConfig#getMaxMessages()} 上限时，
 * 自动从队列头部驱逐最旧的消息（FIFO 先进先出策略）。
 *
 * <h3>线程安全</h3>
 * 使用 {@link ConcurrentLinkedDeque} 作为底层容器，支持多线程并发读写，
 * 无需额外同步锁。适用于 Agent 流水线中多个阶段同时访问记忆的场景。
 *
 * <h3>容量管理</h3>
 * <ul>
 *   <li>{@link #add(AgentMessage)} —— 每次添加后自动检查容量，超出上限则驱逐队首元素。</li>
 *   <li>{@link #evictOldest(int)} —— 主动驱逐指定数量的最旧消息，
 *       返回被驱逐的消息列表供 {@code MemoryManager} 写入长期存储。</li>
 * </ul>
 *
 * <p>设计决策：
 * 使用 {@code ConcurrentLinkedDeque} 而非 {@code LinkedBlockingDeque}，
 * 因为短期记忆场景下不需要阻塞语义，优先考虑无锁的高并发读性能。
 *
 * @author EchoMind Team
 * @see WindowConfig
 * @see com.echomind.memory.MemoryManager
 * @since 1.0
 */
public class ConversationWindow {

    /**
     * 消息容器 —— 基于无锁并发双端队列。
     * 队尾为最新消息，队首为最旧消息。
     */
    private final Deque<AgentMessage> messages;
    /** 窗口配置 —— 控制最大消息数与驱逐数量 */
    private final WindowConfig config;

    /**
     * 构造指定容量的对话窗口。
     *
     * @param config 窗口配置（最大消息数等参数）
     */
    public ConversationWindow(WindowConfig config) {
        this.config = config;
        this.messages = new ConcurrentLinkedDeque<>();
    }

    /**
     * 在队尾添加一条消息，若窗口溢出则自动驱逐队首最旧消息。
     *
     * <p>此方法保证：调用后队列大小不会超过 {@link WindowConfig#getMaxMessages()}。
     *
     * @param message 要添加的 Agent 消息
     */
    public void add(AgentMessage message) {
        messages.addLast(message);
        while (messages.size() > config.getMaxMessages()) {
            messages.pollFirst();
        }
    }

    /**
     * 判断窗口是否已达到最大容量。
     *
     * @return 若当前消息数大于等于配置的最大消息数则返回 true
     */
    public boolean isFull() {
        return messages.size() >= config.getMaxMessages();
    }

    /**
     * 从队首驱逐指定数量的最旧消息。
     *
     * <p>如果队列中消息数少于请求驱逐的数量，则返回实际可驱逐的消息数。
     *
     * @param count 要驱逐的消息数量（由 {@link WindowConfig#getEvictCount()} 提供）
     * @return 被驱逐的消息列表，按从旧到新的顺序排列；可能为空列表
     */
    public List<AgentMessage> evictOldest(int count) {
        List<AgentMessage> evicted = new ArrayList<>();
        for (int i = 0; i < count && !messages.isEmpty(); i++) {
            AgentMessage msg = messages.pollFirst();
            if (msg != null) evicted.add(msg);
        }
        return evicted;
    }

    /**
     * 获取当前窗口内所有消息的快照（不可变列表）。
     *
     * @return 消息的不可变视图，按从旧到新排列
     */
    public List<AgentMessage> getMessages() {
        return List.copyOf(messages);
    }

    /**
     * 查询当前窗口中的消息数量。
     *
     * @return 当前消息数
     */
    public int size() {
        return messages.size();
    }

    /**
     * 清空窗口中的所有消息。
     */
    public void clear() {
        messages.clear();
    }
}
