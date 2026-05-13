package com.echomind.memory.shortterm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 近期上下文配置。
 *
 * <p>核心参数说明：
 * <ul>
 *   <li><b>maxMessages（最大消息数）</b> —— Redis 或本地近期缓存保留的消息数量。
 *       默认值为 20。完整历史仍然保存在 MySQL 中。</li>
 * </ul>
 *
 * <p>调优建议：
 * <ul>
 *   <li>maxMessages 越大，模型提示词里直接携带的近期对话越多，Token 成本也越高。</li>
 *   <li>旧消息不依赖这个缓存保存，页面历史、摘要和向量检索都从 MySQL 读取。</li>
 *   <li>该值可通过 Spring Boot 配置属性 {@code echomind.memory.short-term-window} 调整。</li>
 * </ul>
 *
 * <p>设计决策：
 * 使用简单的 POJO 而非 Spring {@code @ConfigurationProperties} 绑定，
 * 使该模块保持框架无关性（echomind-memory 模块不依赖 Spring）。
 *
 * @author EchoMind Team
 * @see com.echomind.memory.MemoryManager
 * @since 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WindowConfig {

    /**
     * 最大消息数 —— 近期上下文缓存容量上限。
     * 默认值 20，可根据 LLM 上下文窗口大小和延迟要求进行调整。
     */
    private int maxMessages = 20;
}
