package com.echomind.usermemory.config;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.UserMemoryEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserMemoryRabbitConfigTest {

    @Test
    void messageConverterReadsAgentMessageInstantTimestamp() {
        ObjectMapper mapper = new UserMemoryWorkerConfig().objectMapper();
        Jackson2JsonMessageConverter converter = new UserMemoryRabbitConfig().userMemoryMessageConverter(mapper);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setHeader("__TypeId__", UserMemoryEvent.class.getName());
        String json = """
            {
              "sessionId": "session-1",
              "agentId": "default",
              "messages": [{
                "role": "user",
                "content": "我喜欢简洁代码",
                "timestamp": "2026-05-15T06:09:52.631Z"
              }]
            }
            """;

        Object converted = converter.fromMessage(new Message(json.getBytes(StandardCharsets.UTF_8), properties));

        assertThat(converted).isInstanceOf(UserMemoryEvent.class);
        UserMemoryEvent event = (UserMemoryEvent) converted;
        assertThat(event.sessionId()).isEqualTo("session-1");
        assertThat(event.messages()).containsExactly(
            new AgentMessage("user", "我喜欢简洁代码", Instant.parse("2026-05-15T06:09:52.631Z"), null, null)
        );
    }

    @Test
    void messageConverterWritesAndReadsUserMemoryEvent() {
        ObjectMapper mapper = new UserMemoryWorkerConfig().objectMapper();
        Jackson2JsonMessageConverter converter = new UserMemoryRabbitConfig().userMemoryMessageConverter(mapper);
        UserMemoryEvent event = new UserMemoryEvent(
            "session-1",
            "default",
            List.of(new AgentMessage("assistant", "好的", Instant.parse("2026-05-15T06:10:00Z"), null, null))
        );

        Message message = converter.toMessage(event, new MessageProperties());
        Object converted = converter.fromMessage(message);

        assertThat(converted).isEqualTo(event);
    }
}
