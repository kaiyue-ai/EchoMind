package com.echomind.agent.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 执行管道——按序驱动五个管道阶段的编排器。
 *
 * <p>ExecutionPipeline是Agent请求处理的核心执行引擎。它接收一个{@link PipelineContext}，
 * 按照每个阶段声明的{@link PipelineStage#order()}顺序依次执行，将前一个阶段的输出
 * 作为下一个阶段的输入。</p>
 *
 * <p>管道执行流程：</p>
 * <pre>
 * 用户消息 → ContextEnrich → ToolResolution → SkillInvocation → ResultAggregation → MemoryPersist → 最终响应
 * </pre>
 *
 * <p>错误处理：</p>
 * <ul>
 *   <li>任何阶段抛出异常时，管道立即终止</li>
 *   <li>错误信息被设置为上下文的最终响应</li>
 *   <li>后续阶段不会被执行</li>
 * </ul>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>构造时对阶段列表排序并保存副本，确保顺序稳定</li>
 *   <li>每个阶段只接收上一个阶段返回的上下文，保证数据流单向传递</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see PipelineStage
 * @see PipelineContext
 */
public class ExecutionPipeline {

    /** SLF4J日志记录器，用于记录管道执行过程和阶段异常 */
    private static final Logger log = LoggerFactory.getLogger(ExecutionPipeline.class);

    /**
     * 排序后的管道阶段列表。
     * 构造时按{@link PipelineStage#order()}升序排列，确保执行顺序的一致性。
     */
    private final List<PipelineStage> stages;

    /**
     * 构造执行管道并自动排序阶段。
     *
     * <p>传入的阶段列表会被复制并排序，不影响原始列表。
     * 排序依据为{@link PipelineStage#order()}的返回值，值越小越先执行。</p>
     *
     * @param stages 管道阶段列表（未经排序）
     */
    public ExecutionPipeline(List<PipelineStage> stages) {
        this.stages = new ArrayList<>(stages);
        this.stages.sort(Comparator.comparingInt(PipelineStage::order));
    }

    /**
     * 执行管道，按序处理上下文。
     *
     * <p>遍历所有阶段，依次调用{@link PipelineStage#process(PipelineContext)}。
     * 每个阶段的输出成为下一个阶段的输入。如果任何阶段抛出异常，
     * 管道立即终止并设置错误响应。</p>
     *
     * @param ctx 初始管道上下文，包含用户消息等输入数据
     * @return 处理完成后的管道上下文，包含最终响应和中间结果
     */
    public PipelineContext execute(PipelineContext ctx) {
        PipelineContext current = ctx;
        for (PipelineStage stage : stages) {
            try {
                log.debug("[Pipeline] Running stage {} (order={})", stage.name(), stage.order());
                current = stage.process(current);
            } catch (Exception e) {
                log.error("[Pipeline] Stage {} failed: {}", stage.name(), e.getMessage());
                current.setFinalResponse("[Error] Pipeline stage '" + stage.name() + "' failed: " + e.getMessage());
                return current;
            }
        }
        return current;
    }
}
