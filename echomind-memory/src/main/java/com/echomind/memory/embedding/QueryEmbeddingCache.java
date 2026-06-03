package com.echomind.memory.embedding;

import java.util.Map;
import java.util.Optional;

/** 单轮 Pipeline 内复用用户问题向量，避免多个检索阶段重复调用 embedding API。 */
public final class QueryEmbeddingCache {

    // 缓存的向量会写入到 PipelineContext 的属性中
    public static final String ATTRIBUTE_KEY = "queryEmbedding";
    public static final String TEXT_ATTRIBUTE_KEY = "queryEmbeddingText";

    // 私有构造函数
    private QueryEmbeddingCache() {
    }

    // 获取或计算用户问题的向量表示
    public static Optional<double[]> getOrEmbed(Map<String, Object> attributes,
                                                EmbeddingClient embeddingClient,
                                                String text) {
        if (attributes == null || embeddingClient == null || text == null || text.isBlank()) {
            return Optional.empty();
        }
        Object cached = attributes.get(ATTRIBUTE_KEY);
        Object cachedText = attributes.get(TEXT_ATTRIBUTE_KEY);
        if (cached instanceof double[] vector && vector.length > 0 && text.equals(cachedText)) {
            return Optional.of(vector);
        }
        Optional<double[]> vector = embeddingClient.embed(text);
        vector.ifPresent(value -> {
            attributes.put(ATTRIBUTE_KEY, value);
            attributes.put(TEXT_ATTRIBUTE_KEY, text);
        });
        return vector;
    }
}
