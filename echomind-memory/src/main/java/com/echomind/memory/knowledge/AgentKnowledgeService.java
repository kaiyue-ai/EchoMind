package com.echomind.memory.knowledge;

import com.echomind.memory.embedding.EmbeddingInputPolicy;
import com.echomind.memory.knowledge.entity.AgentKnowledgeDocumentEntity;
import com.echomind.memory.knowledge.mapper.AgentKnowledgeDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.milvus.MilvusSearchRequest;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Agent 私有知识库服务。
 *
 * <p>文档元数据仍由 MySQL 保存；切片正文和向量进入 Spring AI Milvus VectorStore。
 * 召回时先用 VectorStore 找中心切片，再用一个很薄的 Milvus native query 读取同文档前后窗口。</p>
 */
@Slf4j
public class AgentKnowledgeService implements AgentKnowledgeManagementPort {

    static final int WINDOW_RADIUS = 5;
    static final int EMBEDDING_BATCH_SIZE = 10;
    static final String META_AGENT_ID = "agentId";
    static final String META_DOCUMENT_ID = "documentId";
    static final String META_CHUNK_ID = "chunkId";
    static final String META_CHUNK_INDEX = "chunkIndex";
    static final String META_FILE_NAME = "fileName";

    private final AgentKnowledgeDocumentMapper documentMapper;
    private final VectorStore vectorStore;
    private final KnowledgeWindowQuery windowQuery;
    private final AgentKnowledgeTextExtractor textExtractor;
    private final AgentKnowledgeChunker chunker;
    private final boolean enabled;
    private final double minVectorSimilarity;
    private final EmbeddingInputPolicy embeddingInputPolicy;

    public AgentKnowledgeService(AgentKnowledgeDocumentMapper documentMapper,
                                 VectorStore vectorStore,
                                 boolean enabled,
                                 int chunkSize,
                                 double chunkOverlapRatio,
                                 double minVectorSimilarity,
                                 boolean ocrEnabled,
                                 String ocrLanguage,
                                 int ocrDpi,
                                 int ocrMinTextChars,
                                 int ocrMaxPages,
                                 String tesseractCommand,
                                 String collectionName) {
        this(documentMapper, vectorStore, vectorStore == null ? null : new KnowledgeWindowQuery(vectorStore, collectionName),
            enabled, chunkSize, chunkOverlapRatio, minVectorSimilarity, ocrEnabled, ocrLanguage, ocrDpi,
            ocrMinTextChars, ocrMaxPages, tesseractCommand, EmbeddingInputPolicy.defaults());
    }

    public AgentKnowledgeService(AgentKnowledgeDocumentMapper documentMapper,
                                 VectorStore vectorStore,
                                 boolean enabled,
                                 int chunkSize,
                                 double chunkOverlapRatio,
                                 double minVectorSimilarity,
                                 boolean ocrEnabled,
                                 String ocrLanguage,
                                 int ocrDpi,
                                 int ocrMinTextChars,
                                 int ocrMaxPages,
                                 String tesseractCommand,
                                 String collectionName,
                                 EmbeddingInputPolicy embeddingInputPolicy) {
        this(documentMapper, vectorStore, vectorStore == null ? null : new KnowledgeWindowQuery(vectorStore, collectionName),
            enabled, chunkSize, chunkOverlapRatio, minVectorSimilarity, ocrEnabled, ocrLanguage, ocrDpi,
            ocrMinTextChars, ocrMaxPages, tesseractCommand, embeddingInputPolicy);
    }

    AgentKnowledgeService(AgentKnowledgeDocumentMapper documentMapper,
                          VectorStore vectorStore,
                          KnowledgeWindowQuery windowQuery,
                          boolean enabled,
                          int chunkSize,
                          double chunkOverlapRatio,
                          double minVectorSimilarity,
                          boolean ocrEnabled,
                          String ocrLanguage,
                          int ocrDpi,
                          int ocrMinTextChars,
                          int ocrMaxPages,
                          String tesseractCommand) {
        this(documentMapper, vectorStore, windowQuery, enabled, chunkSize, chunkOverlapRatio, minVectorSimilarity,
            ocrEnabled, ocrLanguage, ocrDpi, ocrMinTextChars, ocrMaxPages, tesseractCommand,
            EmbeddingInputPolicy.defaults());
    }

    AgentKnowledgeService(AgentKnowledgeDocumentMapper documentMapper,
                          VectorStore vectorStore,
                          KnowledgeWindowQuery windowQuery,
                          boolean enabled,
                          int chunkSize,
                          double chunkOverlapRatio,
                          double minVectorSimilarity,
                          boolean ocrEnabled,
                          String ocrLanguage,
                          int ocrDpi,
                          int ocrMinTextChars,
                          int ocrMaxPages,
                          String tesseractCommand,
                          EmbeddingInputPolicy embeddingInputPolicy) {
        this.documentMapper = documentMapper;
        this.vectorStore = vectorStore;
        this.windowQuery = windowQuery;
        this.enabled = enabled && vectorStore != null;
        this.minVectorSimilarity = clamp(minVectorSimilarity, 0, 1);
        this.embeddingInputPolicy = embeddingInputPolicy == null ? EmbeddingInputPolicy.defaults() : embeddingInputPolicy;
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

        List<Document> vectorDocuments = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            vectorDocuments.add(toVectorDocument(agentId, document.getId(), document.getFileName(), i, chunks.get(i)));
        }
        try {
            for (int i = 0; i < vectorDocuments.size(); i += EMBEDDING_BATCH_SIZE) {
                int end = Math.min(i + EMBEDDING_BATCH_SIZE, vectorDocuments.size());
                vectorStore.add(vectorDocuments.subList(i, end));
            }
        } catch (Exception e) {
            documentMapper.deleteEntity(document);
            throw new IllegalStateException("文件切片写入 Spring AI Milvus 向量索引失败，请检查 Milvus 和向量模型配置", e);
        }

