package com.echomind.agent.team.visualization;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;

/**
 * 团队跟踪记录器 —— 记录 Agent Team 协作流程中的关键事件。
 *
 * <p>记录器以时间线的方式追踪整个团队协作过程，主要用途：
 * <ul>
 *   <li><b>调试</b>：重现团队协作的完整执行过程</li>
 *   <li><b>可视化</b>：为 {@link MermaidGenerator} 提供数据源</li>
 *   <li><b>审计</b>：保留完整的执行记录供后续分析</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>每次 {@link #startSession} 调用会清空之前的事件记录。</li>
 *   <li>{@link #getEvents()} 返回不可变副本，防止外部修改。</li>
 *   <li>事件按添加顺序排列，不做任何排序或去重。</li>
 * </ul>
 */
public class TeamTraceRecorder {

    /** 事件列表（按时间顺序存储） */
    private final List<TraceEvent> events = new ArrayList<>();
    /** 当前会话标识 */
    @Getter
    private String sessionId;
    /** 当前团队标识 */
    @Getter
    private String teamId;
    /** 当前任务描述 */
    private String task;

    /**
     * 开始新的跟踪会话。
     *
     * <p>清空所有历史事件，记录 session_start 事件作为起始标记。
     *
     * @param sessionId 会话唯一标识
     * @param teamId    团队标识
     * @param task      任务描述文本
     */
    public void startSession(String sessionId, String teamId, String task) {
        this.sessionId = sessionId;
        this.teamId = teamId;
        this.task = task;
        events.clear();
        recordEvent("session_start", "Team " + teamId + " started task: " + task);
    }

    /**
     * 记录一个跟踪事件。
     *
     * <p>事件自动附带当前时间戳（{@link Instant#now()}）。
     *
     * @param eventType   事件类型标签（如 "task_start", "plan_created", "step_complete"）
     * @param description 事件描述文本
     */
    public void recordEvent(String eventType, String description) {
        events.add(new TraceEvent(Instant.now(), eventType, description));
    }

    /** @return 事件列表的不可变副本 */
    public List<TraceEvent> getEvents() { return List.copyOf(events); }

    /**
     * 跟踪事件记录 —— 单个事件的数据载体。
     *
     * @param timestamp   事件发生时间（UTC 时间戳）
     * @param eventType   事件类型标签
     * @param description 事件描述文本
     */
    public record TraceEvent(Instant timestamp, String eventType, String description) {}
}
