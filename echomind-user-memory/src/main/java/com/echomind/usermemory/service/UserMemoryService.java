package com.echomind.usermemory.service;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.UserMemoryEvent;
import com.echomind.memory.embedding.EmbeddingClient;
import com.echomind.memory.usermemory.UserMemoryCategory;
import com.echomind.memory.usermemory.UserMemoryEntry;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.memory.usermemory.UserMemoryStore;
import com.echomind.memory.usermemory.UserProfileSnapshot;
import com.echomind.memory.usermemory.UserProfileSnapshotStore;
import com.echomind.usermemory.config.UserMemoryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final UserMemoryBatchAnalyzer analyzer;
    private final EmbeddingClient embeddingClient;
    private final UserMemoryProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public void process(UserMemoryEvent event) {
        if (!properties.isEnabled() || event == null || event.sessionId() == null || event.sessionId().isBlank()
            || event.messages() == null || event.messages().isEmpty()) {
            return;
        }
        String userId = normalizeUserId(event.userId());
        UserMemoryBatchTurn turn = new UserMemoryBatchTurn(
            userId,
            event.sessionId(),
            event.agentId(),
            Instant.now(),
            List.copyOf(event.messages())
        );
        appendTurn(userId, turn);
        if (pendingTurns(userId) < batchSize()) {
            return;
        }
        processBatchIfLocked(userId);
    }

    private void appendTurn(String userId, UserMemoryBatchTurn turn) {
        try {
            redis.opsForList().rightPush(bufferKey(userId), mapper.writeValueAsString(turn));
            redis.opsForHash().increment(metaKey(userId), "pendingTurns", 1);
            redis.opsForHash().put(metaKey(userId), "updatedAt", Instant.now().toString());
            applyBufferTtl(userId);
        } catch (Exception e) {
            log.warn("Failed to append user memory buffer userId={}: {}", userId, e.getMessage());
        }
    }

    private void processBatchIfLocked(String userId) {
        String lockKey = lockKey(userId);
        Boolean locked = redis.opsForValue().setIfAbsent(
            lockKey,
            UUID.randomUUID().toString(),
            Duration.ofSeconds(Math.max(1, properties.getBufferLockTtlSeconds()))
        );
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            List<UserMemoryBatchTurn> turns = readBatch(userId);
            if (turns.size() < batchSize()) {
                return;
            }
            if (processBatch(userId, turns)) {
                removeProcessedTurns(userId, turns.size());
            }
        } finally {
            redis.delete(lockKey);
        }
    }

    private boolean processBatch(String userId, List<UserMemoryBatchTurn> turns) {
        String userMemoryKey = userMemoryKey(userId);
        String batchText = turns.stream()
            .flatMap(turn -> turn.messages().stream())
            .filter(message -> message != null && message.content() != null)
            .map(AgentMessage::content)
            .collect(Collectors.joining("\n"));
        Optional<double[]> queryVector = embeddingClient.embed(batchText);
        List<UserMemoryHit> relatedFacts = queryVector
            .map(vector -> vectorStore.search(
                userMemoryKey,
                vector,
                Math.max(1, properties.getRelatedFactTopK()),
                0
            ))
            .orElseGet(List::of);
        String oldProfile = snapshotStore.get(userId).map(UserProfileSnapshot::content).orElse("");
        UserMemoryBatchResult result = analyzer.analyze(userMemoryKey, oldProfile, turns, relatedFacts);
        if (!result.analysisSucceeded()) {
            log.warn("User memory batch analysis returned no valid JSON userId={}, keeping buffer for retry", userId);
            return false;
        }
        int changed = applyFactChanges(userMemoryKey, result, relatedFacts);
        saveProfileSnapshot(userId, result.profileSnapshot(), oldProfile);
        redis.opsForHash().increment(metaKey(userId), "version", 1);
        redis.opsForHash().put(metaKey(userId), "lastProfileUpdatedAt", Instant.now().toString());
        log.info("Processed user memory batch userId={} turns={} factChanges={}",
            userId, turns.size(), changed);
        return true;
    }

    private int applyFactChanges(String userMemoryKey,
                                 UserMemoryBatchResult result,
                                 List<UserMemoryHit> relatedFacts) {
        int changed = 0;
        for (UserMemoryBatchResult.FactToDelete item : result.factsToDelete()) {
            vectorStore.deleteEntry(userMemoryKey, item.factId());
            changed++;
        }
        for (UserMemoryBatchResult.FactToUpdate item : result.factsToUpdate()) {
            Optional<double[]> embedding = embeddingClient.embed(item.content());
            if (embedding.isEmpty()) {
                continue;
            }
            UserMemoryHit old = relatedFacts.stream()
                .filter(hit -> hit.entryId().equals(item.factId()))
                .findFirst()
                .orElse(null);
            vectorStore.save(new UserMemoryEntry(
                userMemoryKey,
                item.factId(),
                item.category() == null ? (old == null ? UserMemoryCategory.INTEREST : old.category()) : item.category(),
                item.content(),
                item.evidence(),
                item.confidence(),
                embedding.get()
            ));
            changed++;
        }
        for (UserMemoryBatchResult.FactToAdd item : dedupeAdds(result.factsToAdd(), relatedFacts)) {
            Optional<double[]> embedding = embeddingClient.embed(item.content());
            if (embedding.isEmpty()) {
                continue;
            }
            vectorStore.save(new UserMemoryEntry(
                userMemoryKey,
                UUID.randomUUID().toString(),
                item.category(),
                item.content(),
                item.evidence(),
                item.confidence(),
                embedding.get()
            ));
            changed++;
        }
        return changed;
    }

    private List<UserMemoryBatchResult.FactToAdd> dedupeAdds(List<UserMemoryBatchResult.FactToAdd> adds,
                                                             List<UserMemoryHit> relatedFacts) {
        if (adds == null || adds.isEmpty()) {
            return List.of();
        }
        List<UserMemoryBatchResult.FactToAdd> result = new ArrayList<>();
        for (UserMemoryBatchResult.FactToAdd add : adds) {
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

    private List<UserMemoryBatchTurn> readBatch(String userId) {
        List<String> rows = redis.opsForList().range(bufferKey(userId), 0, batchSize() - 1);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
            .map(this::parseTurn)
            .flatMap(Optional::stream)
            .sorted(Comparator.comparing(UserMemoryBatchTurn::createdAt))
            .toList();
    }

    private Optional<UserMemoryBatchTurn> parseTurn(String json) {
        try {
            return Optional.of(mapper.readValue(json, UserMemoryBatchTurn.class));
        } catch (Exception e) {
            log.warn("Failed to parse user memory buffer turn: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void removeProcessedTurns(String userId, int count) {
        try {
            redis.opsForList().trim(bufferKey(userId), count, -1);
            redis.opsForHash().increment(metaKey(userId), "pendingTurns", -count);
            Long size = redis.opsForList().size(bufferKey(userId));
            if (size == null || size <= 0) {
                redis.delete(bufferKey(userId));
                redis.opsForHash().put(metaKey(userId), "pendingTurns", "0");
            }
        } catch (Exception e) {
            log.warn("Failed to trim user memory buffer userId={}: {}", userId, e.getMessage());
        }
    }

    private long pendingTurns(String userId) {
        Long size = redis.opsForList().size(bufferKey(userId));
        return size == null ? 0 : size;
    }

    private void applyBufferTtl(String userId) {
        long days = Math.max(1, properties.getBufferTtlDays());
        Duration ttl = Duration.ofDays(days);
        redis.expire(bufferKey(userId), ttl);
        redis.expire(metaKey(userId), ttl);
    }

    private int batchSize() {
        return Math.max(1, properties.getBatchSize());
    }

    private String userMemoryKey(String userId) {
        return "user:" + normalizeUserId(userId);
    }

    private String bufferKey(String userId) {
        return properties.getBufferKeyPrefix() + normalizeUserId(userId);
    }

    private String metaKey(String userId) {
        return properties.getBufferMetaKeyPrefix() + normalizeUserId(userId);
    }

    private String lockKey(String userId) {
        return properties.getBufferLockKeyPrefix() + normalizeUserId(userId);
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase()
            .replaceAll("\\s+", "")
            .replaceAll("[，。！？、；：“”‘’（）【】《》,.!?;:'\"()\\[\\]<>-]", "");
    }
}
