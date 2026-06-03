package com.echomind.memory.knowledge;

import com.echomind.memory.embedding.EmbeddingClient;
import com.echomind.memory.knowledge.entity.AgentKnowledgeDocumentEntity;
import com.echomind.memory.knowledge.mapper.AgentKnowledgeDocumentMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.echomind.memory.milvus.MilvusVectorStoreSupport.*;

/**
 * Agent 私有知识库服务。
 *
 * <p>职责分三段：</p>
 * <ul>
 *   <li>上传：txt/pdf 提取文本，按段落/标点切片，并写入 MySQL 文档表。</li>
 *   <li>索引：每个切片正文和向量都写入 Milvus。</li>
 *   <li>召回：聊天时只按 agentId 在 Milvus 中检索，并扩展命中片段附近窗口。</li>
 * </ul>
 */
@Slf4j
public class AgentKnowledgeService implements AgentKnowledgeManagementPort {

    private static final int WINDOW_RADIUS = 5;
    private static final String FIELD_PK = "id";
    private static final String FIELD_AGENT_ID = "agent_id";
    private static final String FIELD_DOCUMENT_ID = "document_id";
    private static final String FIELD_CHUNK_ID = "chunk_id";
    private static final String FIELD_CHUNK_INDEX = "chunk_index";
    private static final String FIELD_FILE_NAME = "file_name";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_EMBEDDING = "embedding";

    private final AgentKnowledgeDocumentMapper documentMapper;
    private final EmbeddingClient embeddingClient;
    private final AgentKnowledgeTextExtractor textExtractor;
    private final AgentKnowledgeChunker chunker;
    private final MilvusServiceClient milvusClient;
    private final boolean enabled;
    private final String collectionName;
    private final double minVectorSimilarity;

    private volatile boolean collectionReady;
    private volatile int indexDimension;

    public AgentKnowledgeService(AgentKnowledgeDocumentMapper documentMapper,
                                 EmbeddingClient embeddingClient,
                                 MilvusServiceClient milvusClient,
                                 boolean enabled,
                                 String collectionName,
                                 int chunkSize,
                                 double chunkOverlapRatio,
                                 double minVectorSimilarity,
                                 boolean ocrEnabled,
                                 String ocrLanguage,
                                 int ocrDpi,
                                 int ocrMinTextChars,
                                 int ocrMaxPages,
                                 String tesseractCommand) {
        this.documentMapper = documentMapper;
        this.embeddingClient = embeddingClient;
        this.milvusClient = milvusClient;
        this.enabled = enabled;
        this.collectionName = collectionName;
        this.minVectorSimilarity = clamp(minVectorSimilarity, 0, 1);
        this.textExtractor = new AgentKnowledgeTextExtractor(
            ocrEnabled, ocrLanguage, ocrDpi, ocrMinTextChars, ocrMaxPages, tesseractCommand);
        this.chunker = new AgentKnowledgeChunker(chunkSize, chunkOverlapRatio);
    }

    /** 上传并索引一个 Agent 私有知识库文档。 */
    @Override
    @Transactional
    public AgentKnowledgeDocument upload(String agentId, String fileName, long fileSize, byte[] bytes,
                                         String objectUri, String contentType) {
        requireAgentId(agentId);
        if (!enabled) {
            throw new IllegalStateException("知识库向量功能未启用，请先配置向量模型 API Key");
        }
        AgentKnowledgeTextExtractor.ExtractedText extracted = textExtractor.extract(fileName, bytes);
        List<String> chunks = chunker.split(extracted.text());
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文件没有解析出可索引文本");
        }

        AgentKnowledgeDocumentEntity document = new AgentKnowledgeDocumentEntity();
        document.setAgentId(agentId);
        document.setFileName(safeFileName(fileName));
        document.setFileType(extracted.fileType());
        document.setFileSize(fileSize);
        document.setObjectUri(blankToNull(objectUri));
        document.setContentType(blankToNull(contentType));
        document.setChunkCount(0);
        document = documentMapper.upsertById(document);

