package com.echomind.agent.team;

import java.util.List;

/**
 * 团队协作结果 —— 封装一次 Agent Team 执行的完整产出。
 *
 * <p>包含四个维度的信息：
 * <ul>
 *   <li><b>最终输出</b>（finalOutput）：经过 Reviewer 整合（或直接拼接）的最终文本</li>
 *   <li><b>步骤结果</b>（stepResults）：每个子任务的独立执行结果列表</li>
 *   <li><b>状态</b>（status）：执行状态，如 "COMPLETED" 或 "ERROR"</li>
 *   <li><b>Mermaid 图</b>（mermaidDiagram）：可用于前端渲染的流程时序图源码</li>
 * </ul>
 *
 * @param finalOutput    最终整合输出文本
 * @param stepResults    每个子任务的执行结果列表（按执行顺序）
 * @param status         执行状态（COMPLETED / ERROR）
 * @param mermaidDiagram Mermaid.js 格式的时序图源码
 */
public record TeamResult(
    String finalOutput,
    List<String> stepResults,
    String status,
    String mermaidDiagram
) {}
