package com.echomind.memory.embedding;

import java.util.Optional;

/** 文本向量客户端。 */
public interface EmbeddingClient {

    /**
     * 将文本转为向量。
     *
     * @param text 输入文本
     * @return 向量；配置缺失或调用失败时返回空
     */
    Optional<double[]> embed(String text);
}
