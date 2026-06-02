package com.echomind.agent.team.store;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface TeamMapper extends MybatisPlusMapper<TeamEntity> {

    default List<TeamEntity> selectByOwnerUserIdOrderByCreatedAtAsc(String ownerUserId) {
        return selectList(Wrappers.lambdaQuery(TeamEntity.class)
            .eq(TeamEntity::getOwnerUserId, ownerUserId)
            .orderByAsc(TeamEntity::getCreatedAt));
    }

    default Optional<TeamEntity> selectOptionalByTeamIdAndOwnerUserId(String teamId, String ownerUserId) {
        return Optional.ofNullable(selectOne(Wrappers.lambdaQuery(TeamEntity.class)
            .eq(TeamEntity::getTeamId, teamId)
            .eq(TeamEntity::getOwnerUserId, ownerUserId)));
    }
}
