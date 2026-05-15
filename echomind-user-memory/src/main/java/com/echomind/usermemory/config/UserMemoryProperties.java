package com.echomind.usermemory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "echomind.user-memory")
public class UserMemoryProperties {

    private boolean enabled = true;
    private String queueName = "echomind.user-memory.requests";
    private String vectorIndexName = "idx:user:memory:vectors";
    private String vectorKeyPrefix = "user:memory:vector:";
    private int existingProfileLimit = 30;
    private int maxExtractedEntries = 10;
    private String extractorModelId;
    private String embeddingBaseUrl = "https://dashscope.aliyuncs.com";
    private String embeddingApiKey;
    private String embeddingModel = "tongyi-embedding-vision-plus";
    private String deepSeekApiKey;
    private String deepSeekBaseUrl;
    private String deepSeekModel = "deepseek-v4-flash";
    private String openAiCompatibleProvider = "aliyun-bailian";
    private String openAiCompatibleApiKey;
    private String openAiCompatibleBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String openAiCompatibleModel = "qwen-plus";
}
