package com.echomind.usermemory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "echomind.user-memory")
public class UserMemoryProperties {

    private boolean enabled = true;
    private String queueName = "echomind.user-memory.requests";
    private String profileKeyPrefix = "echomind:user-profile:snapshot:";
    private int existingProfileLimit = 30;
    private int maxExtractedEntries = 10;
    private double minConfidence = 0.3;
    private double mergeMinSimilarity = 0.65;
    private int relatedFactTopK = 12;
    private int profileMaxChars = 2000;
    private String extractorModelId = "deepseek:deepseek-v4-flash";
    private String milvusHost = "localhost";
    private int milvusPort = 19530;
    private String milvusUserMemoryCollection = "echomind_user_memory_v2";
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
