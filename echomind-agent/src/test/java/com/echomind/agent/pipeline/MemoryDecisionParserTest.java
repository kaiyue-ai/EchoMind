package com.echomind.agent.pipeline;

import com.echomind.agent.pipeline.planning.MemoryDecisionParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryDecisionParserTest {

    @Test
    void parsesAndStripsHiddenMemoryDecision() {
        MemoryDecisionParser parser = new MemoryDecisionParser(null);
        String content = """
            我会记住这个偏好。
            <ECHOMIND_MEMORY_DECISION>{"rememberFacts":true,"refreshProfile":false,"reason":"用户表达稳定偏好"}</ECHOMIND_MEMORY_DECISION>
            """;

        MemoryDecisionParser.Parsed parsed = parser.parseAndStrip(content);

        assertThat(parsed.content()).isEqualTo("我会记住这个偏好。");
        assertThat(parsed.decision().rememberFacts()).isTrue();
        assertThat(parsed.decision().refreshProfile()).isFalse();
        assertThat(parsed.decision().parseValid()).isTrue();
        assertThat(parsed.decision().reason()).contains("稳定偏好");
    }

    @Test
    void stripsMisspelledHiddenMemoryDecisionAlias() {
        MemoryDecisionParser parser = new MemoryDecisionParser(null);
        String content = """
            这是正文。
            <ECHOMIMD_MEMORY_DECISION>{"rememberFacts":false,"refreshProfile":false,"reason":"闲聊"}</ECHOMIMD_MEMORY_DECISION>
            """;

        MemoryDecisionParser.Parsed parsed = parser.parseAndStrip(content);

        assertThat(parsed.content()).isEqualTo("这是正文。");
        assertThat(parsed.decision().parseValid()).isTrue();
        assertThat(parsed.decision().rememberFacts()).isFalse();
        assertThat(parsed.decision().refreshProfile()).isFalse();
    }

    @Test
    void stripsShortMisspelledHiddenMemoryDecisionAlias() {
        MemoryDecisionParser parser = new MemoryDecisionParser(null);
        String content = """
            没有去世。
            <ECHOMIM_MEMORY_DECISION>{"rememberFacts":false,"refreshProfile":false,"reason":"事实确认"}</ECHOMIM_MEMORY_DECISION>
            """;

        MemoryDecisionParser.Parsed parsed = parser.parseAndStrip(content);

        assertThat(parsed.content()).isEqualTo("没有去世。");
        assertThat(parsed.decision().parseValid()).isTrue();
        assertThat(parsed.decision().rememberFacts()).isFalse();
        assertThat(parsed.decision().refreshProfile()).isFalse();
    }

    @Test
    void stripsExistingDecisionBlocksForHistoryDisplay() {
        String content = """
            正文
            <ECHOMIM_MEMORY_DECISION>{"rememberFacts":false,"refreshProfile":false,"reason":"闲聊"}</ECHOMIM_MEMORY_DECISION>
            """;

        assertThat(MemoryDecisionParser.stripHiddenDecisionBlocks(content)).isEqualTo("正文");
    }

    @Test
    void fallsBackWhenDecisionJsonIsMissingOrInvalid() {
        MemoryDecisionParser parser = new MemoryDecisionParser(null);

        assertThat(parser.parseAndStrip("普通回复").decision().parseValid()).isFalse();
        assertThat(parser.parseAndStrip("""
            回复
            <ECHOMIND_MEMORY_DECISION>{"rememberFacts":"true","refreshProfile":false}</ECHOMIND_MEMORY_DECISION>
            """).decision().rememberFacts()).isTrue();
    }

    @Test
    void streamFilterHidesSplitMarkerFromVisibleChunks() {
        MemoryDecisionParser parser = new MemoryDecisionParser(null);
        MemoryDecisionParser.StreamFilter filter = parser.streamFilter();

        String first = filter.accept("我会记住");
        String second = filter.accept("这个偏好<ECHOMIND_MEMORY_DECISION>");
        String third = filter.accept("{\"rememberFacts\":true,\"refreshProfile\":true,\"reason\":\"项目约束\"}</ECHOMIND_MEMORY_DECISION>");
        String tail = filter.finish();

        assertThat(first).isNull();
        assertThat(second).isEqualTo("我会记住这个偏好");
        assertThat(third).isNull();
        assertThat(tail).isNull();
        assertThat(filter.decision().rememberFacts()).isTrue();
        assertThat(filter.decision().refreshProfile()).isTrue();
    }

    @Test
    void streamFilterHidesMisspelledMarkerFromVisibleChunks() {
        MemoryDecisionParser parser = new MemoryDecisionParser(null);
        MemoryDecisionParser.StreamFilter filter = parser.streamFilter();

        String first = filter.accept("哈哈，正文");
        String second = filter.accept("<ECHOMIM_MEMORY_DECISION>");
        String third = filter.accept("{\"rememberFacts\":false,\"refreshProfile\":false,\"reason\":\"闲聊\"}</ECHOMIM_MEMORY_DECISION>");
        String tail = filter.finish();

        assertThat(first).isNull();
        assertThat(second).isEqualTo("哈哈，正文");
        assertThat(third).isNull();
        assertThat(tail).isNull();
        assertThat(filter.decision().parseValid()).isTrue();
        assertThat(filter.decision().rememberFacts()).isFalse();
    }
}
