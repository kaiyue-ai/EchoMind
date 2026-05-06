package com.echomind.console.controller.rest;

import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 模型管理控制器 —— 提供 LLM 模型的查询与运行时切换 API。
 *
 * <p>EchoMind 支持多模型提供商（Anthropic、OpenAI 等）的动态路由。
 * 本控制器提供：
 * <ol>
 *   <li><b>模型列表</b>：查看所有已注册的模型及其能力、默认状态</li>
 *   <li><b>模型切换</b>：运行时切换默认模型提供商和模型名称，
 *       后续请求将使用新的默认模型进行处理</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>模型切换通过 {@link ModelProviderRegistry#setDefault} 实现，
 *       效果全局生效。</li>
 *   <li>{@link DynamicModelRouter} 负责汇总所有提供商的模型规格信息。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    /** 动态模型路由器，根据会话上下文选择最合适的模型 */
    private final DynamicModelRouter router;
    /** 模型提供商注册中心，维护所有已注册的提供商及其模型 */
    private final ModelProviderRegistry registry;

    /**
     * 构造模型管理控制器。
     *
     * @param router   动态模型路由器
     * @param registry 模型提供商注册中心
     */
    public ModelController(DynamicModelRouter router, ModelProviderRegistry registry) {
        this.router = router;
        this.registry = registry;
    }

    /**
     * 列出所有可用的 LLM 模型。
     *
     * <p>返回每个模型的提供商 ID、模型名称、能力集合以及是否为默认模型。
     *
     * @return 模型规格列表（包含来自所有已注册提供商的模型）
     */
    @GetMapping
    public ResponseEntity<List<ModelSpec>> listModels() {
        return ResponseEntity.ok(router.listAll());
    }

    /**
     * 切换默认 LLM 模型。
     *
     * <p>接收 providerId 和 modelName，更新注册中心中的默认模型配置。
     * 切换后，新的会话请求将使用指定的模型。
     *
     * @param body 请求体，包含：
     *             <ul>
     *               <li>{@code providerId} —— 目标模型提供商标识（如 "anthropic"）</li>
     *               <li>{@code modelName} —— 目标模型名称（如 "claude-sonnet-4-20250514"）</li>
     *             </ul>
     * @return 包含切换状态、providerId 和 modelName 的确认响应
     */
    @PutMapping("/switch")
    public ResponseEntity<Map<String, String>> switchModel(@RequestBody Map<String, String> body) {
        String providerId = body.get("providerId");
        String modelName = body.get("modelName");
        registry.setDefault(providerId, modelName);
        return ResponseEntity.ok(Map.of(
            "status", "switched",
            "providerId", providerId,
            "modelName", modelName
        ));
    }
}
