package com.echomind.memory.embedding;

import java.util.Optional;

/** 未配置 API Key 时的空实现，保证记忆主链路不被向量功能阻断。 */
public class DisabledEmbeddingClient implements EmbeddingClient {

    @Override
    public Optional<double[]> embed(String text) {
        return Optional.empty();
    }
}
