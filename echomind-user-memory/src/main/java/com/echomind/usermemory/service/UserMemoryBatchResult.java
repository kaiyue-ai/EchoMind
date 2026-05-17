package com.echomind.usermemory.service;

import com.echomind.memory.usermemory.UserMemoryCategory;

import java.util.List;

/** LLM 一次性返回的事实层变更和用户画像快照。 */
public record UserMemoryBatchResult(
    List<FactToAdd> factsToAdd,
    List<FactToUpdate> factsToUpdate,
    List<FactToDelete> factsToDelete,
    String profileSnapshot,
    boolean analysisSucceeded
) {
    public UserMemoryBatchResult(List<FactToAdd> factsToAdd,
                                 List<FactToUpdate> factsToUpdate,
                                 List<FactToDelete> factsToDelete,
                                 String profileSnapshot) {
        this(factsToAdd, factsToUpdate, factsToDelete, profileSnapshot, true);
    }

    public UserMemoryBatchResult {
        factsToAdd = factsToAdd == null ? List.of() : List.copyOf(factsToAdd);
        factsToUpdate = factsToUpdate == null ? List.of() : List.copyOf(factsToUpdate);
        factsToDelete = factsToDelete == null ? List.of() : List.copyOf(factsToDelete);
    }

    public static UserMemoryBatchResult failed(String oldProfile) {
        return new UserMemoryBatchResult(List.of(), List.of(), List.of(), oldProfile == null ? "" : oldProfile, false);
    }

    public record FactToAdd(String content, UserMemoryCategory category, String evidence, double confidence) {
    }

    public record FactToUpdate(String factId, String content, UserMemoryCategory category, String evidence, double confidence) {
    }

    public record FactToDelete(String factId, String reason) {
    }
}
