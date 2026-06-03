package com.echomind.usermemory.service;

import com.echomind.memory.usermemory.UserMemoryCategory;

import java.util.List;

/** 轻量模型针对单轮对话产出的用户记忆变更。 */
public record UserMemoryAnalysisResult(
    List<FactToAdd> factsToAdd,
    List<FactToUpdate> factsToUpdate,
    List<FactToDelete> factsToDelete,
    String profileSnapshot,
    boolean analysisSucceeded
) {
    public UserMemoryAnalysisResult(List<FactToAdd> factsToAdd,
                                    List<FactToUpdate> factsToUpdate,
                                    List<FactToDelete> factsToDelete,
                                    String profileSnapshot) {
        this(factsToAdd, factsToUpdate, factsToDelete, profileSnapshot, true);
    }

    public UserMemoryAnalysisResult {
        factsToAdd = factsToAdd == null ? List.of() : List.copyOf(factsToAdd);
        factsToUpdate = factsToUpdate == null ? List.of() : List.copyOf(factsToUpdate);
        factsToDelete = factsToDelete == null ? List.of() : List.copyOf(factsToDelete);
    }

    public static UserMemoryAnalysisResult failed(String oldProfile) {
        return new UserMemoryAnalysisResult(List.of(), List.of(), List.of(), oldProfile == null ? "" : oldProfile, false);
    }

    public record FactToAdd(String content, UserMemoryCategory category, String evidence, double confidence) {
    }

    public record FactToUpdate(String factId, String content, UserMemoryCategory category, String evidence, double confidence) {
    }

    public record FactToDelete(String factId, String reason) {
    }
}
