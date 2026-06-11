package com.echomind.memory.usermemory.impl;

import com.echomind.memory.embedding.EmbeddingInputPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiUserMemoryStoreTest {

    @Test
    void trimsOverlongSearchQueryBeforeVectorSearch() {
        RecordingVectorStore vectorStore = new RecordingVectorStore();
        SpringAiUserMemoryStore store = new SpringAiUserMemoryStore(vectorStore, new EmbeddingInputPolicy(30));

        store.search("user:default", "HEAD-" + "x".repeat(80) + "-TAIL", 3, 0.3, 0.4);

        assertThat(vectorStore.requests).hasSize(1);
        assertThat(vectorStore.requests.get(0).getQuery())
            .hasSize(30)
            .startsWith("HEAD-")
            .contains("\n...\n")
            .endsWith("-TAIL");
    }

    private static class RecordingVectorStore implements VectorStore {

        private final List<SearchRequest> requests = new ArrayList<>();

        @Override
        public void add(List<Document> documents) {
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            requests.add(request);
            return List.of();
        }
    }
}
