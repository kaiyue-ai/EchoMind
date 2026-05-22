package com.echomind.agent.team.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TeamRunRepository extends JpaRepository<TeamRunEntity, String> {
    List<TeamRunEntity> findByTeamIdOrderByCreatedAtDesc(String teamId);

    List<TeamRunEntity> findByTeamIdAndUserIdOrderByCreatedAtDesc(String teamId, String userId);

    List<TeamRunEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    @Modifying
    @Query("delete from TeamRunEntity run where run.teamId = :teamId")
    void deleteByTeamId(@Param("teamId") String teamId);
}
