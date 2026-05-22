package com.echomind.console.users;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

/** 管理端用户删除时清理旧会话向量备份表。 */
public interface MemoryEmbeddingCleanupRepository extends Repository<MemoryEmbeddingCleanupEntity, Long> {

    @Modifying
    @Query(value = """
        delete from echomind_memory_embeddings
        where session_id in (:sessionIds)
        """, nativeQuery = true)
    int deleteBySessionIds(@Param("sessionIds") Collection<String> sessionIds);

    @Modifying
    @Query(value = """
        delete from echomind_memory_embeddings
        where message_id in (:messageIds)
        """, nativeQuery = true)
    int deleteByMessageIds(@Param("messageIds") Collection<Long> messageIds);
}
