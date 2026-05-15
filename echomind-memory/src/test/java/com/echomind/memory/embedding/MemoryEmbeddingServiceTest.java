package com.echomind.memory.embedding;

import com.echomind.common.model.AgentMessage;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryEmbeddingServiceTest {

    @Test
    void indexesOnlyUserAndAssistantMessages() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        MemoryVectorStore vectorStore = mock(MemoryVectorStore.class);
        when(embeddingClient.embed(any())).thenReturn(Optional.of(new double[] {0.1, 0.2}));
        MemoryEmbeddingService service = new MemoryEmbeddingService(embeddingClient, vectorStore, true);

        service.indexMessage("session-1", 1L, AgentMessage.user("用户消息"));
        service.indexMessage("session-1", 2L, AgentMessage.tool("tool-1", "工具结果"));
        service.indexMessage("session-1", 3L, AgentMessage.assistant("助手回复"));

        verify(embeddingClient).embed("用户消息");
        verify(embeddingClient).embed("助手回复");
        verify(embeddingClient, never()).embed("工具结果");
        verify(vectorStore, times(2)).save(any(MemoryVectorRecord.class));
    }
}
