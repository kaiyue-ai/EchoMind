package com.echomind.skill.markdown;

import com.echomind.skill.api.Skill;
import com.echomind.skill.api.SkillMetadata;
import com.echomind.skill.api.SkillRequest;
import com.echomind.skill.api.SkillResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Markdown 代码块格式化技能。
 *
 * <p>本技能只做一件事：把模型准备输出的代码包装成 Markdown fenced code block。
 * 这样前端可以稳定识别语言标识并按代码块渲染，避免代码和普通说明文字混在一起。</p>
 */
public class MarkdownCodeSkill implements Skill {

    @Override
    public SkillMetadata metadata() {
        return new SkillMetadata(
            "markdown-code",
            "1.0.0",
            "Wrap source code in Markdown fenced code blocks. Use this when the user asks to output, format, or display code.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "code", Map.of("type", "string", "description", "Source code to wrap"),
                    "language", Map.of("type", "string", "description", "Markdown language label, such as java, js, python, sql"),
                    "caption", Map.of("type", "string", "description", "Optional short text before the code block")
                ),
                "required", List.of("code")
            ),
            List.of(),
            "EchoMind",
            List.of("markdown", "code", "format", "代码", "格式化", "代码块"),
            List.of("代码", "代码块", "markdown", "Markdown格式", "格式化代码", "输出代码", "展示代码", "包围代码"),
            Map.of(
                "code", List.of("代码", "源码", "程序", "code", "source"),
                "markdown", List.of("markdown", "md", "Markdown格式", "围栏", "代码块"),
                "format", List.of("格式化", "包围", "包装", "展示", "输出")
            )
        );
    }

    @Override
    public CompletableFuture<SkillResult> execute(SkillRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                String code = stringParam(request, "code").strip();
                if (code.isBlank()) {
                    return SkillResult.failure("code 参数不能为空", System.currentTimeMillis() - start);
                }
                String language = sanitizeLanguage(stringParam(request, "language"));
                String caption = stringParam(request, "caption").strip();
                StringBuilder output = new StringBuilder();
                if (!caption.isBlank()) {
                    output.append(caption).append("\n\n");
                }
                output.append("```").append(language).append('\n')
                    .append(escapeFence(code))
                    .append("\n```");
                return SkillResult.success(output.toString(), System.currentTimeMillis() - start);
            } catch (Exception e) {
                return SkillResult.failure("Markdown code format error: " + e.getMessage(),
                    System.currentTimeMillis() - start);
            }
        });
    }

    private String stringParam(SkillRequest request, String key) {
        Object value = request.parameters().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Markdown 语言标识只保留常见安全字符。
     */
    private String sanitizeLanguage(String language) {
        String normalized = language == null ? "" : language.trim().toLowerCase();
        normalized = normalized.replaceAll("[^a-z0-9_+#.-]", "");
        return normalized;
    }

    /**
     * 代码内部如果刚好包含三反引号，把它提升为四反引号，避免提前结束外层围栏。
     */
    private String escapeFence(String code) {
        return code.replace("```", "````");
    }
}
