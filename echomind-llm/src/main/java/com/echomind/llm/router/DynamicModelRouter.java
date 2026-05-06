package com.echomind.llm.router;

import com.echomind.common.exception.ModelRoutingException;
import com.echomind.llm.session.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 动态模型路由器 —— EchoMind LLM 层的核心路由组件。
 *
 * <p>根据 {@link SessionContext} 中的偏好设置和当前请求的能力需求，
 * 从 {@link ModelProviderRegistry} 中动态选择最合适的模型。
 * 支持三级路由策略：精确匹配 → 提供商默认回退 → 全局默认回退。
 *
 * <p><b>路由优先级（从高到低）：</b>
 * <ol>
 *   <li>用户明确指定了 providerId + modelName → 精确查找该模型</li>
 *   <li>用户仅指定了 providerId → 使用该提供商的默认模型</li>
 *   <li>用户未指定任何偏好 → 使用全局默认模型</li>
 * </ol>
 *
 * <p>当请求需要特定能力（如视觉、函数调用）时，会先解析基础模型，
 * 再检查其是否具备目标能力，不具备则在同一提供商下查找具备该能力的模型。
 *
 * <p><b>设计决策：</b>
 * <ul>
 *   <li>路由器本身无状态，所有模型信息委托给 {@link ModelProviderRegistry} 管理。</li>
 *   <li>解析失败时抛出 {@link ModelRoutingException}，由统一异常处理器转换为 RFC 7807 格式的错误响应。</li>
 *   <li>支持运行时切换模型，无需重启应用。</li>
 * </ul>
 *
 * @see ModelProviderRegistry
 * @see ModelSpec
 * @see ModelCapability
 * @see SessionContext
 */
public class DynamicModelRouter {

    /** 日志记录器，用于跟踪路由决策过程 */
    private static final Logger log = LoggerFactory.getLogger(DynamicModelRouter.class);

    /** 模型提供商注册表，持有所有已注册的模型和提供商信息 */
    private final ModelProviderRegistry registry;

    /**
     * 构造动态模型路由器。
     *
     * @param registry 模型提供商注册表，不能为 {@code null}
     */
    public DynamicModelRouter(ModelProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * 根据会话上下文解析最适合的模型规格。
     *
     * <p>路由逻辑按优先级依次尝试：
     * <ol>
     *   <li>若同时指定了首选提供商和首选模型 → 精确匹配</li>
     *   <li>若仅指定了首选提供商 → 查找该提供商的默认模型，无默认则取第一个可用模型</li>
     *   <li>若均未指定 → 返回全局默认模型</li>
     * </ol>
     *
     * @param ctx 会话上下文，包含用户的首选提供商和模型偏好
     * @return 匹配的模型规格
     * @throws ModelRoutingException 当无法找到任何可用模型时抛出
     */
    public ModelSpec resolve(SessionContext ctx) {
        String preferredProvider = ctx.preferredProvider();
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
