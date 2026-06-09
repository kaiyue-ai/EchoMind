package com.echomind.memory.usermemory.impl;

import com.echomind.memory.embedding.EmbeddingInputPolicy;
import com.echomind.memory.usermemory.UserMemoryCategory;
import com.echomind.memory.usermemory.UserMemoryEntry;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.memory.usermemory.UserMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 用户长期事实的 Spring AI VectorStore 实现。 */
@Slf4j
public class SpringAiUserMemoryStore implements UserMemoryStore {

    private static final String META_USER_MEMORY_KEY = "userMemoryKey";
    private static final String META_ENTRY_ID = "entryId";
    private static final String META_CATEGORY = "category";
    private static final String META_EVIDENCE = "evidence";
    private static final String META_CONFIDENCE = "confidence";
    private static final String META_FIRST_OBSERVED_AT = "firstObservedAt";
    private static final String META_LAST_OBSERVED_AT = "lastObservedAt";
    private static final String META_UPDATED_AT = "updatedAt";

    private final VectorStore vectorStore;
    private final EmbeddingInputPolicy embeddingInputPolicy;

    public SpringAiUserMemoryStore(VectorStore vectorStore) {
        this(vectorStore, EmbeddingInputPolicy.defaults());
    }

    public SpringAiUserMemoryStore(VectorStore vectorStore, EmbeddingInputPolicy embeddingInputPolicy) {
        this.vectorStore = vectorStore;
        this.embeddingInputPolicy = embeddingInputPolicy == null ? EmbeddingInputPolicy.defaults() : embeddingInputPolicy;
    }

    @Override
    public void save(UserMemoryEntry entry) {
        if (entry == null || blank(entry.sessionId()) || blank(entry.entryId()) || blank(entry.content())) {
            return;
        }
        try {
            deleteEntry(entry.sessionId(), entry.entryId());
            vectorStore.add(List.of(toDocument(entry)));
        } catch (Exception e) {
            log.warn("Spring AI user memory save failed sessionId={} entryId={}: {}",
                entry.sessionId(), entry.entryId(), e.getMessage());
        }
    }

    @Override
    public List<UserMemoryHit> search(String sessionId,
                                      String query,
                                      int topK,
                                      double minConfidence,
                                      double minSimilarity) {
        if (blank(sessionId) || blank(query) || topK <= 0) {
            return List.of();
        }
        try {
            FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
            SearchRequest request = SearchRequest.builder()
                .query(embeddingInputPolicy.safeQuery(query))
                .topK(topK)
                .similarityThreshold(clampSimilarity(minSimilarity))
                .filterExpression(filterBuilder.eq(META_USER_MEMORY_KEY, sessionId).build())
                .build();
            return vectorStore.similaritySearch(request).stream()
                .map(this::toHit)
                .filter(hit -> hit != null && hit.confidence() >= minConfidence)
                .toList();
        } catch (Exception e) {
            log.warn("Spring AI user memory search failed sessionId={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<UserMemoryHit> listBySession(String sessionId, int limit) {
        return List.of();
    }

    @Override
    public void deleteEntry(String sessionId, String entryId) {
        if (blank(sessionId) || blank(entryId)) {
            return;
        }
        try {
            vectorStore.delete(List.of(documentId(sessionId, entryId)));
        } catch (Exception e) {
            log.debug("Spring AI user memory delete failed sessionId={} entryId={}: {}",
                sessionId, entryId, e.getMessage());
        }
    }

    @Override
    public void deleteBySession(String sessionId) {
        if (blank(sessionId)) {
            return;
        }
        try {
            FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
            vectorStore.delete(filterBuilder.eq(META_USER_MEMORY_KEY, sessionId).build());
        } catch (Exception e) {
            log.debug("Spring AI user memory delete session failed sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    private Document toDocument(UserMemoryEntry entry) {
        UserMemoryCategory category = entry.category() == null ? UserMemoryCategory.INTEREST : entry.category();
        return Document.builder()
            .id(documentId(entry.sessionId(), entry.entryId()))
            .text(entry.content())
            .metadata(Map.of(
                META_USER_MEMORY_KEY, entry.sessionId(),
                META_ENTRY_ID, entry.entryId(),
                META_CATEGORY, category.storageValue(),
                META_EVIDENCE, entry.evidence() == null ? "" : entry.evidence(),
                META_CONFIDENCE, clamp(entry.confidence(), 0, 1),
                META_FIRST_OBSERVED_AT, toEpochMillis(entry.firstObservedAt()),
                META_LAST_OBSERVED_AT, toEpochMillis(entry.lastObservedAt()),
                META_UPDATED_AT, toEpochMillis(entry.updatedAt())
            ))
            .build();
    }

    private UserMemoryHit toHit(Document document) {
        if (document == null) {
            return null;
        }
        Map<String, Object> metadata = document.getMetadata();
        String entryId = asString(metadata.get(META_ENTRY_ID));
        String category = asString(metadata.get(META_CATEGORY));
        String evidence = asString(metadata.get(META_EVIDENCE));
        double confidence = asDouble(metadata.get(META_CONFIDENCE), 0);
        double score = document.getScore() == null ? 0 : document.getScore();
        if (entryId == null || document.getText() == null) {
            return null;
        }
        return new UserMemoryHit(
            entryId,
            UserMemoryCategory.from(category),
            document.getText(),
            evidence,
            confidence,
            toInstant(metadata.get(META_FIRST_OBSERVED_AT)),
            toInstant(metadata.get(META_LAST_OBSERVED_AT)),
            toInstant(metadata.get(META_UPDATED_AT)),
            score
        );
    }

    private static String documentId(String sessionId, String entryId) {
        String raw = "user-memory\0" + sessionId + "\0" + entryId;
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private long toEpochMillis(Instant instant) {
        return (instant == null ? Instant.now() : instant).toEpochMilli();
    }

    private Instant toInstant(Object value) {
        if (value instanceof Number number) {
            return Instant.ofEpochMilli(number.longValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(text));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private double asDouble(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private double clampSimilarity(double value) {
        return Math.max(-1, Math.min(1, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
