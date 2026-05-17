package com.echomind.memory.knowledge;

import com.echomind.memory.embedding.EmbeddingClient;
import com.echomind.memory.redis.RedisKeyScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.echomind.memory.embedding.RedisStackVectorStoreSupport.*;

/**
 * Agent 私有知识库服务。
 *
 * <p>职责分三段：</p>
 * <ul>
 *   <li>上传：txt/pdf 提取文本，按固定窗口切片，并写入 MySQL 文档/切片表。</li>
 *   <li>索引：每个切片向量化后双写 MySQL 和 Redis Stack。</li>
 *   <li>召回：聊天时按 agentId 在该 Agent 自己的知识库中检索，不串到其它 Agent。</li>
 * </ul>
 */
@Slf4j
public class AgentKnowledgeService {

    private static final byte[] FIELD_AGENT_ID = bytes("agent_id");
    private static final byte[] FIELD_DOCUMENT_ID = bytes("document_id");
    private static final byte[] FIELD_CHUNK_ID = bytes("chunk_id");
    private static final byte[] FIELD_CHUNK_INDEX = bytes("chunk_index");
    private static final byte[] FIELD_FILE_NAME = bytes("file_name");
    private static final byte[] FIELD_CONTENT = bytes("content");
    private static final byte[] FIELD_EMBEDDING = bytes("embedding");

    private final AgentKnowledgeDocumentRepository documentRepository;
    private final AgentKnowledgeChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper mapper;
    private final AgentKnowledgeTextExtractor textExtractor;
    private final AgentKnowledgeChunker chunker;
    private final RedisConnectionFactory redisConnectionFactory;
    private final RedisKeyScanner keyScanner;
    private final boolean enabled;
    private final String indexName;
    private final String keyPrefix;
    private final double minVectorSimilarity;
    private final double vectorWeight;
    private final double keywordWeight;
    private final int keywordCandidateLimit;

    private volatile boolean indexReady;
    private volatile int indexDimension;

