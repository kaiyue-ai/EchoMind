package com.echomind.memory.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentKnowledgeChunkerTest {

    @Test
    void keepsShortParagraphsAsNaturalChunks() {
        AgentKnowledgeChunker chunker = new AgentKnowledgeChunker(120, 0.15);

        List<String> chunks = chunker.split("第一段介绍系统目标。\n\n第二段介绍运行边界。");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).contains("第一段介绍系统目标");
        assertThat(chunks.get(1)).contains("第二段介绍运行边界");
    }

    @Test
    void splitsLongParagraphByPunctuationBeforeHardCutting() {
        AgentKnowledgeChunker chunker = new AgentKnowledgeChunker(20, 0.15);

        List<String> chunks = chunker.split("第一句说明上传流程。第二句说明向量写入。第三句说明窗口召回。");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allMatch(chunk -> chunk.length() <= 20);
        assertThat(String.join("", chunks)).contains("窗口召回");
    }

    @Test
    void addsApproximateFifteenPercentOverlapWithoutExceedingBudget() {
        AgentKnowledgeChunker chunker = new AgentKnowledgeChunker(50, 0.15);

        List<String> chunks = chunker.split("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa。bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb。");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1)).startsWith("aaaaaaa");
        assertThat(chunks.get(1).length()).isLessThanOrEqualTo(50);
    }

    @Test
    void hardCutsSentenceWhenPunctuationUnitExceedsBudget() {
        AgentKnowledgeChunker chunker = new AgentKnowledgeChunker(20, 0.15);

        List<String> chunks = chunker.split("abcdefghijklmnopqrstuvwxyz");

        assertThat(chunks).hasSize(2);
        assertThat(chunks).allMatch(chunk -> chunk.length() <= 20);
    }
}
