package com.echomind.agent.team.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface TeamEventRepository extends JpaRepository<TeamEventEntity, Long> {
    List<TeamEventEntity> findByRunIdOrderByCreatedAtAscIdAsc(String runId);

    @Modifying
    @Query("delete from TeamEventEntity event where event.runId in :runIds")
    void deleteByRunIdIn(@Param("runIds") Collection<String> runIds);
}
