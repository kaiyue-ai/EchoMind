package com.echomind.console.service;

import com.echomind.memory.knowledge.AgentKnowledgeDocument;
import com.echomind.memory.knowledge.AgentKnowledgeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentKnowledgeApplicationServiceTest {

    @Test
    void uploadDelegatesFileMetadataAndBytesToMemoryModule() throws Exception {
        AgentKnowledgeService knowledgeService = mock(AgentKnowledgeService.class);
        AgentKnowledgeApplicationService service = new AgentKnowledgeApplicationService(knowledgeService);
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "guide.txt",
            "text/plain",
            "hello knowledge".getBytes(StandardCharsets.UTF_8)
        );
        AgentKnowledgeDocument document = new AgentKnowledgeDocument(
            1L, "agent-a", "guide.txt", "txt", 15L, 1, Instant.now()
        );
        when(knowledgeService.upload(eq("agent-a"), eq("guide.txt"), eq(15L), any(byte[].class)))
            .thenReturn(document);

        AgentKnowledgeDocument result = service.upload("agent-a", file);

        assertThat(result).isSameAs(document);
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(knowledgeService).upload(eq("agent-a"), eq("guide.txt"), eq(15L), bytesCaptor.capture());
        assertThat(bytesCaptor.getValue()).containsExactly("hello knowledge".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void uploadRejectsEmptyFileBeforeCallingMemoryModule() {
        AgentKnowledgeService knowledgeService = mock(AgentKnowledgeService.class);
        AgentKnowledgeApplicationService service = new AgentKnowledgeApplicationService(knowledgeService);
        MockMultipartFile empty = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> service.upload("agent-a", empty))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("知识库文件不能为空");

        verifyNoInteractions(knowledgeService);
    }
}
