package com.echomind.memory.embedding;

import java.util.Map;
import java.util.Optional;

/** 单轮 Pipeline 内复用用户问题向量，避免多个检索阶段重复调用 embedding API。 */
public final class QueryEmbeddingCache {

    // 缓存的向量会写入到 PipelineContext 的属性中
    public static final String ATTRIBUTE_KEY = "queryEmbedding";

    // 私有构造函数
    private QueryEmbeddingCache() {
    }

    public static Optional<double[]> getOrEmbed(Map<String, Object> attributes,
                                                EmbeddingClient embeddingClient,
                                                String text) {
        if (attributes == null || embeddingClient == null || text == null || text.isBlank()) {
            return Optional.empty();
        }
        // ATTRIBUTE_KEY是向量数据库的缓存键,cached则是算出来的向量值
        Object cached = attributes.get(ATTRIBUTE_KEY);
        if (cached instanceof double[] vector && vector.length > 0) {
            return Optional.of(vector);
        }
        Optional<double[]> vector = embeddingClient.embed(text);
        vector.ifPresent(value -> attributes.put(ATTRIBUTE_KEY, value));
        return vector;
    }
}
