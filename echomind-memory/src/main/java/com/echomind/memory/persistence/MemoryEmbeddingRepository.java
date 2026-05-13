package com.echomind.memory.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** MySQL 记忆向量备份 Repository。 */
public interface MemoryEmbeddingRepository extends JpaRepository<MemoryEmbeddingEntity, Long> {

    /** 加载一个会话内的全部向量，用于 MySQL 线性兜底检索。 */
    List<MemoryEmbeddingEntity> findBySessionId(String sessionId);

    /** 清空会话向量。 */
    void deleteBySessionId(String sessionId);
}
