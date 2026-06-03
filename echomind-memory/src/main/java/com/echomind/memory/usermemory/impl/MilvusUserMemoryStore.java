package com.echomind.memory.usermemory.impl;

import com.echomind.memory.usermemory.UserMemoryCategory;
import com.echomind.memory.usermemory.UserMemoryEntry;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.memory.usermemory.UserMemoryStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

import static com.echomind.memory.milvus.MilvusVectorStoreSupport.*;

/**
 * Milvus 事实的长期向量存储。
 *
 * <p>将用户长期记忆的向量数据存储在 Milvus Standalone 中。</p>
 */
@Slf4j
public class MilvusUserMemoryStore implements UserMemoryStore {

    private static final String FIELD_PK = "id"; // 主键，会话ID + 入口ID
    private static final String FIELD_SESSION_ID = "session_id";
    private static final String FIELD_ENTRY_ID = "entry_id";
    private static final String FIELD_CATEGORY = "category"; // 分类
    private static final String FIELD_CONTENT = "content"; // 内容
    private static final String FIELD_EVIDENCE = "evidence"; //证据
    private static final String FIELD_CONFIDENCE = "confidence"; // 置信度
    private static final String FIELD_FIRST_OBSERVED_AT = "first_observed_at";
    private static final String FIELD_LAST_OBSERVED_AT = "last_observed_at";
    private static final String FIELD_UPDATED_AT = "updated_at";
    private static final String FIELD_EMBEDDING = "embedding"; // 向量

    private final MilvusServiceClient client; // Milvus 客户端
    private final String collectionName; // 集合名称
    private final ConcurrentHashMap<String, Long> sessionIdHashes = new ConcurrentHashMap<>(); // 会话ID 哈希值缓存

    private volatile boolean collectionReady; // 集合是否准备就绪
    private volatile int indexDimension; // 索引维度

    public MilvusUserMemoryStore(MilvusServiceClient client, String collectionName) {
        this.client = client;
        this.collectionName = collectionName;
    }

