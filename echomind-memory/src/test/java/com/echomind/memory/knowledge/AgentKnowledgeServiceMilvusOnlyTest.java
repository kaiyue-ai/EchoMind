package com.echomind.memory.knowledge;

import com.echomind.memory.embedding.EmbeddingClient;
import com.echomind.memory.milvus.MilvusVectorStoreSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AgentKnowledgeServiceMilvusOnlyTest {

    @Test
    void searchDoesNotFallbackToMysqlWhenMilvusIsUnavailable() {
        AgentKnowledgeDocumentRepository documentRepository = mock(AgentKnowledgeDocumentRepository.class);
        AgentKnowledgeService service = service(documentRepository, text -> Optional.of(new double[] {1, 0}));

        List<AgentKnowledgeHit> hits = service.search("agent-1", "System.exit(0)", 3);

        assertThat(hits).isEmpty();
        verifyNoInteractions(documentRepository);
    }

    @Test
    void milvusCosineScoreIsUsedAsSimilarity() {
        assertThat(MilvusVectorStoreSupport.cosineScoreToSimilarity(0.8f)).isEqualTo(0.8);
        assertThat(MilvusVectorStoreSupport.cosineScoreToSimilarity(-0.3f)).isZero();
        assertThat(MilvusVectorStoreSupport.cosineScoreToSimilarity(1.3f)).isEqualTo(1.0);
    }

    private AgentKnowledgeService service(AgentKnowledgeDocumentRepository documentRepository,
                                          EmbeddingClient embeddingClient) {
        return new AgentKnowledgeService(
            documentRepository,
            embeddingClient,
            null,
            true,
            "test_agent_knowledge",
            500,
            0.15,
            0.6,
            false,
            "eng",
            200,
            80,
            20,
            "tesseract"
        );
    }
}
