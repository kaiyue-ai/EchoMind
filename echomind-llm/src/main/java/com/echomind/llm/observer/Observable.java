package com.echomind.llm.observer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 可观测性注解 —— 标记需要被 {@link AgentInvocationObserver} 拦截的方法。
 *
 * <p>这是一个方法级别的标记注解，用于 Spring AOP 切面编程。
 * 被标注的方法在执行时将由 {@link AgentInvocationObserver#observe}
 * 围绕通知（{@code @Around}）拦截，自动记录调用开始时间、输入参数、
 * 输出结果和异常信息。
 *
 * <p><b>使用方式：</b>
 * <pre>{@code
 * @Observable
 * public String processMessage(String userInput) {
 *     // 业务逻辑
 * }
 * }</pre>
 *
 * <p><b>ATTENTION：</b> 此注解仅在 Spring AOP 代理管理的 Bean 上生效。
 * 非 Spring 管理的对象或同类内部调用（self-invocation）不会被拦截。
 *
 * <p><b>设计决策：</b>
 * <ul>
 *   <li>使用 {@code @Retention(RUNTIME)} 确保注解在运行时可用，满足 AOP 需求。</li>
 *   <li>仅作用于 {@code METHOD}，遵循关注点分离原则 —— 只有方法调用才需要观测。</li>
 *   <li>提供可选的 {@code value} 属性用于描述观测点的业务含义，
 *       当前版本中此值预留待后续扩展（如指标分类、日志标签）。</li>
 * </ul>
 *
 * @see AgentInvocationObserver
 * @see AgentInvocationContext
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Observable {
    /**
     * 可选的观测点描述信息。
     *
     * <p>可用于标注业务语义（如 "用户对话处理"、"工具调用拦截"），
     * 当前版本中此值预留，后续可用于日志分类或指标分组。
     *
     * @return 观测点描述，默认为空字符串
     */
    String value() default "";
}
