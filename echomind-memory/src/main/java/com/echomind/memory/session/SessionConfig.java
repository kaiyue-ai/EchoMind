package com.echomind.memory.session;

/**
 * 会话配置 —— 定义单个 Agent 会话的行为参数。
 *
 * <p>核心参数说明：
 * <ul>
 *   <li><b>ttlSeconds（生存时间）</b> —— 会话的最大存活时间（秒）。
 *       默认值为 3600（1 小时）。超过此时间的会话应被视为过期。</li>
 *   <li><b>maxWindows（最大记忆窗口数）</b> —— 会话允许的最大短期记忆窗口数量。
 *       默认值为 5。当窗口数超过此值时，最旧的窗口将被驱逐并写入长期存储。</li>
 *   <li><b>summarizerPrompt（摘要提示词）</b> —— 用于对话摘要生成的 LLM 提示词模板。
 *       当旧消息被驱逐到长期存储时，可先用 LLM 生成摘要以减少存储占用。</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 为特定会话创建定制配置
 * SessionConfig custom = new SessionConfig();
 * custom.setTtlSeconds(7200);      // 2 小时 TTL
 * custom.setMaxWindows(10);        // 允许 10 个记忆窗口
 * String sessionId = sessionManager.createSession(custom);
 * }</pre>
 *
 * <p>设计决策：
 * <ul>
 *   <li>拷贝构造器 —— {@link #SessionConfig(SessionConfig)} 用于从默认模板创建独立副本，
 *       避免多个会话共享同一个配置对象导致相互干扰。</li>
 *   <li>POJO 设计 —— 不依赖 Spring 配置绑定，保持模块的框架无关性。</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see SessionManager
 * @since 1.0
 */
public class SessionConfig {

    /**
     * 会话生存时间（秒）。
     * 默认值 3600 秒 = 1 小时。超过此时间的会话应被清理。
     */
    private long ttlSeconds = 3600;

    /**
     * 最大短期记忆窗口数。
     * 默认值 5。当窗口数超过此值时触发旧窗口驱逐。
     */
    private int maxWindows = 5;

    /**
     * 对话摘要提示词模板。
     * 默认值为 "Summarize the conversation so far in 2-3 sentences."，
     * 用于指引 LLM 生成简洁的对话摘要。
     */
    private String summarizerPrompt = "Summarize the conversation so far in 2-3 sentences.";

    /** 使用所有默认值构造配置 */
    public SessionConfig() {}

    /**
     * 拷贝构造器 —— 从另一个 SessionConfig 创建独立副本。
     *
     * <p>此构造器用于从默认配置模板创建新会话的独立配置，
     * 确保修改某个会话配置不会影响其他会话。
     *
     * @param other 源配置对象（不可为 null）
     */
    public SessionConfig(SessionConfig other) {
        this.ttlSeconds = other.ttlSeconds;
        this.maxWindows = other.maxWindows;
        this.summarizerPrompt = other.summarizerPrompt;
    }

    /** @return 会话生存时间（秒） */
    public long getTtlSeconds() { return ttlSeconds; }
    /** @param ttlSeconds 会话生存时间（秒） */
    public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    /** @return 最大短期记忆窗口数 */
    public int getMaxWindows() { return maxWindows; }
    /** @param maxWindows 最大短期记忆窗口数 */
    public void setMaxWindows(int maxWindows) { this.maxWindows = maxWindows; }
    /** @return 对话摘要提示词模板 */
    public String getSummarizerPrompt() { return summarizerPrompt; }
    /** @param summarizerPrompt 对话摘要提示词模板 */
    public void setSummarizerPrompt(String summarizerPrompt) { this.summarizerPrompt = summarizerPrompt; }
}