        int indexed = 0;
        for (int i = 0; i < chunks.size(); i++) {
            Optional<double[]> embedding = embeddingClient.embed(chunks.get(i));
            if (embedding.isEmpty()) {
                continue;
            }
            if (saveMilvus(agentId, document.getId(), document.getFileName(), i, chunks.get(i), embedding.get())) {
                indexed++;
            }
        }
        if (indexed == 0) {
            documentMapper.deleteEntity(document);
            throw new IllegalStateException("文件切片写入 Milvus 向量索引失败，请检查 Milvus 和向量模型配置");
        }
        document.setChunkCount(indexed);
        document = documentMapper.upsertById(document);
        return AgentKnowledgeDocument.from(document);
    }

    /** 查询指定 Agent 的知识库文档。 */
    @Transactional(readOnly = true)
    @Override
    public List<AgentKnowledgeDocument> listDocuments(String agentId) {
        requireAgentId(agentId);
        return documentMapper.selectByAgentIdOrderByCreatedAtDesc(agentId).stream()
            .map(AgentKnowledgeDocument::from)
            .toList();
    }

    /** 删除一份知识库文档及其全部切片。 */
    @Override
    @Transactional
    public void deleteDocument(String agentId, Long documentId) {
        requireAgentId(agentId);
        AgentKnowledgeDocumentEntity document = documentMapper.selectOptionalById(documentId)
            .orElseThrow(() -> new IllegalArgumentException("知识库文档不存在"));
        if (!agentId.equals(document.getAgentId())) {
            throw new IllegalArgumentException("不能删除其它 Agent 的知识库文档");
        }
        documentMapper.deleteEntity(document);
        deleteMilvusDocument(agentId, documentId);
    }

    /** 删除指定 Agent 的全部知识库。 */
    @Override
    @Transactional
    public void deleteAll(String agentId) {
        requireAgentId(agentId);
        for (AgentKnowledgeDocumentEntity document : documentMapper.selectByAgentIdOrderByCreatedAtDesc(agentId)) {
            deleteMilvusDocument(agentId, document.getId());
            documentMapper.deleteEntity(document);
        }
    }

    /** 按用户问题召回 Agent 私有知识库片段。 */
    @Transactional(readOnly = true)
    public List<AgentKnowledgeHit> search(String agentId, String query, int topK) {
        if (!enabled || topK <= 0 || query == null || query.isBlank() || agentId == null || agentId.isBlank()) {
            return List.of();
        }
        Optional<double[]> queryVector = embeddingClient.embed(truncate(query, 2000));
        if (queryVector.isEmpty()) {
            return List.of();
        }
        return search(agentId, query, queryVector.get(), topK);
    }

    /** 使用已计算的查询向量召回 Agent 私有知识库片段。 */
    @Transactional(readOnly = true)
    public List<AgentKnowledgeHit> search(String agentId, String query, double[] queryVector, int topK) {
        if (!enabled || topK <= 0 || query == null || query.isBlank()
            || agentId == null || agentId.isBlank() || queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        List<AgentKnowledgeHit> centers;
        try {
            centers = searchMilvus(agentId, queryVector, expandedTopK(topK)).stream()
                .filter(hit -> hit.score() >= minVectorSimilarity)
                .limit(topK)
                .toList();
        } catch (Exception e) {
            log.warn("Milvus agent knowledge search failed; skipping vector hits agentId={}: {}",
                agentId, e.getMessage());
            return List.of();
        }
        return expandWindows(agentId, centers);
    }

    // ── Milvus 操作 ──

    private boolean saveMilvus(String agentId, Long documentId, String fileName,
                               int chunkIndex, String content, double[] embedding) {
        if (milvusClient == null) {
            return false;
        }
        try {
            ensureCollection(embedding.length);
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field(FIELD_AGENT_ID, Collections.singletonList(hashAgentId(agentId))));
            fields.add(new InsertParam.Field(FIELD_DOCUMENT_ID, Collections.singletonList(documentId)));
            fields.add(new InsertParam.Field(FIELD_CHUNK_ID, Collections.singletonList(syntheticChunkId(documentId, chunkIndex))));
            fields.add(new InsertParam.Field(FIELD_CHUNK_INDEX, Collections.singletonList(chunkIndex)));
            fields.add(new InsertParam.Field(FIELD_FILE_NAME, Collections.singletonList(fileName)));
            fields.add(new InsertParam.Field(FIELD_CONTENT, Collections.singletonList(content)));
            fields.add(new InsertParam.Field(FIELD_EMBEDDING, Collections.singletonList(toFloatList(embedding))));

            return insert(milvusClient, collectionName, fields);
        } catch (Exception e) {
            log.warn("Failed to write agent knowledge vector to Milvus agentId={} documentId={} chunkIndex={}: {}",
                agentId, documentId, chunkIndex, e.getMessage());
            return false;
        }
    }

    private synchronized void ensureCollection(int dimension) {
        if (milvusClient == null || (collectionReady && indexDimension == dimension)) {
            return;
        }
        if (hasCollection(milvusClient, collectionName)) {
            collectionReady = true;
            indexDimension = dimension;
            loadCollection(milvusClient, collectionName);
            return;
        }
        try {
            List<io.milvus.param.collection.FieldType> fields = List.of(
                pkField(FIELD_PK),
                int64Field(FIELD_AGENT_ID),
                int64Field(FIELD_DOCUMENT_ID),
                int64Field(FIELD_CHUNK_ID),
                int32Field(FIELD_CHUNK_INDEX),
                varCharField(FIELD_FILE_NAME, 512),
                varCharField(FIELD_CONTENT, 8192),
                floatVectorField(FIELD_EMBEDDING, dimension)
            );
            createCollection(milvusClient, collectionName, fields, "Agent 私有知识库向量存储");
            createHnswIndex(milvusClient, collectionName, FIELD_EMBEDDING, "idx_embedding");
            loadCollection(milvusClient, collectionName);
            collectionReady = true;
            indexDimension = dimension;
            log.info("Created Milvus agent knowledge collection {} with dimension {}", collectionName, dimension);
        } catch (Exception e) {
            collectionReady = false;
            throw new RuntimeException("Failed to create Milvus agent knowledge collection: " + e.getMessage(), e);
        }
    }

    private List<AgentKnowledgeHit> searchMilvus(String agentId, double[] queryVector, int topK) {
        if (milvusClient == null) {
            return List.of();
        }
        ensureCollection(queryVector.length);
        try {
            String expr = agentFilterExpr(agentId);
            List<List<Float>> vectors = Collections.singletonList(toFloatList(queryVector));
            List<String> outputFields = Arrays.asList(
                FIELD_DOCUMENT_ID, FIELD_CHUNK_ID, FIELD_CHUNK_INDEX, FIELD_FILE_NAME, FIELD_CONTENT);

            R<SearchResults> response = milvusClient.search(SearchParam.newBuilder()
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
                log.warn("Milvus agent knowledge search failed agentId={}: {}", agentId, response.getMessage());
                return List.of();
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<AgentKnowledgeHit> hits = new ArrayList<>();
            for (SearchResultsWrapper.IDScore score : wrapper.getIDScore(0)) {
                Long documentId = asLong(score.get(FIELD_DOCUMENT_ID));
                Long chunkId = asLong(score.get(FIELD_CHUNK_ID));
                int chunkIndex = asInt(score.get(FIELD_CHUNK_INDEX), 0);
                String fileName = asString(score.get(FIELD_FILE_NAME));
                String content = asString(score.get(FIELD_CONTENT));
                double similarity = distanceToSimilarity((float) score.getScore());
                if (documentId != null && chunkId != null && content != null) {
                    hits.add(new AgentKnowledgeHit(chunkId, documentId, fileName, chunkIndex, content, similarity));
                }
            }
            return hits;
        } catch (Exception e) {
            log.warn("Milvus agent knowledge search failed agentId={}: {}", agentId, e.getMessage());
            return List.of();
        }
    }

    private List<AgentKnowledgeHit> searchMilvusWindow(String agentId, AgentKnowledgeHit center) {
        if (milvusClient == null || center == null || center.documentId() == null) {
            return List.of();
        }
        String expr = windowFilterExpr(agentId, center.documentId(), center.chunkIndex());
        try {
            R<QueryResults> response = milvusClient.query(QueryParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .withOutFields(Arrays.asList(FIELD_DOCUMENT_ID, FIELD_CHUNK_ID, FIELD_CHUNK_INDEX, FIELD_FILE_NAME, FIELD_CONTENT))
                .withLimit((long) (WINDOW_RADIUS * 2 + 1))
                .build());
            if (response.getStatus() != R.Status.Success.getCode()) {
                log.warn("Milvus agent knowledge window query failed agentId={} documentId={} chunkIndex={}: {}",
                    agentId, center.documentId(), center.chunkIndex(), response.getMessage());
                return List.of();
            }
            QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
            List<AgentKnowledgeHit> hits = new ArrayList<>();
            for (var row : wrapper.getRowRecords()) {
                Long documentId = asLong(row.get(FIELD_DOCUMENT_ID));
                Long chunkId = asLong(row.get(FIELD_CHUNK_ID));
                int chunkIndex = asInt(row.get(FIELD_CHUNK_INDEX), 0);
                String fileName = asString(row.get(FIELD_FILE_NAME));
                String content = asString(row.get(FIELD_CONTENT));
                if (documentId != null && chunkId != null && content != null) {
                    hits.add(new AgentKnowledgeHit(chunkId, documentId, fileName, chunkIndex, content, center.score()));
                }
            }
            hits.sort(Comparator.comparingLong(AgentKnowledgeHit::documentId)
                .thenComparingInt(AgentKnowledgeHit::chunkIndex));
            return hits;
        } catch (Exception e) {
            log.warn("Milvus agent knowledge window query failed agentId={} documentId={} chunkIndex={}: {}",
                agentId, center.documentId(), center.chunkIndex(), e.getMessage());
            return List.of();
        }
    }

    private void deleteMilvusDocument(String agentId, Long documentId) {
        if (milvusClient == null) {
            return;
        }
        try {
            String expr = documentFilterExpr(agentId, documentId);
            delete(milvusClient, collectionName, expr);
        } catch (Exception e) {
            log.warn("Failed to delete Milvus agent knowledge vectors agentId={} documentId={}: {}",
                agentId, documentId, e.getMessage());
        }
    }

    static String agentFilterExpr(String agentId) {
        return "agent_id == " + hashAgentId(agentId);
    }

    static String documentFilterExpr(String agentId, Long documentId) {
        return agentFilterExpr(agentId) + " && document_id == " + documentId;
    }

    static String windowFilterExpr(String agentId, Long documentId, int centerChunkIndex) {
        int start = Math.max(0, centerChunkIndex - WINDOW_RADIUS);
        int end = centerChunkIndex + WINDOW_RADIUS;
        return documentFilterExpr(agentId, documentId)
            + " && chunk_index >= " + start
            + " && chunk_index <= " + end;
    }

    /** 将 agentId 哈希为 long，用作 Milvus 标量过滤字段。 */
    static long hashAgentId(String agentId) {
        return (long) agentId.hashCode();
    }

    private List<AgentKnowledgeHit> expandWindows(String agentId, List<AgentKnowledgeHit> centers) {
        Map<String, AgentKnowledgeHit> merged = new LinkedHashMap<>();
        for (AgentKnowledgeHit center : centers) {
            List<AgentKnowledgeHit> window = searchMilvusWindow(agentId, center);
            if (window.isEmpty()) {
                window = List.of(center);
            }
            for (AgentKnowledgeHit hit : window) {
                merged.putIfAbsent(hit.documentId() + ":" + hit.chunkIndex(), hit);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private int expandedTopK(int topK) {
        return Math.max(topK, topK * 3);
    }

    private long syntheticChunkId(Long documentId, int chunkIndex) {
        return Integer.toUnsignedLong(Objects.hash(documentId, chunkIndex));
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int asInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private String safeFileName(String fileName) {
        String value = fileName == null || fileName.isBlank() ? "knowledge.txt" : fileName;
        return value.length() <= 255 ? value : value.substring(value.length() - 255);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void requireAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId不能为空");
        }
    }

}
