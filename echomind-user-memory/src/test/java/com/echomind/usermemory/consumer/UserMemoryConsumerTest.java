package com.echomind.usermemory.consumer;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.UserMemoryEvent;
import com.echomind.usermemory.config.UserMemoryProperties;
import com.echomind.usermemory.service.UserMemoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class UserMemoryConsumerTest {

    @Test
    void rethrowsProcessingFailureSoRabbitRetryCanHandleIt() {
        UserMemoryService service = mock(UserMemoryService.class);
        UserMemoryProperties properties = new UserMemoryProperties();
        UserMemoryEvent event = new UserMemoryEvent("user-a", "session-a", "agent-a",
            List.of(AgentMessage.user("记住我喜欢简洁代码")));
        RuntimeException failure = new RuntimeException("milvus unavailable");
        doThrow(failure).when(service).ingest(event);
        UserMemoryConsumer consumer = new UserMemoryConsumer(service, properties);

        assertThatThrownBy(() -> consumer.onEvent(event)).isSameAs(failure);
    }

    @Test
    void invalidEventReturnsWithoutCallingService() {
        UserMemoryService service = mock(UserMemoryService.class);
        UserMemoryProperties properties = new UserMemoryProperties();
        UserMemoryConsumer consumer = new UserMemoryConsumer(service, properties);

        consumer.onEvent(new UserMemoryEvent("user-a", " ", "agent-a", List.of(AgentMessage.user("x"))));

        verify(service, never()).ingest(org.mockito.ArgumentMatchers.any());
    }
}
