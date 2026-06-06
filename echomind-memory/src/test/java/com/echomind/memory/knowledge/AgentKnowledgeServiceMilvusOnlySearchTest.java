package com.echomind.memory.knowledge;

import com.echomind.memory.knowledge.entity.AgentKnowledgeDocumentEntity;
import com.echomind.memory.knowledge.mapper.AgentKnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentKnowledgeServiceSpringAiSearchTest {

    @Test
    void doesNotFallBackToMysqlSearchWhenVectorStoreIsUnavailable() {
        AgentKnowledgeService service = service(null, 0.6);

        List<AgentKnowledgeHit> hits = service.search("agent-1", "进程退出", 3);

        assertThat(hits).isEmpty();
    }

    @Test
    void springAiMilvusSearchReturnsVectorHitsAndScopesRequestToAgent() {
        RecordingVectorStore vectorStore = new RecordingVectorStore(List.of(
            Document.builder()
                .id("hit-1")
                .text("银杏计划发布窗口是每周二 09:30 到 11:00，回滚口令是 gingko-rollback-7429。")
                .metadata(Map.of(
                    AgentKnowledgeService.META_AGENT_ID, "agent-1",
                    AgentKnowledgeService.META_DOCUMENT_ID, 42L,
                    AgentKnowledgeService.META_CHUNK_ID, 4203L,
                    AgentKnowledgeService.META_CHUNK_INDEX, 3,
                    AgentKnowledgeService.META_FILE_NAME, "fixture.txt"
                ))
                .score(0.91)
                .build()
        ));
        AgentKnowledgeService service = service(vectorStore, 0.25);

        List<AgentKnowledgeHit> hits = service.search("agent-1", "银杏计划发布窗口", 3);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).content()).contains("gingko-rollback-7429");
        assertThat(hits.get(0).score()).isEqualTo(0.91);
        assertThat(vectorStore.requests).hasSize(1);
        SearchRequest request = vectorStore.requests.get(0);
        assertThat(request.getQuery()).isEqualTo("银杏计划发布窗口");
        assertThat(request.getTopK()).isEqualTo(9);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.25);
        assertThat(request.getFilterExpression().toString()).contains("agent-1");
    }

    @Test
    void springAiMilvusWindowExpressionExpandsCenterChunkByFiveNeighbors() {
        String expr = KnowledgeWindowQuery.windowFilterExpr("agent-1", 42L, 7);

        assertThat(expr).isEqualTo(
            "metadata[\"agentId\"] == \"agent-1\""
                + " && metadata[\"documentId\"] == 42"
                + " && metadata[\"chunkIndex\"] >= 2"
                + " && metadata[\"chunkIndex\"] <= 12"
        );
    }

    @Test
    void springAiMilvusWindowExpressionDoesNotStartBelowZero() {
        String expr = KnowledgeWindowQuery.windowFilterExpr("agent-1", 42L, 3);

        assertThat(expr).contains("metadata[\"chunkIndex\"] >= 0");
        assertThat(expr).contains("metadata[\"chunkIndex\"] <= 8");
    }

    @Test
    void vectorDocumentIdIsStableAndScopedByAgentDocumentAndChunk() {
        assertThat(AgentKnowledgeService.vectorDocumentId("agent-1", 42L, 7))
            .isEqualTo(AgentKnowledgeService.vectorDocumentId("agent-1", 42L, 7));
        assertThat(AgentKnowledgeService.vectorDocumentId("agent-1", 42L, 7))
            .isNotEqualTo(AgentKnowledgeService.vectorDocumentId("agent-2", 42L, 7));
    }

    @Test
    void documentViewKeepsObjectUriInternalAndExposesAvailability() {
        AgentKnowledgeDocumentEntity entity = new AgentKnowledgeDocumentEntity();
        entity.setId(1L);
        entity.setAgentId("agent-1");
        entity.setFileName("guide.txt");
        entity.setFileType("txt");
        entity.setFileSize(12L);
        entity.setChunkCount(1);
        entity.setObjectUri("oss://bucket/knowledge/agent-1/guide.txt");
        entity.setContentType("text/plain");

        AgentKnowledgeDocument view = AgentKnowledgeDocument.from(entity);

        assertThat(view.objectUri()).isEqualTo("oss://bucket/knowledge/agent-1/guide.txt");
        assertThat(view.contentType()).isEqualTo("text/plain");
        assertThat(view.hasOriginalFile()).isTrue();
    }

    private AgentKnowledgeService service(VectorStore vectorStore,
                                          double minSimilarity) {
        return new AgentKnowledgeService(
            mock(AgentKnowledgeDocumentMapper.class),
            vectorStore,
            true,
            500,
            0.15,
            minSimilarity,
            false,
            "eng",
            200,
            80,
            20,
            "tesseract",
            "echomind_agent_knowledge_test"
        );
    }

    private static class RecordingVectorStore implements VectorStore {

        private final List<Document> results;
        private final List<SearchRequest> requests = new ArrayList<>();

        private RecordingVectorStore(List<Document> results) {
            this.results = results;
        }

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
            return results;
        }
    }
}
