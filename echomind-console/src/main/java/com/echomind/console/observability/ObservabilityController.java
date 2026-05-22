package com.echomind.console.observability;

import com.echomind.console.observability.JaegerTraceClient.TraceBackendException;
import com.echomind.console.observability.JaegerTraceClient.TraceNotFoundException;
import com.echomind.console.observability.TraceDtos.TraceConfigResponse;
import com.echomind.console.observability.TraceDtos.TraceDetailResponse;
import com.echomind.console.observability.TraceDtos.TraceListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/observability")
@RequiredArgsConstructor
public class ObservabilityController {

    private final JaegerTraceClient traceClient;

    @GetMapping("/traces/config")
    public TraceConfigResponse config() {
        return traceClient.config();
    }

    @GetMapping("/traces")
    public TraceListResponse search(
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) String lookback,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) String userId
    ) {
        return traceClient.search(limit, lookback, scope, userId);
    }

    @GetMapping("/traces/{traceId}")
    public TraceDetailResponse getTrace(@PathVariable String traceId) {
        return traceClient.getTrace(traceId);
    }

    @ExceptionHandler(TraceNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(TraceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(TraceBackendException.class)
    public ResponseEntity<Map<String, String>> badGateway(TraceBackendException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
