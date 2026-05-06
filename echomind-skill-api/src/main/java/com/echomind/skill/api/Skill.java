package com.echomind.skill.api;

import java.util.concurrent.CompletableFuture;

/**
 * <h2>技能接口 —— EchoMind 平台唯一的扩展契约</h2>
 * 所有 EchoMind 技能（Skill）必须实现此接口。它是平台与技能插件之间唯一的 SPI（Service Provider Interface），
 * 定义了技能的全生命周期和核心执行行为。
 *
 * <h3>SPI 定位</h3>
 * <p>
 * 本接口位于 {@code echomind-skill-api} 模块中，该模块<b>没有其他任何依赖</b>（零依赖设计）。
 * 技能开发者只需将本模块作为 {@code provided} 范围的依赖引入，即可编译出可被 EchoMind 平台加载的技能 JAR 包。
 * 这种隔离设计确保了：
 * </p>
 * <ul>
 *   <li>技能编译时不会引入平台内部实现类</li>
 *   <li>平台可以独立升级而不影响已发布的技能</li>
 *   <li>技能使用独立的 {@code SkillClassLoader} 加载，实现真正的类隔离</li>
 * </ul>
 *
 * <h3>生命周期</h3>
 * <pre>{@code
 *   [JAR 发现] → onEnable() → [执行请求] → onDisable() → onDestroy()
 *                     ↑              ↓
 *                     └── 可重新启用 ──┘
 * }</pre>
 * <ol>
 *   <li>{@link #metadata()} —— 在任何阶段都可能被调用，用于获取技能的身份和能力声明</li>
 *   <li>{@link #onEnable()} —— 技能被启用时调用（默认空实现），用于初始化资源（如建立连接池）</li>
 *   <li>{@link #execute(SkillRequest)} —— 核心方法，每次收到执行请求时异步调用</li>
 *   <li>{@link #onDisable()} —— 技能被停用时调用（默认空实现），用于暂停服务但保留资源</li>
 *   <li>{@link #onDestroy()} —— 技能被卸载时调用（默认空实现），用于彻底释放资源</li>
 * </ol>
 *
 * <h3>实现建议</h3>
 * <ul>
 *   <li>{@code execute()} 应尽快返回 {@code CompletableFuture}，耗时操作在异步线程中执行</li>
 *   <li>{@code metadata()} 返回的对象应为不可变或每次返回新副本</li>
 *   <li>{@code onEnable()/onDisable()} 调用应该是幂等的</li>
 *   <li>避免在 Skill 实现中依赖任何非 {@code echomind-skill-api} 的 EchoMind 模块</li>
 * </ul>
 *
 * @see SkillMetadata 技能的静态元数据声明
 * @see SkillRequest 技能执行请求的输入封装
 * @see SkillResult 技能执行的输出结果
 * @see SkillContext 技能执行的会话上下文
 * @see com.echomind.skill.SkillRegistry 平台侧管理技能生命周期的注册中心
 * @see com.echomind.skill.SkillClassLoader 用于加载技能的隔离类加载器
 */
public interface Skill {

    /**
     * 返回此技能的静态元数据，包含名称、版本、参数 Schema、依赖等信息。
     *
     * <h3>调用时机</h3>
     * 此方法会在技能加载完成后立即被调用，也可能在后续的查询、搜索场景中被重复调用。
     * 建议返回缓存的值或不可变对象，避免每次调用都执行昂贵的计算。
     *
     * @return 技能的元数据对象，包含完整的身份和能力声明；
     *         不得返回 null
     */
    SkillMetadata metadata();

    /**
     * <h3>执行技能请求（核心方法）</h3>
     * 接收一个技能执行请求，异步处理并返回执行结果。
     *
     * <h3>异步契约</h3>
     * <ul>
     *   <li>方法必须立即返回 {@code CompletableFuture}，不得在当前线程中阻塞</li>
     *   <li>耗时操作（网络 I/O、文件读写、复杂计算）应在异步线程中执行</li>
     *   <li>异常应通过 {@code CompletableFuture#completeExceptionally} 传递，
     *       而非直接抛出</li>
     * </ul>
     *
     * <h3>参数验证</h3>
     * 平台在调用此方法前会使用 {@link com.echomind.common.util.JsonSchemaValidator}
     * 对 {@code request.parameters()} 进行 Schema 校验，校验失败不会到达此方法。
     * 但技能仍应自行处理业务层面的参数有效性（例如数值范围、格式合法性等）。
     *
     * @param request 技能执行请求，包含输入参数、会话上下文和工具调用 ID
     * @return 异步执行的 Future，完成时包含成功或失败的 {@link SkillResult}
     */
    CompletableFuture<SkillResult> execute(SkillRequest request);

    /**
     * 技能被启用时的生命周期回调。
     *
     * <h3>默认行为</h3>
     * 默认为空实现。覆盖此方法以执行初始化逻辑，例如：
     * <ul>
     *   <li>建立数据库连接池</li>
     *   <li>加载配置文件</li>
     *   <li>预热本地缓存</li>
     *   <li>注册外部服务的回调</li>
     * </ul>
     *
     * <h3>幂等性要求</h3>
     * 此方法可能被多次调用（例如技能被 Disable 后又重新 Enable），
     * 实现时必须保证重复调用不会产生副作用（如重复创建连接池）。
     */
    default void onEnable() {}

    /**
     * 技能被停用时的生命周期回调。
     *
     * <h3>默认行为</h3>
     * 默认为空实现。覆盖此方法以执行暂停逻辑，例如：
     * <ul>
     *   <li>暂停接收新的外部事件</li>
     *   <li>清空临时缓存</li>
     *   <li>断开非关键连接</li>
     * </ul>
     *
     * <h3>行为契约</h3>
     * 调用 {@code onDisable()} 后，平台不会再向此技能发送新的 {@code execute()} 请求，
     * 但已经在执行中的请求允许自然完成。技能不应在此方法中执行耗时清理工作——耗时清理应放在 {@code onDestroy()} 中。
     */
    default void onDisable() {}

    /**
     * 技能被卸载/销毁时的生命周期回调。
     *
     * <h3>默认行为</h3>
     * 默认为空实现。覆盖此方法以执行彻底的资源清理，例如：
     * <ul>
     *   <li>关闭数据库连接池</li>
     *   <li>释放文件句柄</li>
     *   <li>取消注册的外部回调</li>
     *   <li>关闭线程池</li>
     * </ul>
     *
     * <h3>终态保证</h3>
     * 调用 {@code onDestroy()} 后，技能进入 {@code UNLOADED} 终结状态，
     * 其类加载器将被垃圾回收。此方法应是有副作用的最后一次调用。
     */
    default void onDestroy() {}
}
