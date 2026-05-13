package com.echomind.agent.team.messaging;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * 团队消息总线 —— Agent Team 内部的事件驱动通信通道。
 *
 * <p>基于内存的发布/订阅模式实现，核心机制：
 * <ul>
 *   <li><b>发布（publish）</b>：将消息放入无界阻塞队列，同时通知所有订阅者</li>
 *   <li><b>轮询（poll）</b>：非阻塞地从队列头部取出一条消息</li>
 *   <li><b>订阅（subscribe）</b>：注册一个消息消费者，每次发布时回调</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>使用 {@link LinkedBlockingQueue} 作为底层队列，线程安全且支持并发读写。</li>
 *   <li>发布操作是非阻塞的（{@code offer} 而非 {@code put}）。</li>
 *   <li>订阅者回调在发布线程中同步执行，适合轻量级处理逻辑。</li>
 *   <li>通过 DEBUG 级别日志记录每条消息，方便追踪通信链路。</li>
 * </ul>
 */
@Slf4j
public class TeamMessageBus {

    /** 无界阻塞队列，存储待处理的消息 */
    private final BlockingQueue<TeamMessage> queue = new LinkedBlockingQueue<>();
    /** 消息订阅者列表（监听器集合） */
    private final List<Consumer<TeamMessage>> listeners = new ArrayList<>();

    /**
     * 发布一条消息到总线。
     *
     * <p>消息同时被放入队列和被推送给所有已注册的监听器。
     *
     * @param message 待发布的消息
     */
    public void publish(TeamMessage message) {
        queue.offer(message);
        log.debug("[TeamBus] {} -> {}: {} ({})", message.from(), message.to(),
            message.type(), message.payload());
        for (var listener : listeners) {
            listener.accept(message);
        }
    }

    /**
     * 从队列头部轮询一条消息（非阻塞）。
     *
     * @return 队列头部的消息；若队列为空则返回 null
     */
    public TeamMessage poll() {
        return queue.poll();
    }

    /**
     * 注册消息订阅者。
     *
     * <p>订阅者将在每次 {@link #publish} 调用时收到消息。
     *
     * @param listener 消息消费者（Lambda 或方法引用）
     */
    public void subscribe(Consumer<TeamMessage> listener) {
        listeners.add(listener);
    }

    /**
     * 查询队列中待处理的消息数量。
     *
     * @return 队列当前大小
     */
    public int pendingCount() {
        return queue.size();
    }
}
