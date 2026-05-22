package com.echomind.usermemory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "echomind.user-memory")
public class UserMemoryProperties {

    private boolean enabled = true;
    private String queueName = "echomind.user-memory.requests";
    private String flushQueueName = "echomind.user-memory.flush.requests";
    private String vectorIndexName = "idx:user:memory:vectors";
    private String vectorKeyPrefix = "user:memory:vector:";
    private String profileKeyPrefix = "echomind:user-profile:snapshot:";
    private String bufferKeyPrefix = "echomind:user-memory:buffer:";
    private String bufferMetaKeyPrefix = "echomind:user-memory:buffer-meta:";
    private String bufferLockKeyPrefix = "echomind:user-memory:buffer-lock:";
    private int existingProfileLimit = 30;
    private int maxExtractedEntries = 10;
    private int batchSize = 5;
    private int bufferMaxChars = 8000;
    private int bufferIdleFlushSeconds = 1800;
    private int scanIdleBuffersSeconds = 60;
    private double importantSignalConfidenceThreshold = 0.7;
    private int relatedFactTopK = 12;
    private int profileMaxChars = 2000;
    private int bufferLockTtlSeconds = 60;
    private int bufferTtlDays = 7;
    private String extractorModelId;
    private String embeddingBaseUrl = "https://dashscope.aliyuncs.com";
    private String embeddingApiKey;
    private String embeddingModel = "tongyi-embedding-vision-plus";
    private String deepSeekApiKey;
    private String deepSeekBaseUrl;
    private String deepSeekModel = "deepseek-chat";
    private String openAiCompatibleProvider = "aliyun-bailian";
    private String openAiCompatibleApiKey;
    private String openAiCompatibleBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String openAiCompatibleModel = "qwen-plus";
}
