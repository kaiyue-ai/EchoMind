package com.echomind.memory.embedding;

import java.util.List;

/** 会话记忆向量索引。 */
public interface MemoryVectorStore {

    /** 保存一条消息向量。 */
    void save(MemoryVectorRecord record);

    /** 在指定会话内按向量相似度召回历史片段。 */
    List<MemorySearchHit> search(String sessionId, double[] queryVector, int topK);

    /** 删除指定会话的全部向量索引。 */
    void deleteSession(String sessionId);
}
