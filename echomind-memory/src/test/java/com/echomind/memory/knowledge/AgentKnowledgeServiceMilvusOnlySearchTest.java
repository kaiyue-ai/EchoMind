package com.echomind.memory.knowledge;

import com.echomind.memory.embedding.EmbeddingClient;
import com.echomind.memory.knowledge.entity.AgentKnowledgeDocumentEntity;
import com.echomind.memory.knowledge.mapper.AgentKnowledgeDocumentMapper;
import com.echomind.memory.milvus.MilvusVectorStoreSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentKnowledgeServiceMilvusOnlySearchTest {

    @Test
    void doesNotFallBackToMysqlSearchWhenMilvusIsUnavailable() {
        AgentKnowledgeService service = service(text -> Optional.of(new double[] {1, 0}), 0.6);

        List<AgentKnowledgeHit> hits = service.search("agent-1", "进程退出", 3);

        assertThat(hits).isEmpty();
    }

    @Test
    void milvusDistanceIsConvertedToSimilarity() {
        assertThat(MilvusVectorStoreSupport.distanceToSimilarity(0.2f)).isEqualTo(0.8);
        assertThat(MilvusVectorStoreSupport.distanceToSimilarity(1.3f)).isZero();
    }

    @Test
    void milvusCosineScoreIsAlreadySimilarity() {
        assertThat(MilvusVectorStoreSupport.cosineScoreToSimilarity(0.8f)).isEqualTo(0.8);
        assertThat(MilvusVectorStoreSupport.cosineScoreToSimilarity(-2f)).isEqualTo(-1);
        assertThat(MilvusVectorStoreSupport.cosineScoreToSimilarity(Float.NaN)).isZero();
    }

    @Test
    void milvusWindowExpressionExpandsCenterChunkByFiveNeighbors() {
        String expr = AgentKnowledgeService.windowFilterExpr("agent-1", 42L, 7);

        assertThat(expr).isEqualTo(
            "agent_id == " + AgentKnowledgeService.hashAgentId("agent-1")
                + " && document_id == 42"
                + " && chunk_index >= 2"
                + " && chunk_index <= 12"
        );
    }

    @Test
    void milvusWindowExpressionDoesNotStartBelowZero() {
        String expr = AgentKnowledgeService.windowFilterExpr("agent-1", 42L, 3);

        assertThat(expr).contains("chunk_index >= 0");
        assertThat(expr).contains("chunk_index <= 8");
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

    private AgentKnowledgeService service(EmbeddingClient embeddingClient,
                                          double minSimilarity) {
        return new AgentKnowledgeService(
            mock(AgentKnowledgeDocumentMapper.class),
            embeddingClient,
            null,
            true,
            "echomind_agent_knowledge_test",
            500,
            0.15,
            minSimilarity,
            false,
            "eng",
            200,
            80,
            20,
            "tesseract"
        );
    }
}
