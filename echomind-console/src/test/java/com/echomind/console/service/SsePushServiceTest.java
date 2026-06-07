package com.echomind.console.service;

import com.echomind.common.model.ChatResponse;
import com.echomind.common.model.ChatStreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsePushServiceTest {

    @Test
    void createEmitterRequiresMatchingRequestOwner() {
        SsePushService service = new SsePushService();
        service.registerRequest("req-1", "user-a");

        assertThatCode(() -> service.createEmitter("req-1", "user-a"))
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> service.createEmitter("req-1", "user-b"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void missingRequestLooksNotFound() {
        SsePushService service = new SsePushService();

        assertThatThrownBy(() -> service.createEmitter("missing", "user-a"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void streamEventsCanArriveBeforeEmitterSubscribes() {
        SsePushService service = new SsePushService();
        service.registerRequest("req-1", "user-a");
        service.pushEvent(ChatStreamEvent.meta("req-1", "session-a", "trace-a"));
        service.pushEvent(ChatStreamEvent.token("req-1", "你"));
        service.pushEvent(ChatStreamEvent.toolStart("req-1", "calculator"));
        service.pushEvent(ChatStreamEvent.toolEnd("req-1", "calculator", 12));

        assertThatCode(() -> service.createEmitter("req-1", "user-a"))
            .doesNotThrowAnyException();
    }

    @Test
    void terminalResultKeepsOwnerBrieflyForLateSubscriber() {
        SsePushService service = new SsePushService();
        service.registerRequest("req-1", "user-a");
        service.pushEvent(ChatStreamEvent.result(ChatResponse.success(
            "req-1", "session-a", "default", "mock:model", "你好", java.util.List.of(), "trace-a"
        )));

        assertThatCode(() -> service.createEmitter("req-1", "user-a"))
            .doesNotThrowAnyException();
    }
}
