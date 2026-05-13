package com.echomind.agent.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Agent MySQL数据访问层。
 */
@Repository
public interface AgentRepository extends JpaRepository<AgentEntity, String> {
}
