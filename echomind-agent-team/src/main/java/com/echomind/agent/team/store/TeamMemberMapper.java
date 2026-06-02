package com.echomind.agent.team.store;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface TeamMemberMapper extends MybatisPlusMapper<TeamMemberEntity> {

    default List<TeamMemberEntity> selectByTeamIdOrderBySortOrderAscIdAsc(String teamId) {
        return selectList(Wrappers.lambdaQuery(TeamMemberEntity.class)
            .eq(TeamMemberEntity::getTeamId, teamId)
            .orderByAsc(TeamMemberEntity::getSortOrder)
            .orderByAsc(TeamMemberEntity::getId));
    }

    default void deleteByTeamId(String teamId) {
        delete(Wrappers.lambdaQuery(TeamMemberEntity.class)
            .eq(TeamMemberEntity::getTeamId, teamId));
    }
}
