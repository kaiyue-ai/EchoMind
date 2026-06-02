package com.echomind.agent.team.store;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface TeamEventMapper extends MybatisPlusMapper<TeamEventEntity> {

    default List<TeamEventEntity> selectByRunIdOrderByCreatedAtAscIdAsc(String runId) {
        return selectList(Wrappers.lambdaQuery(TeamEventEntity.class)
            .eq(TeamEventEntity::getRunId, runId)
            .orderByAsc(TeamEventEntity::getCreatedAt)
            .orderByAsc(TeamEventEntity::getId));
    }

    default void deleteByRunIdIn(Collection<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return;
        }
        delete(Wrappers.lambdaQuery(TeamEventEntity.class)
            .in(TeamEventEntity::getRunId, runIds));
    }
}
