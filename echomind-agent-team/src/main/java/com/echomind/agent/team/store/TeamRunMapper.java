package com.echomind.agent.team.store;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.List;

@Mapper
public interface TeamRunMapper extends MybatisPlusMapper<TeamRunEntity> {

    /**
     * 尝试将任务从指定状态原子性地转换到目标状态
     *
     * @param runId       任务ID
     * @param fromStatus  当前状态（必须匹配才能更新）
     * @param toStatus    目标状态
     * @return 更新成功返回 1，失败返回 0
     */
    default int tryUpdateStatus(String runId, TeamRunStatus fromStatus, TeamRunStatus toStatus) {
        return update(null, Wrappers.lambdaUpdate(TeamRunEntity.class)
            .eq(TeamRunEntity::getRunId, runId)
            .eq(TeamRunEntity::getStatus, fromStatus)
            .set(TeamRunEntity::getStatus, toStatus));
    }

    /**
     * 尝试获取执行锁（原子性地将状态从 PENDING 转为 EXECUTING）
     *
     * @param runId 任务ID
     * @return 获取锁成功返回 true，失败返回 false
     */
    default boolean tryAcquireExecuteLock(String runId) {
        return tryUpdateStatus(runId, TeamRunStatus.PENDING, TeamRunStatus.EXECUTING) > 0;
    }

    default List<TeamRunEntity> selectByTeamIdOrderByCreatedAtDesc(String teamId) {
        return selectList(Wrappers.lambdaQuery(TeamRunEntity.class)
            .eq(TeamRunEntity::getTeamId, teamId)
            .orderByDesc(TeamRunEntity::getCreatedAt));
    }

    default List<TeamRunEntity> selectByTeamIdAndUserIdOrderByCreatedAtDesc(String teamId, String userId) {
        return selectList(Wrappers.lambdaQuery(TeamRunEntity.class)
            .eq(TeamRunEntity::getTeamId, teamId)
            .eq(TeamRunEntity::getUserId, userId)
            .orderByDesc(TeamRunEntity::getCreatedAt));
    }

    default List<TeamRunEntity> selectByUserIdOrderByCreatedAtDesc(String userId) {
        return selectList(Wrappers.lambdaQuery(TeamRunEntity.class)
            .eq(TeamRunEntity::getUserId, userId)
            .orderByDesc(TeamRunEntity::getCreatedAt));
    }

    default List<TeamRunEntity> selectNonTerminalUpdatedBefore(Instant cutoff, int limit) {
        int normalizedLimit = Math.max(1, Math.min(limit, 100));
        return selectList(Wrappers.lambdaQuery(TeamRunEntity.class)
            .notIn(TeamRunEntity::getStatus, TeamRunStatus.COMPLETED, TeamRunStatus.FAILED)
            .lt(TeamRunEntity::getUpdatedAt, cutoff)
            .orderByAsc(TeamRunEntity::getUpdatedAt)
            .last("LIMIT " + normalizedLimit));
    }

    default void deleteByTeamId(String teamId) {
        delete(Wrappers.lambdaQuery(TeamRunEntity.class)
            .eq(TeamRunEntity::getTeamId, teamId));
    }
}
