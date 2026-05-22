package com.echomind.agent.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemorySignalParserTest {

    @Test
    void parsesAndStripsHiddenMemorySignal() {
        MemorySignalParser parser = new MemorySignalParser(null);
        String content = """
            我会记住这个偏好。
            <ECHOMIND_MEMORY_SIGNAL>{"important":true,"confidence":0.88,"reason":"用户表达稳定偏好"}</ECHOMIND_MEMORY_SIGNAL>
            """;

        MemorySignalParser.Parsed parsed = parser.parseAndStrip(content);

        assertThat(parsed.content()).isEqualTo("我会记住这个偏好。");
        assertThat(parsed.signal().important()).isTrue();
        assertThat(parsed.signal().confidence()).isEqualTo(0.88);
        assertThat(parsed.signal().reason()).contains("稳定偏好");
    }

    @Test
    void streamFilterHidesSplitMarkerFromVisibleChunks() {
        MemorySignalParser parser = new MemorySignalParser(null);
        MemorySignalParser.StreamFilter filter = parser.streamFilter();

        String first = filter.accept("我会记住");
        String second = filter.accept("这个偏好<ECHOMIND_MEMORY_SIGNAL>");
        String third = filter.accept("{\"important\":true,\"confidence\":0.91,\"reason\":\"项目约束\"}</ECHOMIND_MEMORY_SIGNAL>");
        String tail = filter.finish();

        assertThat(first).isNull();
        assertThat(second).isEqualTo("我会记住这个偏好");
        assertThat(third).isNull();
        assertThat(tail).isNull();
        assertThat(filter.signal().important()).isTrue();
        assertThat(filter.signal().confidence()).isEqualTo(0.91);
    }
}
