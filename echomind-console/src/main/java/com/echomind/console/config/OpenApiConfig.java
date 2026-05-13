package com.echomind.console.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI echoMindOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("EchoMind Agent Platform API")
                .version("1.0.0")
                .description("AI Agent platform with MCP & Skill marketplace"));
    }
}
