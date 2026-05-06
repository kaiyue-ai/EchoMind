package com.echomind.common.exception;

/**
 * <h2>模型路由异常</h2>
 * 当 {@code DynamicModelRouter} 无法为当前会话选择合适的 LLM 模型提供者时抛出。
 *
 * <h3>触发场景</h3>
 * <ul>
 *   <li>用户请求的模型在已注册的 {@code ModelProvider} 列表中不存在</li>
 *   <li>会话上下文中的模型偏好与实际可用的提供者不匹配</li>
 *   <li>提供者虽然已注册，但其能力集不支持当前任务类型（例如需要视觉能力但选了纯文本模型）</li>
 *   <li>所有匹配的提供者当前都处于不可用状态（例如 API 密钥未配、限流熔断）</li>
 * </ul>
 *
 * <h3>恢复机制</h3>
 * 路由器会尝试回退到默认模型；如果回退也失败，则抛出本异常。
 * 上层调用方（{@code AgentOrchestrator}）可以捕获此异常并向用户返回友好的错误提示，
 * 建议检查 API 密钥配置或切换其他可用模型。
 *
 * @see com.echomind.llm.DynamicModelRouter 动态模型路由器
 * @see com.echomind.llm.ProviderRegistry 模型提供者注册中心
 * @see com.echomind.llm.ModelProvider 模型提供者 SPI
 */
public class ModelRoutingException extends EchoMindException {
    /**
     * 使用错误描述消息构造路由异常。
     *
     * @param message 描述路由失败原因的详细信息
     */
    public ModelRoutingException(String message) {
        super(message);
    }

    /**
     * 使用错误消息和原始异常构造路由异常（异常链模式）。
     *
     * @param message 描述路由失败原因的详细信息
     * @param cause   触发路由失败的原始异常（例如 HTTP 连接超时、认证失败等）
     */
    public ModelRoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
