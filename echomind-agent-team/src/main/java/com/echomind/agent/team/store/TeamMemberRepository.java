package com.echomind.agent.team.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TeamMemberRepository extends JpaRepository<TeamMemberEntity, Long> {
    List<TeamMemberEntity> findByTeamIdOrderBySortOrderAscIdAsc(String teamId);

    @Modifying
    @Query("delete from TeamMemberEntity member where member.teamId = :teamId")
    void deleteByTeamId(@Param("teamId") String teamId);
}
