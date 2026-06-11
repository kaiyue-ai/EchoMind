package com.echomind.memory.usermemory.impl;

import com.echomind.memory.usermemory.UserMemoryEntry;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.memory.usermemory.UserMemoryStore;

import java.util.List;

/** 向量存储不可用时的用户记忆空实现。 */
public class NoopUserMemoryStore implements UserMemoryStore {

    @Override
    public void save(UserMemoryEntry entry) {
    }

    @Override
    public List<UserMemoryHit> search(String sessionId,
                                      String query,
                                      int topK,
                                      double minConfidence,
                                      double minSimilarity) {
        return List.of();
    }

    @Override
    public List<UserMemoryHit> listBySession(String sessionId, int limit) {
        return List.of();
    }

    @Override
    public void deleteEntry(String sessionId, String entryId) {
    }

    @Override
    public void deleteBySession(String sessionId) {
    }
}
