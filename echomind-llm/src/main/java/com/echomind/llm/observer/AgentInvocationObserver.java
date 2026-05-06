package com.echomind.llm.observer;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Agent 调用观察器 —— Spring AOP 切面，拦截并记录被 {@link Observable} 标注的方法调用。
 *
 * <p>这是一个典型的 AOP 围绕通知（Around Advice），在被拦截方法执行前后
 * 自动记录开始日志、完成日志（含耗时）和异常日志。它创建并填充
 * {@link AgentInvocationContext} 以结构化地捕获调用信息。
 *
 * <p><b>拦截点：</b> 所有被 {@code @Observable} 注解标记的方法。
 *
 * <p><b>日志输出格式：</b>
 * <ul>
 *   <li>开始：{@code [AgentObserver] <methodName> started}</li>
 *   <li>完成：{@code [AgentObserver] <methodName> completed in <N>ms}</li>
 *   <li>失败：{@code [AgentObserver] <methodName> failed after <N>ms: <errorMsg>}</li>
 * </ul>
 *
 * <p><b>执行流程：</b>
 * <ol>
 *   <li>创建 {@link AgentInvocationContext} 并记录开始时间</li>
 *   <li>记录目标方法名称和首个参数（输入消息）</li>
 *   <li>调用 {@code joinPoint.proceed()} 执行原始方法</li>
 *   <li>成功时：计算耗时、记录输出消息、打印完成日志</li>
 *   <li>失败时：计算耗时、记录异常信息、打印错误日志、重新抛出异常</li>
 * </ol>
 *
 * <p><b>设计决策：</b>
 * <ul>
 *   <li>使用 {@code @Aspect} + {@code @Around} 实现非侵入式拦截，
 *       业务代码无需引入任何观测相关依赖。</li>
 *   <li>异常被记录后重新抛出（而非吞没），确保不影响正常业务流程。</li>
 *   <li>耗时计算使用 {@link java.time.Duration#between}，精度到毫秒。</li>
 *   <li>当前仅记录首个参数作为输入消息，适用于 Agent 以单字符串为输入的场景。</li>
 * </ul>
 *
 * @see Observable
 * @see AgentInvocationContext
 */
@Aspect
public class AgentInvocationObserver {

    /** 日志记录器，使用 "[AgentObserver]" 前缀便于日志检索 */
    private static final Logger log = LoggerFactory.getLogger(AgentInvocationObserver.class);

    /**
     * 围绕通知 —— 拦截被 {@link Observable} 注解的方法并记录调用信息。
     *
     * <p>拦截逻辑：
     * <ol>
     *   <li>记录方法开始时间和方法名</li>
     *   <li>提取首个参数作为输入消息</li>
     *   <li>执行原始方法</li>
     *   <li>记录执行耗时和结果</li>
     *   <li>捕获异常并记录后重新抛出</li>
     * </ol>
     *
     * @param joinPoint AspectJ 连接点，提供被拦截方法的信息和参数
     * @return 原始方法的返回值（透明透传）
     * @throws Throwable 原始方法抛出的异常（记录后重新抛出）
     */
    @Around("@annotation(com.echomind.llm.observer.Observable)")
    public Object observe(ProceedingJoinPoint joinPoint) throws Throwable {
        AgentInvocationContext ctx = new AgentInvocationContext();
        ctx.setStartTime(Instant.now());

        String methodName = joinPoint.getSignature().toShortString();
        Object[] args = joinPoint.getArgs();
        if (args.length > 0 && args[0] != null) {
            ctx.setInputMessage(String.valueOf(args[0]));
        }

        try {
            log.info("[AgentObserver] {} started", methodName);
            Object result = joinPoint.proceed();
            long elapsed = java.time.Duration.between(ctx.getStartTime(), Instant.now()).toMillis();
            ctx.setOutputMessage(result != null ? result.toString() : null);

            log.info("[AgentObserver] {} completed in {}ms", methodName, elapsed);
            return result;
        } catch (Exception e) {
            long elapsed = java.time.Duration.between(ctx.getStartTime(), Instant.now()).toMillis();
            ctx.setError(e.getMessage());
            log.error("[AgentObserver] {} failed after {}ms: {}", methodName, elapsed, e.getMessage());
            throw e;
        }
    }
}
