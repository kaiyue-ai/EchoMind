package com.echomind.memory.shortterm;

/**
 * 短期记忆窗口配置 —— 控制 {@link ConversationWindow} 的行为参数。
 *
 * <p>核心参数说明：
 * <ul>
 *   <li><b>maxMessages（最大消息数）</b> —— 短期窗口的消息容量上限。
 *       默认值为 20。当消息数超过此值时，最早的消息将被驱逐。</li>
 *   <li><b>evictCount（驱逐数量）</b> —— 窗口满时一次性驱逐的消息数量。
 *       默认值为 10。采用批量驱逐策略而非逐条驱逐，以减少长期存储的写操作频率。</li>
 * </ul>
 *
 * <p>调优建议：
 * <ul>
 *   <li>maxMessages 越大，Agent 能"记住"的近期对话越多，但内存占用和 LLM Token 消耗也越高。</li>
 *   <li>evictCount 建议设置为 maxMessages 的 30%-50%，避免频繁触发长期存储写入。</li>
 *   <li>可通过 Spring Boot 配置属性进行外部化配置（参考 echomind-boot 模块）。</li>
 * </ul>
 *
 * <p>设计决策：
 * 使用简单的 POJO 而非 Spring {@code @ConfigurationProperties} 绑定，
 * 使该模块保持框架无关性（echomind-memory 模块不依赖 Spring）。
 *
 * @author EchoMind Team
 * @see ConversationWindow
 * @since 1.0
 */
public class WindowConfig {

    /**
     * 最大消息数 —— 短期窗口的容量上限。
     * 默认值 20，可根据 LLM 上下文窗口大小和延迟要求进行调整。
     */
    private int maxMessages = 20;

    /**
     * 窗口满时一次性驱逐的消息数量。
     * 默认值 10，即每次窗口满时驱逐一半左右的消息。
     */
    private int evictCount = 10;

    /** 使用默认参数构造配置（maxMessages=20, evictCount=10） */
    public WindowConfig() {}

    /**
     * 使用自定义参数构造配置。
     *
     * @param maxMessages 窗口最大消息数
     * @param evictCount  窗口满时驱逐数量
     */
    public WindowConfig(int maxMessages, int evictCount) {
        this.maxMessages = maxMessages;
        this.evictCount = evictCount;
    }

    /** @return 窗口最大消息数 */
    public int getMaxMessages() { return maxMessages; }
    /** @param maxMessages 窗口最大消息数 */
    public void setMaxMessages(int maxMessages) { this.maxMessages = maxMessages; }
    /** @return 窗口满时驱逐数量 */
    public int getEvictCount() { return evictCount; }
    /** @param evictCount 窗口满时驱逐数量 */
    public void setEvictCount(int evictCount) { this.evictCount = evictCount; }
}
