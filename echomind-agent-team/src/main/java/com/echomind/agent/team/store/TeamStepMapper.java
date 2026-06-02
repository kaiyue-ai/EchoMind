package com.echomind.agent.team.store;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface TeamStepMapper extends MybatisPlusMapper<TeamStepEntity> {

    default List<TeamStepEntity> selectByRunIdOrderByStepIndexAsc(String runId) {
        return selectList(Wrappers.lambdaQuery(TeamStepEntity.class)
            .eq(TeamStepEntity::getRunId, runId)
            .orderByAsc(TeamStepEntity::getStepIndex));
    }

    default void deleteByRunId(String runId) {
        delete(Wrappers.lambdaQuery(TeamStepEntity.class)
            .eq(TeamStepEntity::getRunId, runId));
    }

    default void deleteByRunIdIn(Collection<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return;
        }
        delete(Wrappers.lambdaQuery(TeamStepEntity.class)
            .in(TeamStepEntity::getRunId, runIds));
    }
}
