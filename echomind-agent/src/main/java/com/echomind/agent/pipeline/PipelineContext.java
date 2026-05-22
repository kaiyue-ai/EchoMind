package com.echomind.agent.pipeline;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MemorySignal;
import com.echomind.common.model.MessageAttachment;
import com.echomind.common.model.TokenUsage;
import com.echomind.llm.router.ModelSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.Getter;
import lombok.Setter;

/**
 * 管线阶段之间传递的请求上下文。
 *
 * <p>调用方写入用户输入、会话、Agent 和模型偏好；各阶段逐步补充历史消息、工具结果
 * 和最终回复。{@code attributes} 用于少量扩展状态，避免为临时信息频繁改模型。</p>
 */
@Getter
@Setter
public class PipelineContext {

    private String sessionId;
    private String userId = "default";
    private String agentId;
    private String modelId;
    private ModelSpec resolvedModel; //已经解析的模型规格
    private String traceId;
    private String userMessage;
    private String systemPrompt;
    private boolean memoryPersistenceEnabled = true; // 要不要将这次聊天存入mysql
    private final List<AgentMessage> messages = new ArrayList<>();  // 拼给llm看的上下文
    /** 本轮用户上传的附件，只保存对象存储引用，不保存二进制内容。 */
    private final List<MessageAttachment> attachments = new ArrayList<>();
    /** 发给模型的附件，可能是签名 URL 或 data URL，不写入记忆。 */
    private final List<MessageAttachment> modelAttachments = new ArrayList<>();
    /** 兼容旧接口命名：记录本轮模型实际调用过的工具名称。 */
    private final List<String> skillResults = new ArrayList<>(); //工具调用的记录
    private String finalResponse; // 模型返回的文本结果
    private boolean failed; // 模型调用失败
    private String failureReason; // 模型调用失败的原因
    private TokenUsage tokenUsage; // 模型调用的 token 使用情况
    /** 主模型只产出是否需要优先沉淀记忆的信号，事实合并仍由后台 worker 完成。 */
    private MemorySignal memorySignal = MemorySignal.NONE;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>(); // 模型调用过程中产生的临时状态

    /** 判断本轮用户消息是否携带图片。 */
    public boolean hasImageAttachments() {
        return attachments.stream().anyMatch(MessageAttachment::isImage);
    }

    /** 返回模型调用使用的附件；未预处理时退回原始附件。 */
    public List<MessageAttachment> attachmentsForModel() {
        return modelAttachments.isEmpty() ? attachments : modelAttachments;
    }

    /** 标记管线失败，同时保留旧的文本响应格式用于前端兼容。 */
    public void markFailed(String reason) {
        failed = true;
        failureReason = normalizeFailureReason(reason);
        finalResponse = "[Error] " + failureReason;
    }

    /** 兼容旧调用点：文本以 [Error] 开头时也视为失败。 */
    public boolean hasFailed() {
        return failed || (finalResponse != null && finalResponse.startsWith("[Error]"));
    }

    public String effectiveFailureReason() {
        if (failureReason != null && !failureReason.isBlank()) {
            return failureReason;
        }
        if (finalResponse != null && finalResponse.startsWith("[Error]")) {
            return finalResponse.substring("[Error]".length()).trim();
        }
        return "模型调用失败";
    }

    /** 记忆按用户和会话隔离，避免不同用户碰巧使用同一个 sessionId 时共享上下文。 */
    public String getMemoryKey() {
        String owner = userId != null && !userId.isBlank() ? userId : "default";
        String conversation = sessionId != null && !sessionId.isBlank() ? sessionId : agentId;
        return owner + ":" + conversation;
    }

    /** 用户长期事实和画像按用户全局共享，不再按 session 或 Agent 切分。 */
    public String getUserMemoryKey() {
        String owner = userId != null && !userId.isBlank() ? userId : "default";
        return "user:" + owner;
    }

    /** 用户画像快照的用户 ID。 */
    public String getProfileUserId() {
        return userId != null && !userId.isBlank() ? userId : "default";
    }

    private String normalizeFailureReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "模型调用失败";
        }
        String trimmed = reason.trim();
        return trimmed.startsWith("[Error]") ? trimmed.substring("[Error]".length()).trim() : trimmed;
    }
}
