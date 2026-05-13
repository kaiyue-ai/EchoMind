package com.echomind.skill.calculator;

import com.echomind.skill.api.*;
import net.objecthunter.exp4j.ExpressionBuilder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 计算器技能 —— 安全地求值数学表达式。
 *
 * <p>基于 exp4j 库实现安全的数学表达式求值。
 * exp4j 是一个轻量级的数学表达式解析器，支持：
 * <ul>
 *   <li>基本运算：加、减、乘、除、幂</li>
 *   <li>常用函数：sin、cos、tan、log、sqrt、abs 等</li>
 *   <li>括号分组与运算优先级</li>
 *   <li>内置变量：pi、e</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>安全性</b>：使用 exp4j 而非 JavaScript {@code eval()}，
 *       避免了代码注入风险。exp4j 仅解析数学表达式，不执行任意代码。</li>
 *   <li><b>自然语言兼容</b>：通过 {@link #extractExpression} 方法
 *       从混合文本中提取数学表达式部分，支持类似 "计算 1+2" 的输入。</li>
 *   <li><b>错误处理</b>：表达式语法错误或计算异常时返回
 *       {@link SkillResult#failure}，而非抛出未捕获异常。</li>
 *   <li><b>异步执行</b>：通过 {@link CompletableFuture#supplyAsync}
 *       在公共线程池中异步执行。</li>
 *   <li><b>默认表达式</b>：未提供表达式时默认为 "0"。</li>
 * </ul>
 *
 * <p>输入参数：
 * <ul>
 *   <li>{@code expression}（string，必填）—— 需要求值的数学表达式</li>
 * </ul>
 *
 * <p>技能标签：math, calculate, compute, evaluate, 计算, 数学, 算式, 等于, 算术, 求值
 */
public class CalculatorSkill implements Skill {

    /**
     * 返回技能元数据。
     *
     * <p>定义输入参数 Schema：一个名为 "expression" 的必填字符串参数。
     *
     * @return 计算器技能的完整元数据
     */
    @Override
    public SkillMetadata metadata() {
        return new SkillMetadata(
            "calculator",
            "1.0.0",
            "Evaluate mathematical expressions safely",
            Map.of(
                "properties", Map.of(
                    "expression", Map.of("type", "string", "description", "Math expression to evaluate")
                ),
                "required", List.of("expression")
            ),
            List.of(),
            "EchoMind",
            List.of("math", "calculate", "compute", "evaluate", "计算", "数学", "算式", "等于", "算术", "求值"),
            List.of("计算", "算一下", "求值", "等于多少", "calculate", "math expression"),
            Map.of(
                "calculate", List.of("计算", "算", "求值", "运算"),
                "expression", List.of("算式", "表达式", "公式")
            )
        );
    }

    /**
     * 执行数学表达式求值。
     *
     * <p>先从输入中提取数学表达式片段，再用 exp4j
     * {@link ExpressionBuilder} 解析并计算。
     * 成功时返回 "表达式 = 结果" 格式的字符串；
     * 失败时返回包含错误信息的失败结果。
     *
     * @param request 技能请求，包含 "expression" 参数
     * @return 包含计算结果或错误信息的异步结果
     */
    @Override
    public CompletableFuture<SkillResult> execute(SkillRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                String raw = String.valueOf(request.parameters().getOrDefault("expression", "0"));
                String expr = extractExpression(raw);
                double result = new ExpressionBuilder(expr).build().evaluate();
                return SkillResult.success(expr + " = " + result, System.currentTimeMillis() - start);
            } catch (Exception e) {
                return SkillResult.failure("Calculation error: " + e.getMessage(),
                    System.currentTimeMillis() - start);
            }
        });
    }

    /**
     * 从自然语言文本中提取数学表达式。
     *
     * <p>使用正则表达式匹配数字、运算符、括号和空格组成的连续片段。
     * 如果文本中无可识别的表达式片段，则返回原始文本作为回退。
     *
     * @param text 可能包含自然语言的输入文本
     * @return 提取出的纯数学表达式字符串
     */
    private String extractExpression(String text) {
        // 匹配包含数字和运算符的表达式: 数字 + 运算符 + 数字
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "[\\d\\s+\\-*/^%(.).]+");
        java.util.regex.Matcher m = p.matcher(text);
        StringBuilder expr = new StringBuilder();
        while (m.find()) {
            expr.append(m.group().trim());
        }
        String result = expr.toString().trim();
        return result.isEmpty() ? text : result;
    }
}
