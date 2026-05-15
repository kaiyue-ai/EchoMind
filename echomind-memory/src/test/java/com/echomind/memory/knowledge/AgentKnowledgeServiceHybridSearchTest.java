package com.echomind.memory.knowledge;

import com.echomind.memory.embedding.EmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentKnowledgeServiceHybridSearchTest {

    @Test
    void doesNotFallBackToMysqlVectorSearchWhenRedisIsUnavailable() {
        AgentKnowledgeChunkRepository chunkRepository = mock(AgentKnowledgeChunkRepository.class);
        when(chunkRepository.findByAgentId("agent-1")).thenReturn(List.of(
            chunk(1L, "agent-1", "unrelated.pdf", "天气预报和城市温度", new double[] {0, 1}),
            chunk(2L, "agent-1", "java.pdf", "System.exit 调用会直接终止 JVM 进程", new double[] {1, 0})
        ));
        when(chunkRepository.searchKeywordCandidates(eq("agent-1"), any(), any(Pageable.class)))
            .thenReturn(List.of());

        AgentKnowledgeService service = service(chunkRepository, text -> Optional.of(new double[] {1, 0}), 0.6);

        List<AgentKnowledgeHit> hits = service.search("agent-1", "进程退出", 3);

        assertThat(hits).isEmpty();
    }

    @Test
    void keywordHitCanRescueExactTechnicalTerms() {
        AgentKnowledgeChunkRepository chunkRepository = mock(AgentKnowledgeChunkRepository.class);
        AgentKnowledgeChunkEntity keywordChunk = chunk(
            7L, "agent-1", "java安全规范.txt", "禁止在 Skill 代码中调用 System.exit(0)", new double[] {0, 1});
        when(chunkRepository.findByAgentId("agent-1")).thenReturn(List.of());
        when(chunkRepository.searchKeywordCandidates(eq("agent-1"), any(), any(Pageable.class)))
            .thenReturn(List.of(keywordChunk));

        AgentKnowledgeService service = service(chunkRepository, text -> Optional.of(new double[] {1, 0}), 0.8);

        List<AgentKnowledgeHit> hits = service.search("agent-1", "System.exit(0)", 3);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).chunkId()).isEqualTo(7L);
        assertThat(hits.get(0).content()).contains("System.exit");
    }

    @Test
    void redisDistanceIsConvertedToSimilarity() throws Exception {
        AgentKnowledgeService service = service(mock(AgentKnowledgeChunkRepository.class),
            text -> Optional.of(new double[] {1, 0}), 0.25);
        Method method = AgentKnowledgeService.class.getDeclaredMethod("distanceToSimilarity", double.class);
        method.setAccessible(true);

        assertThat((double) method.invoke(service, 0.2)).isEqualTo(0.8);
        assertThat((double) method.invoke(service, 1.3)).isZero();
    }

    private AgentKnowledgeService service(AgentKnowledgeChunkRepository chunkRepository,
                                          EmbeddingClient embeddingClient,
                                          double minSimilarity) {
        return new AgentKnowledgeService(
            mock(AgentKnowledgeDocumentRepository.class),
            chunkRepository,
            embeddingClient,
            new ObjectMapper(),
            null,
            true,
            "idx:test",
            "test:knowledge:",
            1000,
            150,
            minSimilarity,
            0.75,
            0.25,
            20,
            false,
            "eng",
            200,
            80,
            20,
            "tesseract"
        );
    }

    private AgentKnowledgeChunkEntity chunk(Long id,
                                            String agentId,
                                            String fileName,
                                            String content,
                                            double[] embedding) {
        AgentKnowledgeChunkEntity entity = new AgentKnowledgeChunkEntity();
        entity.setId(id);
        entity.setAgentId(agentId);
        entity.setDocumentId(10L);
        entity.setFileName(fileName);
        entity.setChunkIndex(id.intValue());
        entity.setContent(content);
        try {
            entity.setEmbeddingJson(new ObjectMapper().writeValueAsString(embedding));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return entity;
    }
}
