package com.echomind.memory.embedding;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryEmbeddingCacheTest {

    @Test
    void reusesQueryEmbeddingWithinSamePipelineAttributes() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        double[] vector = new double[] {0.1, 0.2, 0.3};
        when(embeddingClient.embed("同一轮用户问题")).thenReturn(Optional.of(vector));

        Optional<double[]> first = QueryEmbeddingCache.getOrEmbed(attributes, embeddingClient, "同一轮用户问题");
        Optional<double[]> second = QueryEmbeddingCache.getOrEmbed(attributes, embeddingClient, "同一轮用户问题");

        assertThat(first).containsSame(vector);
        assertThat(second).containsSame(vector);
        verify(embeddingClient, times(1)).embed("同一轮用户问题");
        assertThat(attributes).containsEntry(QueryEmbeddingCache.TEXT_ATTRIBUTE_KEY, "同一轮用户问题");
    }

    @Test
    void recalculatesWhenEmbeddingTextChanges() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        double[] originalVector = new double[] {0.1};
        double[] rewrittenVector = new double[] {0.9};
        when(embeddingClient.embed("今天苹果多少钱")).thenReturn(Optional.of(originalVector));
        when(embeddingClient.embed("今天苹果的价格")).thenReturn(Optional.of(rewrittenVector));

        Optional<double[]> first = QueryEmbeddingCache.getOrEmbed(attributes, embeddingClient, "今天苹果多少钱");
        Optional<double[]> second = QueryEmbeddingCache.getOrEmbed(attributes, embeddingClient, "今天苹果的价格");

        assertThat(first).containsSame(originalVector);
        assertThat(second).containsSame(rewrittenVector);
        assertThat(attributes).containsEntry(QueryEmbeddingCache.TEXT_ATTRIBUTE_KEY, "今天苹果的价格");
        verify(embeddingClient).embed("今天苹果多少钱");
        verify(embeddingClient).embed("今天苹果的价格");
    }
}
