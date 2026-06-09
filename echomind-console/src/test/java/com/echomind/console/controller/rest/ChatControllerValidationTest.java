package com.echomind.console.controller.rest;

import com.echomind.console.controller.GlobalExceptionHandler;
import com.echomind.console.service.ChatApplicationService;
import com.echomind.console.service.MemoryApplicationService;
import com.echomind.console.service.SsePushService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsOversizedMessageBeforeSubmittingChat() throws Exception {
        ChatApplicationService chatService = mock(ChatApplicationService.class);
        MockMvc mvc = mockMvc(chatService);

        var result = mvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "agentId", "default",
                    "message", "A".repeat(20_001),
                    "sessionId", "session-a"
                ))))
            .andExpect(status().isBadRequest())
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("20000");
        verifyNoInteractions(chatService);
    }

    private MockMvc mockMvc(ChatApplicationService chatService) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders
            .standaloneSetup(new ChatController(
                chatService,
                mock(MemoryApplicationService.class),
                mock(SsePushService.class)
            ))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
    }
}
