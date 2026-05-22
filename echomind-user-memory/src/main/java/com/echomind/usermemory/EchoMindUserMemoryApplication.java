package com.echomind.usermemory;

import com.echomind.usermemory.config.UserMemoryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(UserMemoryProperties.class)
@EnableScheduling
public class EchoMindUserMemoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(EchoMindUserMemoryApplication.class, args);
    }
}
