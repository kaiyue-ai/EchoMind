package com.echomind.app;

import com.echomind.common.observability.EchoMindTrace;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EchoMindTracingConfig {

    @Bean
    CommandLineRunner echoMindTraceInitializer(OpenTelemetry openTelemetry) {
        return args -> EchoMindTrace.setOpenTelemetry(openTelemetry);
    }
}
