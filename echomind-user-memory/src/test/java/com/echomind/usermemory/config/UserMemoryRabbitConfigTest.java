package com.echomind.usermemory.config;

import com.echomind.common.messaging.RabbitQueueNames;
import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.UserMemoryEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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

    @Test
    void userMemoryQueueIsDurableAndDeadLettered() {
        UserMemoryProperties properties = new UserMemoryProperties();
        properties.setQueueName("user.memory.queue");
        Queue queue = new UserMemoryRabbitConfig().userMemoryQueue(properties);

        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
            .containsEntry("x-dead-letter-exchange", RabbitQueueNames.DEAD_LETTER_EXCHANGE)
            .containsEntry("x-dead-letter-routing-key", RabbitQueueNames.USER_MEMORY_DLQ);
    }

    @Test
    void rabbitTemplateUsesMandatoryPublishing() {
        RabbitTemplate template = new UserMemoryRabbitConfig().userMemoryRabbitTemplate(
            mock(ConnectionFactory.class),
            mock(Jackson2JsonMessageConverter.class)
        );

        assertThat(ReflectionTestUtils.getField(template, "mandatoryExpression")).isNotNull();
    }
}
