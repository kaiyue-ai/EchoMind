package com.echomind.usermemory.service;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MemoryDecision;
import com.echomind.common.model.UserMemoryEvent;
import com.echomind.memory.usermemory.UserMemoryCategory;
import com.echomind.memory.usermemory.UserMemoryEntry;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.memory.usermemory.UserMemoryStore;
import com.echomind.memory.usermemory.UserProfileSnapshot;
import com.echomind.memory.usermemory.UserProfileSnapshotStore;
import com.echomind.usermemory.config.UserMemoryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserMemoryService {

    private final UserMemoryStore vectorStore;
    private final UserProfileSnapshotStore snapshotStore;
    private final UserMemoryAnalyzer analyzer;
    private final UserMemoryProperties properties;

    public void process(UserMemoryEvent event) {
        ingest(event);
    }

    /** 收到 RabbitMQ 事件后立即异步处理，不再放入用户级缓冲区等待 flush。 */
    public void ingest(UserMemoryEvent event) {
        if (!properties.isEnabled() || event == null || event.sessionId() == null || event.sessionId().isBlank()
            || event.messages() == null || event.messages().isEmpty()) {
            return;
        }
        MemoryDecision decision = event.memoryDecision() == null ? MemoryDecision.FALLBACK : event.memoryDecision();
        if (!decision.shouldProcessUserMemory()) {
            return;
        }
        String userId = normalizeUserId(event.userId());
        UserMemoryTurn turn = new UserMemoryTurn(
            userId,
            event.sessionId(),
            event.agentId(),
            Instant.now(),
            userMessages(event.messages())
        );
        if (turn.messages().isEmpty()) {
            return;
        }
        processTurn(userId, turn, decision);
    }

    private void processTurn(String userId, UserMemoryTurn turn, MemoryDecision decision) {
        String userMemoryKey = userMemoryKey(userId);
        String batchText = turn.messages().stream()
            .filter(message -> message != null && message.content() != null)
            .map(AgentMessage::content)
            .collect(Collectors.joining("\n"));
        List<UserMemoryHit> relatedFacts = findRelatedFacts(userMemoryKey, batchText);
        String oldProfile = snapshotStore.get(userId).map(UserProfileSnapshot::content).orElse("");
        UserMemoryAnalysisResult result = analyzer.analyze(
            userMemoryKey,
            oldProfile,
            turn,
            relatedFacts,
            decision.rememberFacts(),
            decision.refreshProfile()
        );
        if (!result.analysisSucceeded()) {
            log.warn("User memory analysis returned no valid JSON userId={}, sessionId={}", userId, turn.sessionId());
            return;
        }
        int changed = decision.rememberFacts()
            ? applyFactChanges(userMemoryKey, turn.createdAt(), result, relatedFacts)
            : 0;
        if (decision.refreshProfile()) {
            saveProfileSnapshot(userId, result.profileSnapshot(), oldProfile);
        }
        log.info("Processed user memory userId={} sessionId={} rememberFacts={} refreshProfile={} factChanges={}",
            userId, turn.sessionId(), decision.rememberFacts(), decision.refreshProfile(), changed);
    }

    private List<UserMemoryHit> findRelatedFacts(String userMemoryKey, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return vectorStore.search(
            userMemoryKey,
            text,
            Math.max(1, properties.getRelatedFactTopK()),
            Math.max(0, properties.getMinConfidence()),
            clampSimilarity(properties.getMergeMinSimilarity())
        );
    }

    private int applyFactChanges(String userMemoryKey,
                                 Instant observedAt,
                                 UserMemoryAnalysisResult result,
                                 List<UserMemoryHit> relatedFacts) {
        int changed = 0;
        for (UserMemoryAnalysisResult.FactToDelete item : result.factsToDelete()) {
            vectorStore.deleteEntry(userMemoryKey, item.factId());
            changed++;
        }
        for (UserMemoryAnalysisResult.FactToUpdate item : result.factsToUpdate()) {
            UserMemoryHit old = relatedFacts.stream()
                .filter(hit -> hit.entryId().equals(item.factId()))
                .findFirst()
                .orElse(null);
            vectorStore.deleteEntry(userMemoryKey, item.factId());
            vectorStore.save(new UserMemoryEntry(
                userMemoryKey,
                item.factId(),
                item.category() == null ? (old == null ? UserMemoryCategory.INTEREST : old.category()) : item.category(),
                item.content(),
                item.evidence(),
                item.confidence(),
                old == null || old.firstObservedAt() == null ? observedAt : old.firstObservedAt(),
                observedAt,
                Instant.now()
            ));
            changed++;
        }
        for (UserMemoryAnalysisResult.FactToAdd item : dedupeAdds(result.factsToAdd(), relatedFacts)) {
            vectorStore.save(new UserMemoryEntry(
                userMemoryKey,
                UUID.randomUUID().toString(),
                item.category(),
                item.content(),
                item.evidence(),
                item.confidence(),
                observedAt,
                observedAt,
                Instant.now()
            ));
            changed++;
        }
        return changed;
    }

    private List<UserMemoryAnalysisResult.FactToAdd> dedupeAdds(List<UserMemoryAnalysisResult.FactToAdd> adds,
                                                                List<UserMemoryHit> relatedFacts) {
        if (adds == null || adds.isEmpty()) {
            return List.of();
        }
        List<UserMemoryAnalysisResult.FactToAdd> result = new ArrayList<>();
        for (UserMemoryAnalysisResult.FactToAdd add : adds) {
            boolean duplicate = relatedFacts.stream()
                .anyMatch(hit -> normalize(hit.content()).equals(normalize(add.content())));
            boolean duplicateInBatch = result.stream()
                .anyMatch(existing -> normalize(existing.content()).equals(normalize(add.content())));
            if (!duplicate && !duplicateInBatch) {
                result.add(add);
            }
        }
        return result;
    }

    private void saveProfileSnapshot(String userId, String newProfile, String oldProfile) {
        String profile = newProfile == null || newProfile.isBlank() ? oldProfile : newProfile.trim();
        if (profile == null || profile.isBlank()) {
            return;
        }
        int maxChars = Math.max(1, properties.getProfileMaxChars());
        if (profile.length() > maxChars) {
            log.warn("Skip overlong user profile snapshot userId={} length={} max={}",
                userId, profile.length(), maxChars);
            return;
        }
        int nextVersion = snapshotStore.get(userId)
            .map(UserProfileSnapshot::version)
            .map(version -> version + 1)
            .orElse(1);
        snapshotStore.save(new UserProfileSnapshot(userId, profile, nextVersion, Instant.now()));
    }

    private String userMemoryKey(String userId) {
        return "user:" + normalizeUserId(userId);
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }

    private List<AgentMessage> userMessages(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
            .filter(message -> message != null && "user".equals(message.role()))
            .toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase()
            .replaceAll("\\s+", "")
            .replaceAll("[，。！？、；：“”‘’（）【】《》,.!?;:'\"()\\[\\]<>-]", "");
    }

    private double clampSimilarity(double value) {
        return Math.max(-1, Math.min(1, value));
    }
}
