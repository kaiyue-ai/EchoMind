package com.echomind.llm.observer;

import java.time.Instant;

/**
 * Agent 调用上下文 —— 携带单次 Agent 方法调用的观测数据。
 *
 * <p>由 {@link AgentInvocationObserver} 在拦截被 {@link Observable} 标注的方法时
 * 创建和填充。记录调用的起止时间、输入输出和异常信息，用于性能分析和故障排查。
 *
 * <p><b>字段说明：</b>
 * <ul>
 *   <li><b>agentId：</b> 被调用的 Agent 标识（当前预留，后续可从上下文提取）</li>
 *   <li><b>startTime：</b> 方法调用开始时间（{@link Instant} 精度）</li>
 *   <li><b>inputMessage：</b> 方法的首个参数（字符串形式）</li>
 *   <li><b>outputMessage：</b> 方法的返回值（字符串形式），方法执行完成后填充</li>
 *   <li><b>error：</b> 异常信息，方法抛出异常时填充</li>
 * </ul>
 *
 * <p><b>设计决策：</b>
 * <ul>
 *   <li>{@code outputMessage} 和 {@code error} 使用 {@code volatile} 修饰，
 *       因为在 AOP 通知中可能在 try/catch 的不同分支被赋值，volatile 确保
 *       其他线程（如监控线程）能及时看到最新的值。</li>
 *   <li>这是一个可变的数据载体（非 record），因为在 AOP 拦截过程中需要逐步填充。
 *       其生命周期限于单次 AOP 通知调用，不跨线程共享。</li>
 * </ul>
 *
 * @see AgentInvocationObserver
 * @see Observable
 */
public class AgentInvocationContext {
    /** Agent 标识，当前预留 */
    private String agentId;

    /** 方法调用开始时间（UTC 时间点） */
    private Instant startTime;

    /** 方法的输入消息（首个参数转字符串） */
    private String inputMessage;

    /** 方法的输出消息（返回值转字符串），volatile 保证多线程可见性 */
    private volatile String outputMessage;

    /** 调用异常信息，volatile 保证多线程可见性 */
    private volatile String error;

    /** @return Agent 标识 */
    public String getAgentId() { return agentId; }
    /** @param agentId Agent 标识 */
    public void setAgentId(String agentId) { this.agentId = agentId; }

    /** @return 调用开始时间 */
    public Instant getStartTime() { return startTime; }
    /** @param startTime 调用开始时间 */
    public void setStartTime(Instant startTime) { this.startTime = startTime; }

    /** @return 输入消息 */
    public String getInputMessage() { return inputMessage; }
    /** @param inputMessage 输入消息 */
    public void setInputMessage(String inputMessage) { this.inputMessage = inputMessage; }

    /** @return 输出消息 */
    public String getOutputMessage() { return outputMessage; }
    /** @param outputMessage 输出消息 */
    public void setOutputMessage(String outputMessage) { this.outputMessage = outputMessage; }

    /** @return 异常信息，无异常时返回 {@code null} */
    public String getError() { return error; }
    /** @param error 异常信息 */
    public void setError(String error) { this.error = error; }
}
