package com.echomind.console.service;

import com.echomind.memory.knowledge.AgentKnowledgeDocument;
import com.echomind.memory.knowledge.AgentKnowledgeManagementPort;
import com.echomind.skill.storage.ObjectStorageService;
import com.echomind.skill.storage.StoredObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentKnowledgeApplicationServiceTest {

    @Test
    void uploadDelegatesFileMetadataAndBytesToMemoryModule() throws Exception {
        AgentKnowledgeManagementPort knowledgeService = mock(AgentKnowledgeManagementPort.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        AgentKnowledgeApplicationService service = new AgentKnowledgeApplicationService(knowledgeService, storageService);
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "guide.txt",
            "text/plain",
            "hello knowledge".getBytes(StandardCharsets.UTF_8)
        );
        AgentKnowledgeDocument document = new AgentKnowledgeDocument(
            1L, "agent-a", "guide.txt", "txt", 15L, 1,
            "oss://bucket/knowledge/agent-a/guide.txt", "text/plain", true, Instant.now()
        );
        when(storageService.putObject(anyString(), any(Path.class), eq("text/plain")))
            .thenAnswer(invocation -> new StoredObject(
                "oss://bucket/" + invocation.getArgument(0, String.class),
                "bucket",
                invocation.getArgument(0, String.class),
                "https://cdn.example.com/file",
                15L,
                "text/plain"
            ));
        when(knowledgeService.upload(eq("agent-a"), eq("guide.txt"), eq(15L), any(byte[].class),
            anyString(), eq("text/plain")))
            .thenReturn(document);

        AgentKnowledgeDocument result = service.upload("agent-a", file);

        assertThat(result).isSameAs(document);
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageService).putObject(keyCaptor.capture(), any(Path.class), eq("text/plain"));
        assertThat(keyCaptor.getValue()).startsWith("knowledge/agent-a/");
        verify(knowledgeService).upload(eq("agent-a"), eq("guide.txt"), eq(15L), bytesCaptor.capture(),
            eq("oss://bucket/" + keyCaptor.getValue()), eq("text/plain"));
        assertThat(bytesCaptor.getValue()).containsExactly("hello knowledge".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void uploadRejectsEmptyFileBeforeCallingMemoryModule() {
        AgentKnowledgeManagementPort knowledgeService = mock(AgentKnowledgeManagementPort.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        AgentKnowledgeApplicationService service = new AgentKnowledgeApplicationService(knowledgeService, storageService);
        MockMultipartFile empty = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> service.upload("agent-a", empty))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("知识库文件不能为空");

        verifyNoInteractions(knowledgeService);
        verifyNoInteractions(storageService);
    }

    @Test
    void uploadDeletesStoredObjectWhenIndexingFails() throws Exception {
        AgentKnowledgeManagementPort knowledgeService = mock(AgentKnowledgeManagementPort.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        AgentKnowledgeApplicationService service = new AgentKnowledgeApplicationService(knowledgeService, storageService);
        MockMultipartFile file = new MockMultipartFile(
            "file", "guide.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        when(storageService.putObject(anyString(), any(Path.class), eq("text/plain")))
            .thenReturn(new StoredObject("oss://bucket/knowledge/agent-a/guide.txt", "bucket",
                "knowledge/agent-a/guide.txt", "https://cdn.example.com/file", 5L, "text/plain"));
        when(knowledgeService.upload(eq("agent-a"), eq("guide.txt"), eq(5L), any(byte[].class),
            eq("oss://bucket/knowledge/agent-a/guide.txt"), eq("text/plain")))
            .thenThrow(new IllegalStateException("milvus down"));
        when(storageService.supports("oss://bucket/knowledge/agent-a/guide.txt")).thenReturn(true);

        assertThatThrownBy(() -> service.upload("agent-a", file))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("milvus down");

        verify(storageService).deleteObject("oss://bucket/knowledge/agent-a/guide.txt");
    }

    @Test
    void deleteDocumentDeletesOriginalObjectWhenPresent() throws Exception {
        AgentKnowledgeManagementPort knowledgeService = mock(AgentKnowledgeManagementPort.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        AgentKnowledgeApplicationService service = new AgentKnowledgeApplicationService(knowledgeService, storageService);
        AgentKnowledgeDocument document = document("oss://bucket/knowledge/a.txt", true);
        when(knowledgeService.listDocuments("agent-a")).thenReturn(List.of(document));
        when(storageService.supports("oss://bucket/knowledge/a.txt")).thenReturn(true);

        service.deleteDocument("agent-a", 1L);

        verify(knowledgeService).deleteDocument("agent-a", 1L);
        verify(storageService).deleteObject("oss://bucket/knowledge/a.txt");
    }

    @Test
    void deleteDocumentSkipsObjectDeleteForLegacyDocument() throws Exception {
        AgentKnowledgeManagementPort knowledgeService = mock(AgentKnowledgeManagementPort.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        AgentKnowledgeApplicationService service = new AgentKnowledgeApplicationService(knowledgeService, storageService);
        when(knowledgeService.listDocuments("agent-a")).thenReturn(List.of(document(null, false)));

        service.deleteDocument("agent-a", 1L);

        verify(knowledgeService).deleteDocument("agent-a", 1L);
        verify(storageService, never()).deleteObject(anyString());
    }

    @Test
    void downloadReadsOriginalObject() throws Exception {
        AgentKnowledgeManagementPort knowledgeService = mock(AgentKnowledgeManagementPort.class);
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        AgentKnowledgeApplicationService service = new AgentKnowledgeApplicationService(knowledgeService, storageService);
        when(knowledgeService.listDocuments("agent-a")).thenReturn(List.of(document("oss://bucket/knowledge/a.txt", true)));
        when(storageService.supports("oss://bucket/knowledge/a.txt")).thenReturn(true);
        when(storageService.readObject("oss://bucket/knowledge/a.txt"))
            .thenReturn("hello".getBytes(StandardCharsets.UTF_8));

        AgentKnowledgeApplicationService.KnowledgeDownload download = service.download("agent-a", 1L);

        assertThat(download.fileName()).isEqualTo("guide.txt");
        assertThat(download.contentType()).isEqualTo("text/plain");
        assertThat(download.bytes()).containsExactly("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void downloadRejectsLegacyDocumentWithoutOriginalObject() {
        AgentKnowledgeManagementPort knowledgeService = mock(AgentKnowledgeManagementPort.class);
        AgentKnowledgeApplicationService service = new AgentKnowledgeApplicationService(
            knowledgeService, mock(ObjectStorageService.class));
        when(knowledgeService.listDocuments("agent-a")).thenReturn(List.of(document(null, false)));

        assertThatThrownBy(() -> service.download("agent-a", 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("没有可下载的原文件");
    }

    private AgentKnowledgeDocument document(String objectUri, boolean hasOriginalFile) {
        return new AgentKnowledgeDocument(
            1L, "agent-a", "guide.txt", "txt", 15L, 1,
            objectUri, "text/plain", hasOriginalFile, Instant.now()
        );
    }
}
