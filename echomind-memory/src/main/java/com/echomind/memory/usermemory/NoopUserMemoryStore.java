package com.echomind.memory.usermemory;

import java.util.List;

/** Redis Stack 不可用时的用户画像空实现。 */
public class NoopUserMemoryStore implements UserMemoryStore {

    @Override
    public void save(UserMemoryEntry entry) {
    }

    @Override
    public List<UserMemoryHit> search(String sessionId, double[] queryVector, int topK, double minConfidence) {
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
