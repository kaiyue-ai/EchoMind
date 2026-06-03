package com.echomind.memory.usermemory;

import java.util.List;

/** 用户长期画像存储契约。 */
public interface UserMemoryStore {

    // 保存
    void save(UserMemoryEntry entry);

    // 搜索
    List<UserMemoryHit> search(String sessionId,
                               double[] queryVector,
                               int topK,
                               double minConfidence,
                               double minSimilarity);

    // 列表
    List<UserMemoryHit> listBySession(String sessionId, int limit);

    // 删除
    void deleteEntry(String sessionId, String entryId);

    // 删除所有
    void deleteBySession(String sessionId);
}
