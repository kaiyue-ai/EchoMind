package com.echomind.memory.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 历史兼容表 Repository。普通聊天向量的正式写入路径已经改为 Redis Stack。 */
public interface MemoryEmbeddingRepository extends JpaRepository<MemoryEmbeddingEntity, Long> {

    /** 加载一个会话内的全部历史向量，仅供旧线性实现使用。 */
    List<MemoryEmbeddingEntity> findBySessionId(String sessionId);

    /** 清空会话向量。 */
    void deleteBySessionId(String sessionId);
}
