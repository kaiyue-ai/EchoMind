package com.echomind.console.service;

import com.echomind.common.model.MessageAttachment;
import com.echomind.console.auth.AuthContext;
import com.echomind.console.auth.AuthUser;
import com.echomind.console.dto.ChatMessageRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRequestNormalizerTest {

    @Test
    void defaultsBlankAgentAndSessionAndUsesImagePromptForAttachmentOnlyMessage() {
        MessageAttachment image = MessageAttachment.image(
            "oss://bucket/image.png",
            "https://signed.example.com/image.png",
            "image/png",
            "image.png",
            1L
        );

        AuthContext.set(new AuthUser("user-a", "alice", true));
        try {
            NormalizedChatRequest normalized = new ChatRequestNormalizer()
                .normalize(new ChatMessageRequest(null, "", null, "mock:model", List.of(image)));

            assertThat(normalized.userId()).isEqualTo("user-a");
            assertThat(normalized.agentId()).isEqualTo("default");
            assertThat(normalized.sessionId()).isNotBlank();
            assertThat(normalized.message()).isEqualTo("请理解这张图片。");
            assertThat(normalized.attachments()).containsExactly(image);
        } finally {
            AuthContext.clear();
        }
    }

    @Test
    void rejectsBlankMessageWithoutAttachments() {
        AuthContext.set(new AuthUser("user-a", "alice", true));
        try {
            assertThatThrownBy(() -> new ChatRequestNormalizer()
                .normalize(new ChatMessageRequest("default", " ", "session-a", null, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message is required");
        } finally {
            AuthContext.clear();
        }
    }
}
