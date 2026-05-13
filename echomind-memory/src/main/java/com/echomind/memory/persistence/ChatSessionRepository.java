package com.echomind.memory.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 会话记忆主表 Repository。 */
public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {

    /** 会话列表按最近活跃时间倒序展示。 */
    List<ChatSessionEntity> findAllByOrderByLastActivityDesc();

    /** 会话列表按最近活跃时间倒序展示，排除指定前缀的内部会话。 */
    @Query("""
        select s from ChatSessionEntity s
        where s.sessionId not like concat(:prefix, '%')
        order by s.lastActivity desc
        """)
    List<ChatSessionEntity> findVisibleSessions(@Param("prefix") String prefix);
}
