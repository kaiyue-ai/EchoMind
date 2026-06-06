package com.echomind.memory.knowledge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Spring AI VectorStore 暂不暴露纯标量范围查询；这里只保留知识库窗口扩展所需的最小 native query。
 */
@Slf4j
class KnowledgeWindowQuery {

    private static final String FIELD_DOC_ID = MilvusVectorStore.DOC_ID_FIELD_NAME;
    private static final String FIELD_CONTENT = MilvusVectorStore.CONTENT_FIELD_NAME;
    private static final String FIELD_METADATA = MilvusVectorStore.METADATA_FIELD_NAME;

    private final VectorStore vectorStore;
    private final String collectionName;

    KnowledgeWindowQuery(VectorStore vectorStore, String collectionName) {
        this.vectorStore = vectorStore;
        this.collectionName = collectionName;
    }

    List<AgentKnowledgeHit> search(String agentId, AgentKnowledgeHit center) {
        if (vectorStore == null || center == null || center.documentId() == null) {
            return List.of();
        }
        return vectorStore.getNativeClient()
            .filter(MilvusServiceClient.class::isInstance)
            .map(MilvusServiceClient.class::cast)
            .map(client -> queryWindow(client, agentId, center))
            .orElseGet(List::of);
    }

    private List<AgentKnowledgeHit> queryWindow(MilvusServiceClient client, String agentId, AgentKnowledgeHit center) {
        String expr = windowFilterExpr(agentId, center.documentId(), center.chunkIndex());
        try {
            R<QueryResults> response = client.query(QueryParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .withOutFields(List.of(FIELD_DOC_ID, FIELD_CONTENT, FIELD_METADATA))
                .withLimit((long) (AgentKnowledgeService.WINDOW_RADIUS * 2 + 1))
                .build());
            if (response.getStatus() != R.Status.Success.getCode()) {
                log.warn("Spring AI Milvus knowledge window query failed agentId={} documentId={} chunkIndex={}: {}",
                    agentId, center.documentId(), center.chunkIndex(), response.getMessage());
                return List.of();
            }
            QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
            List<AgentKnowledgeHit> hits = new ArrayList<>();
            for (var row : wrapper.getRowRecords()) {
                String content = asString(row.get(FIELD_CONTENT));
                JsonObject metadata = metadata(row.get(FIELD_METADATA));
                if (metadata == null || content == null) {
                    continue;
                }
                Long documentId = jsonLong(metadata, AgentKnowledgeService.META_DOCUMENT_ID);
                Long chunkId = jsonLong(metadata, AgentKnowledgeService.META_CHUNK_ID);
                int chunkIndex = jsonInt(metadata, AgentKnowledgeService.META_CHUNK_INDEX, 0);
                String fileName = jsonString(metadata, AgentKnowledgeService.META_FILE_NAME);
                if (documentId != null && chunkId != null) {
                    hits.add(new AgentKnowledgeHit(chunkId, documentId, fileName, chunkIndex, content, center.score()));
                }
            }
            hits.sort(Comparator.comparingLong(AgentKnowledgeHit::documentId)
                .thenComparingInt(AgentKnowledgeHit::chunkIndex));
            return hits;
        } catch (Exception e) {
            log.warn("Spring AI Milvus knowledge window query failed agentId={} documentId={} chunkIndex={}: {}",
                agentId, center.documentId(), center.chunkIndex(), e.getMessage());
            return List.of();
        }
    }

    static String windowFilterExpr(String agentId, Long documentId, int centerChunkIndex) {
        int start = Math.max(0, centerChunkIndex - AgentKnowledgeService.WINDOW_RADIUS);
        int end = centerChunkIndex + AgentKnowledgeService.WINDOW_RADIUS;
        return "metadata[\"%s\"] == %s && metadata[\"%s\"] == %d && metadata[\"%s\"] >= %d && metadata[\"%s\"] <= %d"
            .formatted(
                AgentKnowledgeService.META_AGENT_ID, quote(agentId),
                AgentKnowledgeService.META_DOCUMENT_ID, documentId,
                AgentKnowledgeService.META_CHUNK_INDEX, start,
                AgentKnowledgeService.META_CHUNK_INDEX, end
            );
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }

    private JsonObject metadata(Object value) {
        if (value instanceof JsonObject jsonObject) {
            return jsonObject;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return JsonParser.parseString(text).getAsJsonObject();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Long jsonLong(JsonObject metadata, String key) {
        if (!metadata.has(key) || metadata.get(key).isJsonNull()) {
            return null;
        }
        try {
            return metadata.get(key).getAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    private int jsonInt(JsonObject metadata, String key, int defaultValue) {
        if (!metadata.has(key) || metadata.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return metadata.get(key).getAsInt();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String jsonString(JsonObject metadata, String key) {
        if (!metadata.has(key) || metadata.get(key).isJsonNull()) {
            return null;
        }
        return metadata.get(key).getAsString();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
