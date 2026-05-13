package com.echomind.agent.pipeline;

import lombok.Getter;

/**
 * 最终发给模型的 prompt 预算。
 *
 * <p>记忆、知识库和工具阶段各自会控制召回数量，但最终仍需要一层统一兜底。
 * 这里先用字符数做近似预算，避免引入 tokenizer 依赖；真实 token 数通常与中英文、
 * 标点和模型 tokenizer 有关，后续可以在本类背后替换成更精确的实现。</p>
 */
@Getter
public class PromptBudget {

    /** 单次用户 prompt 最大字符数，包含历史、知识库片段和当前问题。 */
    private final int maxChars;
    /** 知识库或系统注入内容最多保留字符数。 */
    private final int maxSystemMessageChars;
    /** 单条普通历史消息最多保留字符数。 */
    private final int maxHistoryMessageChars;

    public PromptBudget(int maxChars, int maxSystemMessageChars, int maxHistoryMessageChars) {
        this.maxChars = Math.max(1000, maxChars);
        this.maxSystemMessageChars = Math.max(500, Math.min(maxSystemMessageChars, this.maxChars));
        this.maxHistoryMessageChars = Math.max(200, Math.min(maxHistoryMessageChars, this.maxChars));
    }
}
