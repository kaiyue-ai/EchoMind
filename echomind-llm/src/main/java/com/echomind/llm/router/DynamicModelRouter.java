package com.echomind.llm.router;

import com.echomind.common.exception.ModelRoutingException;
import com.echomind.llm.session.SessionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
/**
 * 动态模型路由器，根据会话上下文解析最合适的模型规格。
 *
 * <p>模型路由器会根据会话上下文（用户偏好、当前会话提供商）
 * 确定最合适的模型规格，并返回给调用方。
 *
 * <p>模型路由器会根据会话上下文（用户偏好、当前会话提供商）
 * 确定最合适的模型规格，并返回给调用方。
 *
 *
 * @see ModelProviderRegistry
 * @see ModelSpec
 * @see ModelCapability
 * @see SessionContext
 */
@Slf4j
@RequiredArgsConstructor
// 这是所有大模型的路由器
public class DynamicModelRouter {

    /** 模型提供商注册表，持有所有已注册的模型和提供商信息 */
    private final ModelProviderRegistry registry;

    /**
     * 根据会话上下文解析最适合的模型规格。
     * @param ctx 会话上下文，包含用户的首选提供商和模型偏好
     * @return 匹配的模型规格
     * @throws ModelRoutingException 当无法找到任何可用模型时抛出
     */
    public ModelSpec resolve(SessionContext ctx) {
        // 获取用户偏好
        String preferredProvider = ctx.preferredProvider();
        // 获取用户偏好的模型
        String preferredModel = ctx.preferredModel();
        if (preferredProvider != null && preferredModel != null) {
            return registry.find(preferredProvider, preferredModel)
                .orElseThrow(() -> new ModelRoutingException(
                    "Preferred model not found: " + preferredProvider + "/" + preferredModel));
        }
        if (preferredProvider != null) {
            List<ModelSpec> providerModels = registry.listByProvider(preferredProvider);
            return providerModels.stream()
                .filter(ModelSpec::isDefault)
                .findFirst()
                .orElseGet(() -> providerModels.stream().findFirst()
                    .orElseThrow(() -> new ModelRoutingException(
                        "No models available for provider: " + preferredProvider)));
        }
        return registry.defaultModel()
            .orElseThrow(() -> new ModelRoutingException("No default model configured"));
    }

    /**
     * 根据会话上下文和特定能力需求解析模型。
     *
     * <p>先调用 {@link #resolve(SessionContext)} 获取基础模型，
     * 若基础模型已具备目标能力则直接返回；
     * 否则在同一提供商下查找首个具备该能力的替代模型。
     *
     * <p>典型使用场景：
     * <ul>
     *   <li>用户上传了图片 → 需要 {@link ModelCapability#VISION} 能力的模型</li>
     *   <li>Skill 需要调用外部工具 → 需要 {@link ModelCapability#FUNCTION} 能力的模型</li>
     *   <li>前端请求 SSE 流式输出 → 需要 {@link ModelCapability#STREAM} 能力的模型</li>
     * </ul>
     *
     * @param ctx        会话上下文
     * @param capability 目标能力需求
     * @return 具备指定能力的模型规格
     * @throws ModelRoutingException 当无模型满足能力需求时抛出
     */
    public ModelSpec resolveForCapability(SessionContext ctx, ModelCapability capability) {
        ModelSpec base = resolve(ctx);
        if (base.capabilities().contains(capability)) {
            return base;
        }
        return registry.findByCapability(base.providerId(), capability)
            .orElseThrow(() -> new ModelRoutingException(
                "No model with capability " + capability + " for provider: " + base.providerId()));
    }

    /**
     * 列出所有已注册的模型规格。
     *
     * @return 所有可用模型的不可变列表
     */
    public List<ModelSpec> listAll() {
        return registry.listAll();
    }
}
