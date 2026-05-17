package com.echomind.console.service;

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
}
