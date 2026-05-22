package com.echomind.console.service;

import com.echomind.memory.knowledge.AgentKnowledgeDocument;
import com.echomind.memory.knowledge.AgentKnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Agent 知识库应用服务。
 *
 * <p>REST 层只处理 HTTP 上传和路径参数，知识库文件校验、上传参数整理和
 * Memory 模块调用都收口在这里。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentKnowledgeApplicationService {

    private final AgentKnowledgeService knowledgeService;

    public List<AgentKnowledgeDocument> listDocuments(String agentId) {
        return knowledgeService.listDocuments(agentId);
    }

    public AgentKnowledgeDocument upload(String agentId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("知识库文件不能为空");
        }
        return knowledgeService.upload(
            agentId,
            file.getOriginalFilename(),
            file.getSize(),
            file.getBytes()
        );
    }

    public void deleteDocument(String agentId, Long documentId) {
        knowledgeService.deleteDocument(agentId, documentId);
    }
}
