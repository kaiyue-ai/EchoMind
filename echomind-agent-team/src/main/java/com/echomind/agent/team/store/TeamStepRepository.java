package com.echomind.agent.team.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Repository
public interface TeamStepRepository extends JpaRepository<TeamStepEntity, String> {
    List<TeamStepEntity> findByRunIdOrderByStepIndexAsc(String runId);

    @Modifying
    @Transactional
    @Query("delete from TeamStepEntity step where step.runId = :runId")
    void deleteByRunId(@Param("runId") String runId);

    @Modifying
    @Query("delete from TeamStepEntity step where step.runId in :runIds")
    void deleteByRunIdIn(@Param("runIds") Collection<String> runIds);
}
