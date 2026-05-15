package com.echomind.memory.embedding;

import java.util.List;

/** Redis Stack 不可用时的空会话向量索引。 */
public class NoopMemoryVectorStore implements MemoryVectorStore {

    @Override
    public void save(MemoryVectorRecord record) {
        // no-op
    }

    @Override
    public List<MemorySearchHit> search(String sessionId, double[] queryVector, int topK) {
        return List.of();
    }

    @Override
    public void deleteSession(String sessionId) {
        // no-op
    }
}
