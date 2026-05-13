package com.echomind.agent.pipeline;

/**
 * 管道阶段接口——执行管道中单个处理阶段的抽象契约。
 *
 * <p>每个PipelineStage代表Agent请求处理流程中的一个独立步骤。
 * 所有阶段由{@link ExecutionPipeline}按{@link #order()}排序后依次调用。</p>
 *
 * <p>实现要求：</p>
 * <ul>
 *   <li><b>无状态</b>：阶段应尽量保持无状态，确保线程安全和可复用性</li>
 *   <li><b>幂等性</b>：同一上下文多次处理应产生相同或兼容的结果</li>
 *   <li><b>异常处理</b>：阶段的未捕获异常将由{@link ExecutionPipeline}捕获并记录</li>
 * </ul>
 *
 * <p>标准阶段及其顺序：</p>
 * <ol>
 *   <li>{@code order=10} ContextEnrichStage — 上下文丰富（加载历史记忆）</li>
 *   <li>{@code order=20} ToolResolutionStage — 解析本轮模型</li>
 *   <li>{@code order=30} MultimodalGuardStage — 校验模型是否支持图片输入</li>
 *   <li>{@code order=35} AttachmentPreparationStage — 准备模型可读取的附件 URL</li>
 *   <li>{@code order=40} ResultAggregationStage — 注册可用工具并调用模型</li>
 *   <li>{@code order=50} MemoryPersistStage — 写回会话记忆</li>
 * </ol>
 *
 * @author EchoMind Team
 * @see ExecutionPipeline
 * @see PipelineContext
 */
public interface PipelineStage {

    /**
     * 返回此阶段在管道中的执行顺序。
     *
     * <p>数值越小越先执行。标准阶段使用10、20、30、40、50留出间隔，
     * 方便在现有阶段之间插入新阶段而无需修改已有代码。</p>
     *
     * @return 执行顺序值（越小越优先）
     */
    int order();

    /**
     * 处理管道上下文，执行此阶段的业务逻辑。
     *
     * <p>实现者应读取上下文中的相关数据，执行处理逻辑，
     * 并将结果写回上下文。返回的上下文实例通常与传入的是同一个对象。</p>
     *
     * @param ctx 当前管道上下文，包含输入数据和之前阶段的处理结果
     * @return 处理后的管道上下文（通常与输入是同一实例）
     */
    PipelineContext process(PipelineContext ctx);

    /**
     * 返回此阶段的名称，用于日志和调试。
     *
     * <p>默认实现返回类的简单名称（{@code getClass().getSimpleName()}）。
     * 可以覆盖以提供更具描述性的名称。</p>
     *
     * @return 阶段名称
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
