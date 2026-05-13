package com.echomind.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * EchoMind 平台应用入口 —— Spring Boot 启动类。
 *
 * <p>本类是 EchoMind AI Agent 平台的唯一启动入口，通过以下注解进行全局配置：
 * <ul>
 *   <li>{@link SpringBootApplication}{@code (scanBasePackages = "com.echomind")}：
 *       启用自动配置并扫描 {@code com.echomind} 包下的所有 Spring 组件</li>
 *   <li>{@link EnableJpaRepositories}{@code (basePackages = "com.echomind")}：
 *       启用 Spring Data JPA 仓库扫描，覆盖 Skill、Agent 等需要持久化的模块</li>
 *   <li>{@link EntityScan}{@code (basePackages = "com.echomind")}：
 *       扫描所有 EchoMind 模块中的 JPA 实体类</li>
 *   <li>{@link EnableAsync}：启用 Spring 异步执行能力，支持
 *       {@code @Async} 注解的异步方法（如技能并发调用）</li>
 *   <li>{@link EnableScheduling}：启用 Spring 定时任务调度，
 *       支持 {@code @Scheduled} 注解（如技能目录轮询、记忆自动摘要）</li>
 * </ul>
 *
 * <p>启动方式（开发环境）：
 * <pre>{@code
 * mvn -f echomind-app/pom.xml spring-boot:run
 * }</pre>
 *
 * <p>必要环境变量：
 * <ul>
 *   <li>{@code DEEPSEEK_API_KEY} —— DeepSeek API 密钥</li>
 *   <li>{@code DEEPSEEK_BASE_URL} —— DeepSeek 兼容 API 基础 URL</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = "com.echomind")
@EnableJpaRepositories(basePackages = "com.echomind")
@EntityScan(basePackages = "com.echomind")
@EnableAsync
@EnableScheduling
public class EchoMindApplication {

    /**
     * 应用主入口 —— 启动 Spring Boot 应用程序。
     *
     * @param args 命令行参数，传递给 Spring Boot 启动器
     */
    public static void main(String[] args) {
        SpringApplication.run(EchoMindApplication.class, args);
    }
}
