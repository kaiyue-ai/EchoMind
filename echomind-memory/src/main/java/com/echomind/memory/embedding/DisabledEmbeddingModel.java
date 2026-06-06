package com.echomind.memory.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/** Spring AI EmbeddingModel 的禁用占位实现，用于保留无 API key 时的安全启动语义。 */
public class DisabledEmbeddingModel implements EmbeddingModel {

    private final int dimension;

    public DisabledEmbeddingModel(int dimension) {
        this.dimension = Math.max(1, dimension);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        throw new IllegalStateException("Embedding model is disabled");
    }

    @Override
    public float[] embed(Document document) {
        throw new IllegalStateException("Embedding model is disabled");
    }

    @Override
    public int dimensions() {
        return dimension;
    }
}
