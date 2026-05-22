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

    // 阶段的顺序值
    int order();

   // 处理过程
    PipelineContext process(PipelineContext ctx);

   // 阶段名称
    default String name() {
        return getClass().getSimpleName();
    }
}
