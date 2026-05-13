package com.echomind.memory.summary;

import com.echomind.common.model.AgentMessage;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 会话摘要服务。
 *
 * <p>这里先采用确定性压缩策略：把早期消息裁剪成可控长度的中文摘要块。
 * 它不额外调用大模型，避免聊天保存阶段再次触发 LLM、工具或流式链路。</p>
 */
@RequiredArgsConstructor
public class MemorySummaryService {

    private final int recentWindow;
    private final int maxChars;

    /**
     * 根据完整历史生成早期对话摘要。
     *
     * @param messages 完整历史，按时间升序
     * @return 可注入提示词的摘要；历史不足时返回空字符串
     */
    public String summarize(List<AgentMessage> messages) {
        if (messages == null || messages.size() <= recentWindow) {
            return "";
        }
        int olderEnd = Math.max(0, messages.size() - recentWindow);
        StringBuilder sb = new StringBuilder("以下是本会话较早内容的压缩摘要：\n");
        for (AgentMessage message : messages.subList(0, olderEnd)) {
            if (message.content() == null || message.content().isBlank()) {
                continue;
            }
            String line = "- " + roleLabel(message.role()) + "：" + normalize(message.content()) + "\n";
            if (sb.length() + line.length() > maxChars) {
                sb.append("- ……更早内容已省略。\n");
                break;
            }
            sb.append(line);
        }
        return sb.toString().trim();
    }

    private String roleLabel(String role) {
        return switch (role) {
            case "user" -> "用户";
            case "assistant" -> "助手";
            case "tool" -> "工具";
            case "system" -> "系统";
            default -> role == null ? "未知" : role;
        };
    }

    private String normalize(String value) {
        String text = value.replaceAll("\\s+", " ").trim();
        int perLineMax = Math.min(240, Math.max(80, maxChars / 10));
        if (text.length() <= perLineMax) {
            return text;
        }
        return text.substring(0, perLineMax - 3) + "...";
    }
}
