package com.echomind.memory.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

/** VectorStore 的禁用占位实现，用于无 embedding key 时保留安全启动语义。 */
public class DisabledVectorStore implements VectorStore {

    @Override
    public void add(List<Document> documents) {
        throw new IllegalStateException("Vector store is disabled");
    }

    @Override
    public void delete(List<String> idList) {
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return List.of();
    }
}
