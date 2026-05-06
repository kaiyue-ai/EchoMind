package com.echomind.llm.session;

import java.util.Map;

/**
 * 会话上下文 —— 承载单次对话会话的完整状态和用户偏好。
 *
 * <p>这是一个不可变的 {@code record}，在 LLM 层的路由和调用过程中传递。
 * 包含会话标识、用户指定的模型偏好以及可扩展的自定义属性。
 * 被 {@link com.echomind.llm.router.DynamicModelRouter} 用于做出路由决策。
 *
 * <p><b>核心字段说明：</b>
 * <ul>
 *   <li><b>sessionId：</b> 全局唯一的会话标识，贯穿整个请求生命周期。</li>
 *   <li><b>preferredProvider / preferredModel：</b> 用户可选的模型偏好，
 *        为 {@code null} 时表示未指定，路由器将使用默认值。</li>
 *   <li><b>attributes：</b> 可扩展的自定义属性映射，用于携带额外的元数据
 *        （如用户角色、地域、API 版本等），不影响路由逻辑。</li>
 * </ul>
 *
 * <p><b>设计决策：</b>
 * <ul>
 *   <li>使用 Java {@code record} 保证线程安全和不变量，方便在响应式流中传递。</li>
 *   <li>{@link #create(String)} 工厂方法创建最小化的上下文，无需显式传 null。</li>
 *   <li>{@link #withModel(String, String)} 返回新实例而非修改自身，
 *       确保不可变性，避免并发副作用。</li>
 * </ul>
 *
 * @param sessionId         会话唯一标识，不能为 {@code null}
 * @param preferredProvider 用户首选的模型提供商 ID，可为 {@code null}
 * @param preferredModel    用户首选的模型名称，可为 {@code null}
 * @param attributes        自定义属性映射，用于扩展上下文字段
 *
 * @see com.echomind.llm.router.DynamicModelRouter#resolve(SessionContext)
 */
public record SessionContext(
    String sessionId,
    String preferredProvider,
    String preferredModel,
    Map<String, Object> attributes
) {
    /**
     * 创建一个仅包含会话 ID 的最小化上下文。
     *
     * <p>所有偏好均为 {@code null}，属性映射为空。
     * 路由器将使用全局默认值完成后续解析。
     *
     * @param sessionId 会话唯一标识
     * @return 新的 SessionContext 实例
     */
    public static SessionContext create(String sessionId) {
        return new SessionContext(sessionId, null, null, Map.of());
    }

    /**
     * 基于当前上下文创建一个指定模型偏好的新上下文。
     *
     * <p>此方法不修改当前实例，而是返回一个全新的不可变实例。
     * 这在响应式编程和并发场景中尤为重要，避免了共享可变状态。
     *
     * @param providerId 目标提供商 ID
     * @param modelName  目标模型名称
     * @return 带有模型偏好的新 SessionContext 实例（原 attributes 保持不变）
     */
    public SessionContext withModel(String providerId, String modelName) {
        return new SessionContext(sessionId, providerId, modelName, attributes);
    }
}
