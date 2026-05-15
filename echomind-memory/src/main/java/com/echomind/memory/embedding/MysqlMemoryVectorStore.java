package com.echomind.memory.embedding;

import com.echomind.memory.persistence.MemoryEmbeddingEntity;
import com.echomind.memory.persistence.MemoryEmbeddingRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 历史 MySQL 线性向量检索实现。
 *
 * <p>当前普通聊天向量正式路径是 Redis Stack-only，本类不再由主应用自动装配，
 * 仅为旧数据迁移或显式测试保留。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class MysqlMemoryVectorStore implements MemoryVectorStore {

    private static final TypeReference<List<Double>> DOUBLE_LIST = new TypeReference<>() {};

    private final MemoryEmbeddingRepository repository;
    private final ObjectMapper mapper;

    @Override
    @Transactional
    public void save(MemoryVectorRecord record) {
        if (record == null || record.embedding() == null || record.embedding().length == 0) {
            return;
        }
        try {
            MemoryEmbeddingEntity entity = new MemoryEmbeddingEntity();
            entity.setSessionId(record.sessionId());
            entity.setMessageId(record.messageId());
            entity.setRole(record.role());
            entity.setContentPreview(record.contentPreview());
            entity.setEmbeddingJson(mapper.writeValueAsString(record.embedding()));
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to save MySQL memory vector session={} messageId={}: {}",
                record.sessionId(), record.messageId(), e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemorySearchHit> search(String sessionId, double[] queryVector, int topK) {
        if (sessionId == null || sessionId.isBlank() || queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        return repository.findBySessionId(sessionId).stream()
            .map(entity -> toHit(entity, queryVector))
            .filter(hit -> hit.score() > 0)
            .sorted(Comparator.comparingDouble(MemorySearchHit::score).reversed())
            .limit(topK)
            .toList();
    }

    @Override
    @Transactional
    public void deleteSession(String sessionId) {
        repository.deleteBySessionId(sessionId);
    }

    private MemorySearchHit toHit(MemoryEmbeddingEntity entity, double[] queryVector) {
        double[] vector = parseVector(entity.getEmbeddingJson());
        return new MemorySearchHit(
            entity.getMessageId(),
            entity.getRole(),
            entity.getContentPreview(),
            cosine(queryVector, vector)
        );
    }

    private double[] parseVector(String json) {
        if (json == null || json.isBlank()) {
            return new double[0];
        }
        try {
            List<Double> values = mapper.readValue(json, DOUBLE_LIST);
            double[] vector = new double[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = values.get(i);
            }
            return vector;
        } catch (Exception e) {
            log.warn("Failed to parse MySQL memory vector json: {}", e.getMessage());
            return new double[0];
        }
    }

    private double cosine(double[] a, double[] b) {
        if (a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