        document.setChunkCount(vectorDocuments.size());
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
        deleteVectorDocument(agentId, documentId, document.getChunkCount());
    }

    /** 删除指定 Agent 的全部知识库。 */
    @Override
    @Transactional
    public void deleteAll(String agentId) {
        requireAgentId(agentId);
        for (AgentKnowledgeDocumentEntity document : documentMapper.selectByAgentIdOrderByCreatedAtDesc(agentId)) {
            deleteVectorDocument(agentId, document.getId(), document.getChunkCount());
            documentMapper.deleteEntity(document);
        }
    }

    /** 按用户问题召回 Agent 私有知识库片段。 */
    @Transactional(readOnly = true)
    public List<AgentKnowledgeHit> search(String agentId, String query, int topK) {
        if (!enabled || topK <= 0 || query == null || query.isBlank() || agentId == null || agentId.isBlank()) {
            return List.of();
        }
        List<AgentKnowledgeHit> centers;
        try {
            FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
            MilvusSearchRequest request = MilvusSearchRequest.milvusBuilder()
                .query(embeddingInputPolicy.safeQuery(query))
                .topK(expandedTopK(topK))
                .similarityThreshold(minVectorSimilarity)
                .filterExpression(filterBuilder.eq(META_AGENT_ID, agentId).build())
                .searchParamsJson("{\"ef\":128}")
                .build();
            centers = vectorStore.similaritySearch(request).stream()
                .map(this::toHit)
                .filter(Objects::nonNull)
                .limit(topK)
                .toList();
        } catch (Exception e) {
            log.warn("Spring AI Milvus agent knowledge search failed; skipping vector hits agentId={}: {}",
                agentId, e.getMessage());
            return List.of();
        }
        return expandWindows(agentId, centers);
    }

    private Document toVectorDocument(String agentId, Long documentId, String fileName, int chunkIndex, String content) {
        Long chunkId = syntheticChunkId(documentId, chunkIndex);
        return Document.builder()
            .id(vectorDocumentId(agentId, documentId, chunkIndex))
            .text(content)
            .metadata(Map.of(
                META_AGENT_ID, agentId,
                META_DOCUMENT_ID, documentId,
                META_CHUNK_ID, chunkId,
                META_CHUNK_INDEX, chunkIndex,
                META_FILE_NAME, fileName
            ))
            .build();
    }

    private AgentKnowledgeHit toHit(Document document) {
        if (document == null) {
            return null;
        }
        Map<String, Object> metadata = document.getMetadata();
        Long documentId = asLong(metadata.get(META_DOCUMENT_ID));
        Long chunkId = asLong(metadata.get(META_CHUNK_ID));
        int chunkIndex = asInt(metadata.get(META_CHUNK_INDEX), 0);
        String fileName = asString(metadata.get(META_FILE_NAME));
        String content = document.getText();
        double score = document.getScore() == null ? 0 : document.getScore();
        if (documentId == null || chunkId == null || content == null) {
            return null;
        }
        return new AgentKnowledgeHit(chunkId, documentId, fileName, chunkIndex, content, score);
    }

    private void deleteVectorDocument(String agentId, Long documentId, int chunkCount) {
        if (vectorStore == null || documentId == null || chunkCount <= 0) {
            return;
        }
        List<String> ids = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            ids.add(vectorDocumentId(agentId, documentId, i));
        }
        try {
            vectorStore.delete(ids);
        } catch (Exception e) {
            log.warn("Failed to delete Spring AI Milvus agent knowledge vectors agentId={} documentId={}: {}",
                agentId, documentId, e.getMessage());
        }
    }

    private List<AgentKnowledgeHit> expandWindows(String agentId, List<AgentKnowledgeHit> centers) {
        Map<String, AgentKnowledgeHit> merged = new LinkedHashMap<>();
        for (AgentKnowledgeHit center : centers) {
            List<AgentKnowledgeHit> window = windowQuery == null ? List.of() : windowQuery.search(agentId, center);
            if (window.isEmpty()) {
                window = List.of(center);
            }
            for (AgentKnowledgeHit hit : window) {
                merged.putIfAbsent(hit.documentId() + ":" + hit.chunkIndex(), hit);
            }
        }
        List<AgentKnowledgeHit> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparingLong(AgentKnowledgeHit::documentId)
            .thenComparingInt(AgentKnowledgeHit::chunkIndex));
        return result;
    }

    private int expandedTopK(int topK) {
        return Math.max(topK, topK * 3);
    }

    static String vectorDocumentId(String agentId, Long documentId, int chunkIndex) {
        String raw = "knowledge\0" + agentId + "\0" + documentId + "\0" + chunkIndex;
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Long syntheticChunkId(Long documentId, int chunkIndex) {
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

    private String safeFileName(String fileName) {
        String value = fileName == null || fileName.isBlank() ? "knowledge.txt" : fileName;
        return value.length() <= 255 ? value : value.substring(value.length() - 255);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void requireAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId不能为空");
        }
    }
}
