package com.echomind.skill.markdown;

import com.echomind.skill.api.SkillRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownCodeSkillTest {

    private final MarkdownCodeSkill skill = new MarkdownCodeSkill();

    @Test
    void wrapsCodeWithLanguageFence() {
        var result = skill.execute(new SkillRequest(Map.of(
            "code", "public class Demo {}",
            "language", "java",
            "caption", "示例代码"
        ), null, null)).join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).isEqualTo("""
            示例代码

            ```java
            public class Demo {}
            ```""");
    }

    @Test
    void escapesNestedFence() {
        var result = skill.execute(new SkillRequest(Map.of(
            "code", "```text\ninner\n```",
            "language", "markdown"
        ), null, null)).join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).contains("````text");
    }

    @Test
    void rejectsBlankCode() {
        var result = skill.execute(new SkillRequest(Map.of("code", " "), null, null)).join();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("不能为空");
    }
}
