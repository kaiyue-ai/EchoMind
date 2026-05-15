package com.echomind.memory.usermemory;

import java.util.List;

/** 用户长期画像存储契约。 */
public interface UserMemoryStore {

    void save(UserMemoryEntry entry);

    List<UserMemoryHit> search(String sessionId, double[] queryVector, int topK, double minConfidence);

    List<UserMemoryHit> listBySession(String sessionId, int limit);

    void deleteEntry(String sessionId, String entryId);

    void deleteBySession(String sessionId);
}
