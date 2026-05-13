package com.echomind.memory.embedding;

import com.echomind.common.model.AgentMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * 记忆向量索引与检索服务。
 *
 * <p>保存消息时为可检索文本建立向量；聊天前根据当前用户问题召回相关历史。
 * 向量调用失败只会让相关历史为空，不影响正常聊天和正式历史入库。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class MemoryEmbeddingService {

    private static final int INDEX_TEXT_MAX_CHARS = 2000;
    private static final int PREVIEW_MAX_CHARS = 500;

    private final EmbeddingClient embeddingClient;
    private final MemoryVectorStore vectorStore;
    private final boolean enabled;

    /** 给一条消息建立向量索引。 */
    public void indexMessage(String sessionId, Long messageId, AgentMessage message) {
        if (!enabled || messageId == null || !isIndexable(message)) {
            return;
        }
        String content = truncate(message.content(), INDEX_TEXT_MAX_CHARS);
        Optional<double[]> embedding = embeddingClient.embed(content);
        if (embedding.isEmpty()) {
            return;
        }
        try {
            vectorStore.save(new MemoryVectorRecord(
                sessionId,
                messageId,
                message.role(),
                truncate(message.content(), PREVIEW_MAX_CHARS),
                embedding.get()
            ));
        } catch (Exception e) {
            log.warn("Failed to index memory vector session={} messageId={}: {}",
                sessionId, messageId, e.getMessage());
        }
    }

    /** 按当前问题召回相关历史片段。 */
    public List<MemorySearchHit> search(String sessionId, String query, int topK) {
        if (!enabled || topK <= 0 || query == null || query.isBlank()) {
            return List.of();
        }
        Optional<double[]> queryVector = embeddingClient.embed(truncate(query, INDEX_TEXT_MAX_CHARS));
        if (queryVector.isEmpty()) {
            return List.of();
        }
        return vectorStore.search(sessionId, queryVector.get(), topK);
    }

    /** 清除指定会话的向量索引。 */
    public void deleteSession(String sessionId) {
        vectorStore.deleteSession(sessionId);
    }

    private boolean isIndexable(AgentMessage message) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            return false;
        }
        return "user".equals(message.role()) || "assistant".equals(message.role());
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }
}
