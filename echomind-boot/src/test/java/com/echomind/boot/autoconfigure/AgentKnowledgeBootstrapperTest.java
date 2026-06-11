package com.echomind.boot.autoconfigure;

import com.echomind.boot.properties.EchoMindProperties;
import com.echomind.memory.knowledge.AgentKnowledgeDocument;
import com.echomind.memory.knowledge.AgentKnowledgeManagementPort;
import com.echomind.skill.storage.ObjectStorageService;
import com.echomind.skill.storage.StoredObject;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentKnowledgeBootstrapperTest {

    @Test
    void uploadsConfiguredContentSeedWhenDocumentIsMissing() throws Exception {
        EchoMindProperties.AgentBootstrap config = new EchoMindProperties.AgentBootstrap();
        EchoMindProperties.KnowledgeSeed seed = new EchoMindProperties.KnowledgeSeed();
        seed.setAgentId("zhangxuefeng");
        seed.setFileName("zhangxuefeng-seed.txt");
        seed.setContent("张雪峰 Agent 的知识库 seed");
        config.setKnowledgeSeeds(List.of(seed));

        AgentKnowledgeManagementPort knowledgeService = mock(AgentKnowledgeManagementPort.class);
        when(knowledgeService.listDocuments("zhangxuefeng")).thenReturn(List.of());
        ObjectStorageService storageService = mock(ObjectStorageService.class);
        when(storageService.putObject(any(), any(Path.class), eq("text/plain")))
            .thenReturn(new StoredObject("local://knowledge-seeds/zhangxuefeng/zhangxuefeng-seed.txt",
                "local", "knowledge-seeds/zhangxuefeng/zhangxuefeng-seed.txt", "/objects/seed", 16, "text/plain"));

        int seeded = new AgentKnowledgeBootstrapper(
            config, knowledgeService, storageService, new DefaultResourceLoader()).seedAll();

        assertThat(seeded).isEqualTo(1);
        verify(knowledgeService).upload(eq("zhangxuefeng"), eq("zhangxuefeng-seed.txt"),
            eq((long) "张雪峰 Agent 的知识库 seed".getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
            any(byte[].class), eq("local://knowledge-seeds/zhangxuefeng/zhangxuefeng-seed.txt"), eq("text/plain"));
    }

    @Test
    void skipsSeedWhenSameFileNameAlreadyExists() throws Exception {
        EchoMindProperties.AgentBootstrap config = new EchoMindProperties.AgentBootstrap();
        EchoMindProperties.KnowledgeSeed seed = new EchoMindProperties.KnowledgeSeed();
        seed.setAgentId("jvm-master");
        seed.setFileName("jvm-seed.txt");
        seed.setContent("JVM seed");
        config.setKnowledgeSeeds(List.of(seed));

        AgentKnowledgeManagementPort knowledgeService = mock(AgentKnowledgeManagementPort.class);
        when(knowledgeService.listDocuments("jvm-master")).thenReturn(List.of(document("jvm-master", "jvm-seed.txt")));
        ObjectStorageService storageService = mock(ObjectStorageService.class);

        int seeded = new AgentKnowledgeBootstrapper(
            config, knowledgeService, storageService, new DefaultResourceLoader()).seedAll();

        assertThat(seeded).isZero();
        verify(storageService, never()).putObject(any(), any(Path.class), any());
        verify(knowledgeService, never()).upload(any(), any(), anyLong(), any(), any(), any());
    }

    private AgentKnowledgeDocument document(String agentId, String fileName) {
        return new AgentKnowledgeDocument(1L, agentId, fileName, "txt", 10, 1,
            "local://seed", "text/plain", true, Instant.now());
    }
}
