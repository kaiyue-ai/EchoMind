package com.echomind.usermemory;

import com.echomind.usermemory.config.UserMemoryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(UserMemoryProperties.class)
public class EchoMindUserMemoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(EchoMindUserMemoryApplication.class, args);
    }
}
