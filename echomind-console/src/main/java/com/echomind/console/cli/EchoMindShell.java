package com.echomind.console.cli;

import com.echomind.agent.Agent;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import com.echomind.skill.marketplace.MarketplaceService;
import com.echomind.skill.registry.SkillRegistration;
import com.echomind.skill.registry.SkillRegistry;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.util.UUID;

/**
 * EchoMind 命令行 Shell —— 提供基于 Spring Shell 的交云式 CLI 入口。
 *
 * <p>当应用以交互模式启动时，本组件注册以下命令：
 * <ol>
 *   <li><b>chat</b> —— 与指定 Agent 进行对话，输出 Agent 和模型信息及回复</li>
 *   <li><b>model-switch</b> —— 切换默认 LLM 模型（提供商:模型名）</li>
 *   <li><b>models</b> —— 列出所有可用模型及其能力和默认状态</li>
 *   <li><b>skill-list</b> —— 列出所有已注册技能的名称、版本、状态和描述</li>
 *   <li><b>agents</b> —— 列出所有 Agent 的名称和绑定的技能</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>标注 {@code @ShellComponent} 使 Spring Shell 自动发现并注册命令。</li>
 *   <li>每个 @{@link ShellMethod} 方法即对应一条 CLI 命令，方法名决定命令名。</li>
 *   <li>chat 命令每次生成新的 UUID 作为 sessionId，不跨命令保持会话状态。</li>
 * </ul>
 */
@ShellComponent
public class EchoMindShell {

    /** Agent 编排器，驱动执行管线 */
    private final AgentOrchestrator orchestrator;
    /** Agent 工厂，管理所有 Agent 实例 */
    private final AgentFactory agentFactory;
    /** 技能注册中心，维护技能列表 */
    private final SkillRegistry skillRegistry;
    /** 动态模型路由器，汇总所有模型信息 */
    private final DynamicModelRouter modelRouter;
    /** 模型提供商注册中心，管理提供商及其默认模型 */
    private final ModelProviderRegistry providerRegistry;

    /**
     * 构造 CLI Shell。
     *
     * @param orchestrator     Agent 编排器
     * @param agentFactory     Agent 工厂
     * @param skillRegistry    技能注册中心
     * @param modelRouter      动态模型路由器
     * @param providerRegistry 模型提供商注册中心
     */
    public EchoMindShell(AgentOrchestrator orchestrator, AgentFactory agentFactory,
                         SkillRegistry skillRegistry, DynamicModelRouter modelRouter,
                         ModelProviderRegistry providerRegistry) {
        this.orchestrator = orchestrator;
        this.agentFactory = agentFactory;
        this.skillRegistry = skillRegistry;
        this.modelRouter = modelRouter;
        this.providerRegistry = providerRegistry;
    }

    /**
     * 与指定 Agent 对话。
     *
     * <p>向 Agent 发送消息，经过完整执行管线后返回格式化结果，
     * 包含 Agent ID、模型 ID 和最终回复内容。
     *
     * @param agent   目标 Agent ID，默认 "default"
     * @param message 要发送的消息文本
     * @return 格式化后的对话结果
     */
    @ShellMethod("Chat with an agent")
    public String chat(@ShellOption(defaultValue = "default") String agent,
                       String message) {
        PipelineContext result = orchestrator.execute(agent, UUID.randomUUID().toString(), message);
        return "[Agent: " + result.getAgentId() + ", Model: " + result.getModelId() + "]\n"
            + result.getFinalResponse();
    }

    /**
     * 切换默认 LLM 模型。
     *
     * <p>更新注册中心中的默认模型配置，即时生效。
     *
     * @param provider 目标模型提供商标识（如 "anthropic"）
     * @param model    目标模型名称（如 "claude-sonnet-4-20250514"）
     * @return 切换确认消息
     */
    @ShellMethod("Switch the active model")
    public String modelSwitch(String provider, String model) {
        providerRegistry.setDefault(provider, model);
        return "Switched to " + provider + ":" + model;
    }

    /**
     * 列出所有可用的 LLM 模型。
     *
     * <p>显示每个模型的提供商/模型名、能力标签以及是否为默认模型。
     *
     * @return 格式化的模型列表
     */
    @ShellMethod("List available models")
    public String models() {
        StringBuilder sb = new StringBuilder("Available Models:\n");
        for (ModelSpec spec : modelRouter.listAll()) {
            sb.append(String.format("  %s/%s [%s] %s\n",
                spec.providerId(), spec.modelName(),
                String.join(",", spec.capabilities().stream().map(Enum::name).toList()),
                spec.isDefault() ? "(default)" : ""));
        }
        return sb.toString();
    }

    /**
     * 列出所有已注册的技能。
     *
     * <p>显示每个技能的名称、版本、当前状态（启用/禁用/错误）和描述。
     *
     * @return 格式化的技能列表
     */
    @ShellMethod("List skills")
    public String skillList() {
        StringBuilder sb = new StringBuilder("Skills:\n");
        for (SkillRegistration reg : skillRegistry.listAll()) {
            sb.append(String.format("  %-25s v%-8s [%s] %s\n",
                reg.getMetadata().name(), reg.getMetadata().version(),
                reg.getState(), reg.getMetadata().description()));
        }
        return sb.toString();
    }

    /**
     * 列出所有已创建的 Agent。
     *
     * <p>显示每个 Agent 的 ID、名称以及绑定的技能 ID 列表。
     *
     * @return 格式化的 Agent 列表
     */
    @ShellMethod("List agents")
    public String agents() {
        StringBuilder sb = new StringBuilder("Agents:\n");
        for (Agent agent : agentFactory.allAgents().values()) {
            sb.append(String.format("  %s - %s (skills: %s)\n",
                agent.getAgentId(), agent.getName(), agent.getSkillIds()));
        }
        return sb.toString();
    }
}
