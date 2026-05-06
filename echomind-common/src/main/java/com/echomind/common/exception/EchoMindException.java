package com.echomind.common.exception;

/**
 * <h2>EchoMind 统一异常基类</h2>
 * EchoMind 平台上所有业务异常的公共父类，继承自 {@link RuntimeException}。
 *
 * <h3>设计决策</h3>
 * <p>
 * 选择继承 {@code RuntimeException}（非受检异常）而非 {@code Exception}（受检异常），
 * 是基于以下考量：
 * </p>
 * <ul>
 *   <li><b>简化调用链</b> —— 避免在每个方法签名上声明冗长的 {@code throws} 子句，
 *       尤其是穿过多层 Pipeline Stage 和跨模块调用时</li>
 *   <li><b>Spring 事务兼容</b> —— Spring 的声明式事务默认只对非受检异常回滚</li>
 *   <li><b>全局异常处理</b> —— 由 {@code @ControllerAdvice} 统一拦截并转换为
 *       RFC 7807 Problem Details 格式的 HTTP 响应，无需在 Controller 中逐层捕获</li>
 * </ul>
 *
 * <h3>子类体系</h3>
 * <pre>{@code
 * EchoMindException
 *   ├── SkillLoadException         技能加载失败
 *   ├── ModelRoutingException      LLM 模型路由失败
 *   ├── MemoryPersistenceException  记忆持久化失败
 *   └── MCPTransportException       MCP 协议传输失败
 * }</pre>
 * 每个子类对应平台上特定模块的异常场景，便于日志分类、监控告警和针对性处理。
 *
 * @see SkillLoadException
 * @see ModelRoutingException
 * @see MemoryPersistenceException
 * @see MCPTransportException
 */
public class EchoMindException extends RuntimeException {
    /**
     * 使用错误描述消息构造异常。
     *
     * @param message 人类可读的错误描述，将被记录到日志和 HTTP 响应体中
     */
    public EchoMindException(String message) {
        super(message);
    }

    /**
     * 使用错误描述消息和原始异常构造异常（异常链模式）。
     * 保留完整的因果链条，便于在日志中追踪问题的根本原因。
     *
     * @param message 人类可读的错误描述
     * @param cause   触发此异常的原始异常（可为 null）
     */
    public EchoMindException(String message, Throwable cause) {
        super(message, cause);
    }
}
