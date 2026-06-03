package com.echomind.skill.api;

import java.util.concurrent.CompletableFuture;

// skill的接口
public interface Skill {

    /** 返回技能身份、参数 Schema、依赖和标签。 */
    SkillMetadata metadata();

    /**
     * 异步执行技能请求。
     *
     * <p>耗时 I/O 应放到异步任务里，异常应转为失败结果或异常完成的 Future。</p>
     */
    CompletableFuture<SkillResult> execute(SkillRequest request);

    /** 技能启用时的初始化钩子，要求幂等。 */
    default void onEnable() {}

    /** 技能停用时的暂停钩子，不建议做耗时清理。 */
    default void onDisable() {}

    /** 技能卸载时释放资源。 */
    default void onDestroy() {}
}