    public AgentKnowledgeService(AgentKnowledgeDocumentRepository documentRepository,
                                 AgentKnowledgeChunkRepository chunkRepository,
                                 EmbeddingClient embeddingClient,
                                 ObjectMapper mapper,
                                 RedisConnectionFactory redisConnectionFactory,
                                 boolean enabled,
                                 String indexName,
                                 String keyPrefix,
                                 int chunkSize,
                                 int chunkOverlap,
                                 double minVectorSimilarity,
                                 double vectorWeight,
                                 double keywordWeight,
                                 int keywordCandidateLimit,
                                 boolean ocrEnabled,
                                 String ocrLanguage,
                                 int ocrDpi,
                                 int ocrMinTextChars,
                                 int ocrMaxPages,
                                 String tesseractCommand) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingClient = embeddingClient;
        this.mapper = mapper;
        this.redisConnectionFactory = redisConnectionFactory;
        StringRedisTemplate redis = redisConnectionFactory == null ? null : new StringRedisTemplate(redisConnectionFactory);
        if (redis != null) {
            redis.afterPropertiesSet();
        }
        this.keyScanner = redis == null ? null : new RedisKeyScanner(redis);
        this.enabled = enabled;
        this.indexName = blankToDefault(indexName, "idx:echomind:agent:knowledge:vectors");
        this.keyPrefix = blankToDefault(keyPrefix, "echomind:agent:knowledge:vector:");
        this.minVectorSimilarity = clamp(minVectorSimilarity, 0, 1);
        this.vectorWeight = Math.max(0, vectorWeight);
        this.keywordWeight = Math.max(0, keywordWeight);
        this.keywordCandidateLimit = Math.max(1, keywordCandidateLimit);
        this.textExtractor = new AgentKnowledgeTextExtractor(
            ocrEnabled, ocrLanguage, ocrDpi, ocrMinTextChars, ocrMaxPages, tesseractCommand);
        this.chunker = new AgentKnowledgeChunker(chunkSize, chunkOverlap);
    }

    /** 上传并索引一个 Agent 私有知识库文档。 */
    @Transactional
    public AgentKnowledgeDocument upload(String agentId, String fileName, long fileSize, byte[] bytes) {
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
        document.setChunkCount(0);
        document = documentRepository.save(document);

        int indexed = 0;
        for (int i = 0; i < chunks.size(); i++) {
            Optional<double[]> embedding = embeddingClient.embed(chunks.get(i));
            if (embedding.isEmpty()) {
                continue;
            }
            AgentKnowledgeChunkEntity chunk = new AgentKnowledgeChunkEntity();
            chunk.setAgentId(agentId);
            chunk.setDocumentId(document.getId());
            chunk.setFileName(document.getFileName());
            chunk.setChunkIndex(i);
            chunk.setContent(chunks.get(i));
            chunk.setEmbeddingJson("[]");
            chunk = chunkRepository.save(chunk);
            if (saveRedis(chunk, embedding.get())) {
                indexed++;
            } else {
                chunkRepository.delete(chunk);
            }
        }
        if (indexed == 0) {
            chunkRepository.deleteByDocumentId(document.getId());
            documentRepository.delete(document);
            throw new IllegalStateException("文件切片写入 Redis Stack 向量索引失败，请检查 Redis Stack 和向量模型配置");
        }
        document.setChunkCount(indexed);
        document = documentRepository.save(document);
        return AgentKnowledgeDocument.from(document);
    }

    /** 查询指定 Agent 的知识库文档。 */
    @Transactional(readOnly = true)
    public List<AgentKnowledgeDocument> listDocuments(String agentId) {
        requireAgentId(agentId);
        return documentRepository.findByAgentIdOrderByCreatedAtDesc(agentId).stream()
            .map(AgentKnowledgeDocument::from)
            .toList();
    }

    /** 删除一份知识库文档及其全部切片。 */
    @Transactional
    public void deleteDocument(String agentId, Long documentId) {
        requireAgentId(agentId);
        AgentKnowledgeDocumentEntity document = documentRepository.findById(documentId)
            .orElseThrow(() -> new IllegalArgumentException("知识库文档不存在"));
        if (!agentId.equals(document.getAgentId())) {
            throw new IllegalArgumentException("不能删除其它 Agent 的知识库文档");
        }
        chunkRepository.deleteByDocumentId(documentId);
        documentRepository.delete(document);
        deleteRedisDocument(agentId, documentId);
    }

    /** 删除指定 Agent 的全部知识库。 */
    @Transactional
    public void deleteAll(String agentId) {
        requireAgentId(agentId);
        for (AgentKnowledgeDocumentEntity document : documentRepository.findByAgentIdOrderByCreatedAtDesc(agentId)) {
            deleteRedisDocument(agentId, document.getId());
            documentRepository.delete(document);
        }
        chunkRepository.deleteByAgentId(agentId);
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
        List<String> keywords = extractKeywords(query);
        Map<Long, HybridCandidate> candidates = new LinkedHashMap<>();
        try {
            mergeVectorHits(candidates, searchRedis(agentId, queryVector, expandedTopK(topK)), true);
        } catch (Exception e) {
            log.warn("Redis Stack agent knowledge search failed; skipping vector hits agentId={}: {}",
                agentId, e.getMessage());
        }
        mergeKeywordHits(candidates, searchKeyword(agentId, keywords));
        return rankHybridCandidates(candidates.values(), topK);
    }

    private boolean saveRedis(AgentKnowledgeChunkEntity chunk, double[] embedding) {
        if (redisConnectionFactory == null) {
            return false;
        }
        try {
            ensureIndex(embedding.length);
            try (RedisConnection connection = redisConnectionFactory.getConnection()) {
                connection.execute("HSET",
                    bytes(key(chunk.getAgentId(), chunk.getDocumentId(), chunk.getId())),
                    FIELD_AGENT_ID, bytes(chunk.getAgentId()),
                    FIELD_DOCUMENT_ID, bytes(String.valueOf(chunk.getDocumentId())),
                    FIELD_CHUNK_ID, bytes(String.valueOf(chunk.getId())),
                    FIELD_CHUNK_INDEX, bytes(String.valueOf(chunk.getChunkIndex())),
                    FIELD_FILE_NAME, bytes(chunk.getFileName()),
                    FIELD_CONTENT, bytes(chunk.getContent()),
                    FIELD_EMBEDDING, vectorBytes(embedding)
                );
            }
            return true;
        } catch (Exception e) {
            log.warn("Failed to write agent knowledge vector to Redis agentId={} chunkId={}: {}",
                chunk.getAgentId(), chunk.getId(), e.getMessage());
            return false;
        }
    }

    private synchronized void ensureIndex(int dimension) {
        if (redisConnectionFactory == null || (indexReady && indexDimension == dimension)) {
            return;
        }
        if (indexExists()) {
            indexReady = true;
            indexDimension = dimension;
            return;
        }
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            executeStatusCommand(connection, "FT.CREATE",
                bytes(indexName),
                bytes("ON"), bytes("HASH"),
                bytes("PREFIX"), bytes("1"), bytes(keyPrefix),
                bytes("SCHEMA"),
                bytes("agent_id"), bytes("TAG"),
                bytes("document_id"), bytes("NUMERIC"),
                bytes("chunk_id"), bytes("NUMERIC"),
                bytes("chunk_index"), bytes("NUMERIC"),
                bytes("file_name"), bytes("TEXT"),
                bytes("content"), bytes("TEXT"),
                bytes("embedding"), bytes("VECTOR"), bytes("HNSW"), bytes("6"),
                bytes("TYPE"), bytes("FLOAT32"),
                bytes("DIM"), bytes(String.valueOf(dimension)),
                bytes("DISTANCE_METRIC"), bytes("COSINE")
            );
            indexReady = true;
            indexDimension = dimension;
            log.info("Created Redis Stack agent knowledge index {} with dimension {}", indexName, dimension);
        } catch (Exception e) {
            if (isIndexAlreadyExists(e)) {
                indexReady = true;
                indexDimension = dimension;
                return;
            }
            indexReady = false;
            throw e;
        }
    }

    private boolean indexExists() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            Object raw = executeArrayCommand(connection, "FT._LIST");
            List<?> indexes = asList(raw);
            if (indexes.isEmpty()) {
                return false;
            }
            for (Object index : indexes) {
                if (indexName.equals(text(index))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private List<AgentKnowledgeHit> searchRedis(String agentId, double[] queryVector, int topK) {
        if (redisConnectionFactory == null) {
            return List.of();
        }
        ensureIndex(queryVector.length);
        String query = "(@agent_id:{" + escapeTag(agentId) + "})=>[KNN "
            + topK + " @embedding $vec AS score]";
        Object raw;
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            raw = executeArrayCommand(connection, "FT.SEARCH",
                bytes(indexName),
                bytes(query),
                bytes("PARAMS"), bytes("2"), bytes("vec"), vectorBytes(queryVector),
                bytes("SORTBY"), bytes("score"), bytes("ASC"),
                bytes("RETURN"), bytes("6"), bytes("document_id"), bytes("chunk_id"),
                bytes("chunk_index"), bytes("file_name"), bytes("content"), bytes("score"),
                bytes("DIALECT"), bytes("2")
            );
        }
        return parseSearchResult(raw);
    }

    private List<AgentKnowledgeHit> parseSearchResult(Object raw) {
        List<?> rows = asList(raw);
        if (rows.size() <= 1) {
            return List.of();
        }
        List<?> structuredResults = findValue(rows, "results");
        if (!structuredResults.isEmpty()) {
            return parseStructuredResults(structuredResults);
        }
        List<AgentKnowledgeHit> hits = new ArrayList<>();
        for (int i = 1; i + 1 < rows.size(); i += 2) {
            Object fieldsRaw = rows.get(i + 1);
            List<?> fields = asList(fieldsRaw);
            if (fields.isEmpty()) {
                continue;
            }
            AgentKnowledgeHit hit = parseFields(fields);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    private List<AgentKnowledgeHit> parseStructuredResults(List<?> results) {
        List<AgentKnowledgeHit> hits = new ArrayList<>();
        for (Object result : results) {
            List<?> resultFields = asList(result);
            if (resultFields.isEmpty()) {
                continue;
            }
            AgentKnowledgeHit hit = parseFields(findValue(resultFields, "extra_attributes"));
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    private AgentKnowledgeHit parseFields(List<?> fields) {
        Long documentId = null;
        Long chunkId = null;
        int chunkIndex = 0;
        String fileName = null;
        String content = null;
        double score = 0;
        for (int j = 0; j + 1 < fields.size(); j += 2) {
            String name = text(fields.get(j));
            Object value = fields.get(j + 1);
            if ("document_id".equals(name)) {
                documentId = parseLong(value);
            } else if ("chunk_id".equals(name)) {
                chunkId = parseLong(value);
            } else if ("chunk_index".equals(name)) {
                Long ci = parseLong(value);
                chunkIndex = ci != null ? ci.intValue() : 0;
            } else if ("file_name".equals(name)) {
                fileName = text(value);
            } else if ("content".equals(name)) {
                content = text(value);
            } else if ("score".equals(name)) {
                score = distanceToSimilarity(parseDouble(value));
            }
        }
        return documentId != null && chunkId != null && content != null
            ? new AgentKnowledgeHit(chunkId, documentId, fileName, chunkIndex, content, score)
            : null;
    }

    private List<AgentKnowledgeHit> searchKeyword(String agentId, List<String> keywords) {
        if (keywords.isEmpty()) {
            return List.of();
        }
        Map<Long, AgentKnowledgeHit> hits = new LinkedHashMap<>();
        for (String keyword : keywords) {
            String pattern = "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";
            for (AgentKnowledgeChunkEntity entity : chunkRepository.searchKeywordCandidates(
                agentId, pattern, PageRequest.of(0, keywordCandidateLimit))) {
                double keywordScore = keywordScore(entity, keywords);
                if (keywordScore <= 0) {
                    continue;
                }
                AgentKnowledgeHit existing = hits.get(entity.getId());
                if (existing == null || keywordScore > existing.score()) {
                    hits.put(entity.getId(), new AgentKnowledgeHit(
                        entity.getId(),
                        entity.getDocumentId(),
                        entity.getFileName(),
                        entity.getChunkIndex(),
                        entity.getContent(),
                        keywordScore
                    ));
                }
            }
        }
        return hits.values().stream()
            .sorted(Comparator.comparingDouble(AgentKnowledgeHit::score).reversed())
            .limit(keywordCandidateLimit)
            .toList();
    }

    private void mergeVectorHits(Map<Long, HybridCandidate> candidates,
                                 List<AgentKnowledgeHit> hits,
                                 boolean respectThreshold) {
        for (AgentKnowledgeHit hit : hits) {
            if (respectThreshold && hit.score() < minVectorSimilarity) {
                continue;
            }
            HybridCandidate candidate = candidates.computeIfAbsent(hit.chunkId(), id -> new HybridCandidate(hit));
            candidate.vectorScore = Math.max(candidate.vectorScore, hit.score());
        }
    }

    private void mergeKeywordHits(Map<Long, HybridCandidate> candidates, List<AgentKnowledgeHit> hits) {
        for (AgentKnowledgeHit hit : hits) {
            HybridCandidate candidate = candidates.computeIfAbsent(hit.chunkId(), id -> new HybridCandidate(hit));
            candidate.keywordScore = Math.max(candidate.keywordScore, hit.score());
        }
    }

    private List<AgentKnowledgeHit> rankHybridCandidates(Collection<HybridCandidate> candidates, int topK) {
        double totalWeight = vectorWeight + keywordWeight;
        double safeTotalWeight = totalWeight <= 0 ? 1 : totalWeight;
        return candidates.stream()
            .filter(candidate -> candidate.vectorScore >= minVectorSimilarity || candidate.keywordScore > 0)
            .sorted(Comparator.comparingDouble(
                candidate -> -((candidate.vectorScore * vectorWeight + candidate.keywordScore * keywordWeight) / safeTotalWeight)))
            .limit(topK)
            .map(candidate -> candidate.hitWithScore(
                (candidate.vectorScore * vectorWeight + candidate.keywordScore * keywordWeight) / safeTotalWeight))
            .toList();
    }

    private void deleteRedisDocument(String agentId, Long documentId) {
        if (keyScanner == null) {
            return;
        }
        try {
            keyScanner.deleteByPattern(keyPrefix + encodeId(agentId) + ":" + documentId + ":*");
        } catch (Exception e) {
            log.warn("Failed to delete Redis agent knowledge vectors agentId={} documentId={}: {}",
                agentId, documentId, e.getMessage());
        }
    }

    private double keywordScore(AgentKnowledgeChunkEntity entity, List<String> keywords) {
        String content = (safeText(entity.getFileName()) + " " + safeText(entity.getContent())).toLowerCase(Locale.ROOT);
        if (content.isBlank() || keywords.isEmpty()) {
            return 0;
        }
        int matched = 0;
        int occurrences = 0;
        for (String keyword : keywords) {
            int count = countOccurrences(content, keyword.toLowerCase(Locale.ROOT));
            if (count > 0) {
                matched++;
                occurrences += count;
            }
        }
        if (matched == 0) {
            return 0;
        }
        double coverage = (double) matched / keywords.size();
        double densityBonus = Math.min(0.25, occurrences * 0.03);
        return clamp(coverage + densityBonus, 0, 1);
    }

    private int countOccurrences(String content, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return 0;
        }
        int count = 0;
        int index = content.indexOf(keyword);
        while (index >= 0) {
            count++;
            index = content.indexOf(keyword, index + keyword.length());
        }
        return count;
    }

    private List<String> extractKeywords(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalized = query.toLowerCase(Locale.ROOT)
            .replaceAll("[\\p{Punct}，。！？、；：“”‘’（）【】《》]+", " ");
        List<String> result = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (isUsefulKeyword(token)) {
                result.add(token);
            }
        }
        if (result.isEmpty() && normalized.replaceAll("\\s+", "").length() >= 2) {
            result.add(normalized.replaceAll("\\s+", ""));
        }
        return result.stream().distinct().limit(8).toList();
    }

    private boolean isUsefulKeyword(String token) {
        if (token == null) {
            return false;
        }
        String value = token.trim();
        if (value.length() < 2) {
            return false;
        }
        return !Set.of("什么", "怎么", "如何", "为什么", "这个", "那个", "一下", "the", "and", "for", "with")
            .contains(value);
    }

    private int expandedTopK(int topK) {
        return Math.max(topK, topK * 3);
    }

    private String escapeLike(String value) {
        return value
            .replace("!", "!!")
            .replace("%", "!%")
            .replace("_", "!_");
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private String key(String agentId, Long documentId, Long chunkId) {
        return keyPrefix + encodeId(agentId) + ":" + documentId + ":" + chunkId;
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

    private void requireAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId不能为空");
        }
    }

    private static class HybridCandidate {
        private final AgentKnowledgeHit hit;
        private double vectorScore;
        private double keywordScore;

        private HybridCandidate(AgentKnowledgeHit hit) {
            this.hit = hit;
        }

        private AgentKnowledgeHit hitWithScore(double score) {
            return new AgentKnowledgeHit(
                hit.chunkId(),
                hit.documentId(),
                hit.fileName(),
                hit.chunkIndex(),
                hit.content(),
                score
            );
        }
    }
}
