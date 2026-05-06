package com.echomind.skill.orchestrator;

import com.echomind.skill.api.Skill;
import com.echomind.skill.api.SkillContext;
import com.echomind.skill.api.SkillRequest;
import com.echomind.skill.api.SkillResult;
import com.echomind.skill.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Skill编排器，负责Skill的异步执行调度和生命周期管理。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li><b>异步执行</b>：通过线程池异步执行Skill，返回{@link CompletableFuture}支持非阻塞调用</li>
 *   <li><b>按名称/ID调度</b>：支持通过Skill名称或唯一ID定位并执行Skill</li>
 *   <li><b>执行统计</b>：记录每次执行的耗时，输出INFO级别日志</li>
 *   <li><b>异常处理</b>：捕获执行异常并封装为{@link SkillResult#failure(String, long)}返回</li>
 * </ul>
 *
 * <p>线程模型：</p>
 * <ul>
 *   <li>使用{@link Executors#newCachedThreadPool()}作为执行器，按需创建线程</li>
 *   <li>每个Skill执行提交到线程池异步处理，不阻塞调用线程</li>
 *   <li>调用方通过{@link CompletableFuture}获取结果或链接后续操作</li>
 * </ul>
 *
 * <p>执行流程：</p>
 * <ol>
 *   <li>通过{@link SkillRegistry}查找目标Skill</li>
 *   <li>构造{@link SkillRequest}封装参数和上下文</li>
 *   <li>提交到线程池异步执行</li>
 *   <li>记录执行耗时和结果</li>
 * </ol>
 *
 * @author EchoMind Team
 * @see SkillRegistry
 * @see Skill
 */
public class SkillOrchestrator {

    /** SLF4J日志记录器，用于记录Skill执行事件和异常 */
    private static final Logger log = LoggerFactory.getLogger(SkillOrchestrator.class);

    /** Skill注册中心，用于查找目标Skill */
    private final SkillRegistry registry;

    /**
     * 线程池执行器，用于异步执行Skill。
     *
     * <p>使用{@link Executors#newCachedThreadPool()}——按需创建新线程，
     * 空闲线程60秒后回收。适合Skill执行频率不高但需要快速响应的场景。</p>
     */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 构造Skill编排器。
     *
     * @param registry Skill注册中心，提供Skill查找能力
     */
    public SkillOrchestrator(SkillRegistry registry) {
        this.registry = registry;
    }

    /**
     * 按Skill名称异步执行Skill。
     *
     * <p>通过{@link SkillRegistry#findByName(String)}查找Skill，
     * 如果未找到则返回失败的{@link SkillResult}。</p>
     *
     * @param skillName  要执行的Skill名称
     * @param parameters 执行参数，传递给Skill.execute()
     * @param context    执行上下文，包含sessionId和agentId等信息
     * @return CompletableFuture，异步返回SkillResult
     */
    public CompletableFuture<SkillResult> execute(String skillName, Map<String, Object> parameters,
                                                   SkillContext context) {
        return registry.findByName(skillName)
            .map(skill -> executeSkill(skill, parameters, context))
            .orElseGet(() -> CompletableFuture.completedFuture(
                SkillResult.failure("Skill not found: " + skillName, 0)));
    }

    /**
     * 按Skill唯一ID异步执行Skill。
     *
     * <p>通过{@link SkillRegistry#getSkill(String)}查找Skill，
     * 如果未找到则返回失败的{@link SkillResult}。</p>
     *
     * @param skillId    Skill的唯一标识符
     * @param parameters 执行参数
     * @param context    执行上下文
     * @return CompletableFuture，异步返回SkillResult
     */
    public CompletableFuture<SkillResult> executeById(String skillId, Map<String, Object> parameters,
                                                       SkillContext context) {
        return registry.getSkill(skillId)
            .map(skill -> executeSkill(skill, parameters, context))
            .orElseGet(() -> CompletableFuture.completedFuture(
                SkillResult.failure("Skill not found: " + skillId, 0)));
    }

    /**
     * 在独立线程中执行指定的Skill。
     *
     * <p>执行步骤：</p>
     * <ol>
     *   <li>记录开始时间戳</li>
     *   <li>构造{@link SkillRequest}</li>
     *   <li>调用{@code skill.execute(request)}并等待结果</li>
     *   <li>记录执行耗时日志</li>
     *   <li>异常时捕获并封装为失败结果</li>
     * </ol>
     *
     * @param skill      要执行的Skill实例
     * @param parameters 执行参数
     * @param context    执行上下文
     * @return CompletableFuture，异步返回成功或失败的SkillResult
     */
    private CompletableFuture<SkillResult> executeSkill(Skill skill, Map<String, Object> parameters,
                                                        SkillContext context) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                SkillRequest request = new SkillRequest(parameters, context, null);
                SkillResult result = skill.execute(request).join();
                log.info("Skill {} executed in {}ms", skill.metadata().name(),
                    System.currentTimeMillis() - start);
                return result;
            } catch (Exception e) {
                log.error("Skill {} execution failed", skill.metadata().name(), e);
                return SkillResult.failure(e.getMessage(), System.currentTimeMillis() - start);
            }
        }, executor);
    }

    /**
     * 获取所有已启用的可用Skill列表。
     *
     * @return 已启用Skill实例的列表
     */
    public List<Skill> listAvailableSkills() {
        return registry.listEnabled();
    }
}
