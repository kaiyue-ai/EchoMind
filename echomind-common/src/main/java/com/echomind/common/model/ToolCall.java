package com.echomind.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * <h2>工具调用记录</h2>
 * 封装一次完整的工具/技能调用生命周期——从请求发起到结果返回的全过程数据。
 *
 * <h3>数据结构</h3>
 * <p>
 * 一次工具调用包含两个阶段的信息：
 * </p>
 * <ol>
 *   <li><b>请求阶段</b>：{@code id}（唯一标识）、{@code name}（工具名）、{@code arguments}（输入参数）</li>
 *   <li><b>响应阶段</b>：{@code result}（成功时的返回数据）或 {@code error}（失败时的错误信息）</li>
 * </ol>
 * <p>
 * {@code result} 与 {@code error} 互斥：调用成功时 error 为 null，调用失败时 result 为 null。
 * </p>
 *
 * <h3>MCP 协议对齐</h3>
 * 此模型与 MCP 2024-11-05 规范中的 {@code tools/call} 请求/响应结构对齐，
 * 同时作为 EchoMind 内部通用工具调用的标准承载对象。
 *
 * @see com.echomind.mcp.MCPClient MCP 客户端在远程工具调用时使用此模型
 * @see com.echomind.skill.api.SkillResult SPI 层的技能执行结果
 * @see AgentMessage#tool(String, String) 将 ToolCall 结果转换为对话消息
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCall(
    /**
     * 工具调用的唯一标识符（UUID 字符串形式）。
     * 用于在全流程（请求-响应-日志-追踪）中关联同一次调用。
     */
    String id,
    /**
     * 工具名称，对应已注册的技能或 MCP 工具的唯一标识。
     * 例如 {@code "weather-query"}、{@code "calculator"}、{@code "fetch_nowcoder_java_interview_article"}。
     */
    String name,
    /**
     * 工具调用的输入参数（键值对映射）。
     * 键为参数名，值为 JSON 兼容的数据类型（String、Number、Boolean、List、Map 等）。
     */
    Map<String, Object> arguments,
    /**
     * 工具执行成功时的返回结果字符串。
     * 通常为 JSON 格式文本或纯文本；调用失败时此字段为 null。
     */
    String result,
    /**
     * 工具执行失败时的错误信息字符串。
     * 包含异常类型、错误消息和堆栈跟踪等诊断信息；调用成功时此字段为 null。
     */
    String error
) {}
