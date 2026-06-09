package com.echomind.console.controller.rest;

import com.echomind.console.deadletter.RabbitDeadLetterDtos.DeadLetterListResponse;
import com.echomind.console.deadletter.RabbitDeadLetterDtos.DeadLetterReplayResponse;
import com.echomind.console.deadletter.RabbitDeadLetterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/rabbitmq/dead-letters")
@RequiredArgsConstructor
public class AdminRabbitDeadLetterController {

    private final RabbitDeadLetterService deadLetterService;

    @GetMapping
    public DeadLetterListResponse list(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) Integer limit) {
        return deadLetterService.list(status, limit);
    }

    @PostMapping("/{id}/replay")
    public DeadLetterReplayResponse replay(@PathVariable long id) {
        return deadLetterService.replay(id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
