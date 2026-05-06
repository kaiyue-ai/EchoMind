package com.echomind.agent.orchestration;

import com.echomind.skill.api.Skill;
import com.echomind.skill.orchestrator.SkillOrchestrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务路由器，负责根据用户消息内容智能匹配最合适的Skill。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>基于标签（Tag）匹配：将用户消息与每个Skill元数据中的标签集合进行比对</li>
 *   <li>返回匹配的Skill列表，供调用方选择和执行</li>
 * </ul>
 *
 * <p>匹配算法：</p>
 * <ol>
 *   <li>遍历所有已启用的Skill</li>
 *   <li>对每个Skill，检查用户消息（转为小写）是否包含Skill元数据中定义的任一标签</li>
 *   <li>匹配成功时将Skill加入结果列表（同一Skill不会重复添加）</li>
 * </ol>
 *
 * <p>与SkillInvocationStage的区别：</p>
 * <ul>
 *   <li>{@code SkillInvocationStage}使用名称关键词+标签+描述的三层匹配，匹配后立即执行</li>
 *   <li>{@code TaskRouter}只使用标签匹配，仅返回匹配列表而不执行</li>
 *   <li>{@code TaskRouter}为调用方提供更细粒度的控制——可以先检查匹配结果再决定是否执行</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see SkillOrchestrator
 * @see Skill
 */
public class TaskRouter {

    /** Skill编排器，提供可用Skill列表 */
    private final SkillOrchestrator orchestrator;

    /**
     * 构造任务路由器。
     *
     * @param orchestrator Skill编排器
     */
    public TaskRouter(SkillOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 根据用户消息匹配相关Skill。
     *
     * <p>对每个已启用的Skill，检查用户消息（不区分大小写）是否包含
     * Skill元数据中定义的任一标签（Tag）。匹配成功则加入结果列表。</p>
     *
     * <p>每个Skill最多被添加一次——一旦发现匹配的标签即跳出内层循环。</p>
     *
     * @param userMessage 用户输入的文本消息
     * @return 匹配的Skill列表（可能为空）
     */
    public List<Skill> matchSkills(String userMessage) {
        List<Skill> matched = new ArrayList<>();
        String msg = userMessage.toLowerCase();

        for (Skill skill : orchestrator.listAvailableSkills()) {
            String name = skill.metadata().name().toLowerCase();
            String desc = skill.metadata().description().toLowerCase();

            for (String tag : skill.metadata().tags()) {
                if (msg.contains(tag.toLowerCase())) {
                    matched.add(skill);
                    break; // 同一Skill避免重复添加
                }
            }
        }

        return matched;
    }
}