    @Override // 保存事实
    public void save(UserMemoryEntry entry) {
        if (entry == null || entry.embedding() == null || entry.embedding().length == 0) {
            return;
        }
        ensureCollection(entry.embedding().length);
        try {
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field(FIELD_SESSION_ID, Collections.singletonList(hashSessionId(entry.sessionId()))));
            fields.add(new InsertParam.Field(FIELD_ENTRY_ID, Collections.singletonList(entry.entryId())));
            fields.add(new InsertParam.Field(FIELD_CATEGORY, Collections.singletonList(entry.category().storageValue())));
            fields.add(new InsertParam.Field(FIELD_CONTENT, Collections.singletonList(entry.content())));
            fields.add(new InsertParam.Field(FIELD_EVIDENCE, Collections.singletonList(entry.evidence() != null ? entry.evidence() : "")));
            fields.add(new InsertParam.Field(FIELD_CONFIDENCE, Collections.singletonList((float) clamp(entry.confidence(), 0, 1))));
            fields.add(new InsertParam.Field(FIELD_FIRST_OBSERVED_AT, Collections.singletonList(toEpochMillis(entry.firstObservedAt()))));
            fields.add(new InsertParam.Field(FIELD_LAST_OBSERVED_AT, Collections.singletonList(toEpochMillis(entry.lastObservedAt()))));
            fields.add(new InsertParam.Field(FIELD_UPDATED_AT, Collections.singletonList(toEpochMillis(entry.updatedAt()))));
            fields.add(new InsertParam.Field(FIELD_EMBEDDING, Collections.singletonList(toFloatList(entry.embedding()))));

            insert(client, collectionName, fields);
        } catch (Exception e) {
            log.warn("Milvus user memory save failed sessionId={} entryId={}: {}",
                entry.sessionId(), entry.entryId(), e.getMessage());
        }
    }

    @Override // 搜索事实
    public List<UserMemoryHit> search(String sessionId,
                                      double[] queryVector,
                                      int topK,
                                      double minConfidence,
                                      double minSimilarity) {
        if (sessionId == null || sessionId.isBlank() || queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        try {
            ensureCollection(queryVector.length);
            String expr = "session_id == " + hashSessionId(sessionId);
            List<List<Float>> vectors = Collections.singletonList(toFloatList(queryVector));
            List<String> outputFields = Arrays.asList(
                FIELD_ENTRY_ID,
                FIELD_CATEGORY,
                FIELD_CONTENT,
                FIELD_EVIDENCE,
                FIELD_CONFIDENCE,
                FIELD_FIRST_OBSERVED_AT,
                FIELD_LAST_OBSERVED_AT,
                FIELD_UPDATED_AT
            );

            R<SearchResults> response = client.search(SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withOutFields(outputFields)
                .withExpr(expr)
                .withVectors(vectors)
                .withVectorFieldName(FIELD_EMBEDDING)
                .withTopK(topK)
                .withMetricType(MetricType.COSINE)
                .withParams("{\"ef\":128}")
                .build());

            if (response.getStatus() != R.Status.Success.getCode()) {
                log.warn("Milvus user memory search failed sessionId={}: {}", sessionId, response.getMessage());
                return List.of();
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<UserMemoryHit> hits = new ArrayList<>();
            for (SearchResultsWrapper.IDScore score : wrapper.getIDScore(0)) {
                float confidence = score.get(FIELD_CONFIDENCE) instanceof Float
                    ? (Float) score.get(FIELD_CONFIDENCE) : 0f;
                if (confidence < minConfidence) {
                    continue;
                }
                double similarity = cosineScoreToSimilarity((float) score.getScore());
                if (similarity < minSimilarity) {
                    continue;
                }
                String entryId = String.valueOf(score.get(FIELD_ENTRY_ID));
                String categoryStr = String.valueOf(score.get(FIELD_CATEGORY));
                String content = String.valueOf(score.get(FIELD_CONTENT));
                String evidence = String.valueOf(score.get(FIELD_EVIDENCE));
                hits.add(new UserMemoryHit(
                    entryId,
                    UserMemoryCategory.from(categoryStr),
                    content,
                    evidence,
                    confidence,
                    toInstant(score.get(FIELD_FIRST_OBSERVED_AT)),
                    toInstant(score.get(FIELD_LAST_OBSERVED_AT)),
                    toInstant(score.get(FIELD_UPDATED_AT)),
                    similarity
                ));
            }
            return hits;
        } catch (Exception e) {
            log.warn("Milvus user memory search failed sessionId={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override // 列表会话下的事实
    public List<UserMemoryHit> listBySession(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank() || limit <= 0) {
            return List.of();
        }
        // Milvus 不支持无向量的 search，用 query 替代
        try {
            ensureCollection(indexDimension > 0 ? indexDimension : 1024);
            String expr = "session_id == " + hashSessionId(sessionId);
            var queryParam = io.milvus.param.dml.QueryParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .withOutFields(Arrays.asList(
                    FIELD_ENTRY_ID,
                    FIELD_CATEGORY,
                    FIELD_CONTENT,
                    FIELD_EVIDENCE,
                    FIELD_CONFIDENCE,
                    FIELD_FIRST_OBSERVED_AT,
                    FIELD_LAST_OBSERVED_AT,
                    FIELD_UPDATED_AT
                ))
                .withLimit((long) limit)
                .build();
            R<QueryResults> response = client.query(queryParam);
            if (response.getStatus() != R.Status.Success.getCode()) {
                log.debug("Milvus user memory list failed sessionId={}: {}", sessionId, response.getMessage());
                return List.of();
            }
            QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
            List<UserMemoryHit> hits = new ArrayList<>();
            for (var row : wrapper.getRowRecords()) {
                String entryId = String.valueOf(row.get(FIELD_ENTRY_ID));
                String categoryStr = String.valueOf(row.get(FIELD_CATEGORY));
                String content = String.valueOf(row.get(FIELD_CONTENT));
                String evidence = String.valueOf(row.get(FIELD_EVIDENCE));
                float confidence = row.get(FIELD_CONFIDENCE) instanceof Float
                    ? (Float) row.get(FIELD_CONFIDENCE) : 0f;
                hits.add(new UserMemoryHit(
                    entryId,
                    UserMemoryCategory.from(categoryStr),
                    content,
                    evidence,
                    confidence,
                    toInstant(row.get(FIELD_FIRST_OBSERVED_AT)),
                    toInstant(row.get(FIELD_LAST_OBSERVED_AT)),
                    toInstant(row.get(FIELD_UPDATED_AT)),
                    0
                ));
            }
            return hits;
        } catch (Exception e) {
            log.debug("Milvus user memory list failed sessionId={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void deleteEntry(String sessionId, String entryId) {
        if (sessionId == null || sessionId.isBlank() || entryId == null || entryId.isBlank()) {
            return;
        }
        String expr = "session_id == " + hashSessionId(sessionId) + " && entry_id == \"" + entryId + "\"";
        delete(client, collectionName, expr);
    }

    @Override
    public void deleteBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String expr = "session_id == " + hashSessionId(sessionId);
        delete(client, collectionName, expr);
    }

    private synchronized void ensureCollection(int dimension) {
        if (collectionReady && indexDimension == dimension) {
            return;
        }
        if (hasCollection(client, collectionName)) {
            collectionReady = true;
            indexDimension = dimension;
            loadCollection(client, collectionName);
            return;
        }
        try {
            List<io.milvus.param.collection.FieldType> fields = List.of(
                pkField(FIELD_PK),
                int64Field(FIELD_SESSION_ID),
                varCharField(FIELD_ENTRY_ID, 256),
                varCharField(FIELD_CATEGORY, 64),
                varCharField(FIELD_CONTENT, 4096),
                varCharField(FIELD_EVIDENCE, 4096),
                floatField(FIELD_CONFIDENCE),
                int64Field(FIELD_FIRST_OBSERVED_AT),
                int64Field(FIELD_LAST_OBSERVED_AT),
                int64Field(FIELD_UPDATED_AT),
                floatVectorField(FIELD_EMBEDDING, dimension)
            );
            createCollection(client, collectionName, fields, "用户长期画像向量存储");
            createHnswIndex(client, collectionName, FIELD_EMBEDDING, "idx_embedding");
            loadCollection(client, collectionName);
            collectionReady = true;
            indexDimension = dimension;
            log.info("Created Milvus user memory collection {} with dimension {}", collectionName, dimension);
        } catch (Exception e) {
            collectionReady = false;
            throw new RuntimeException("Failed to create Milvus user memory collection: " + e.getMessage(), e);
        }
    }

    /** 将 sessionId 哈希为 long，用作 Milvus 标量过滤字段。 */
    private long hashSessionId(String sessionId) {
        return sessionIdHashes.computeIfAbsent(sessionId, id -> (long) id.hashCode());
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
}
