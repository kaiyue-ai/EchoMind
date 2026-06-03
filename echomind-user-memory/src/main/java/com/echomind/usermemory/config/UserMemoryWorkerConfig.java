package com.echomind.usermemory.config;

import com.echomind.llm.provider.DeepSeekProvider;
import com.echomind.llm.provider.MockModelProvider;
import com.echomind.llm.provider.OpenAICompatibleProvider;
import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import com.echomind.memory.embedding.DashScopeEmbeddingClient;
import com.echomind.memory.embedding.DisabledEmbeddingClient;
import com.echomind.memory.embedding.EmbeddingClient;
import com.echomind.memory.usermemory.impl.RedisUserProfileSnapshotStore;
import com.echomind.memory.usermemory.UserProfileSnapshotStore;
import com.echomind.memory.milvus.MilvusClientFactory;
import com.echomind.memory.usermemory.impl.MilvusUserMemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.List;
import java.util.Set;

@Configuration
public class UserMemoryWorkerConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public EmbeddingClient userMemoryEmbeddingClient(UserMemoryProperties properties, ObjectMapper mapper) {
        String apiKey = firstNonBlank(
            properties.getEmbeddingApiKey(),
            System.getenv("ALIYUN_BAILIAN_API_KEY"),
            System.getenv("DASHSCOPE_API_KEY")
        );
        if (apiKey == null) {
            return new DisabledEmbeddingClient();
        }
        return new DashScopeEmbeddingClient(
            properties.getEmbeddingBaseUrl(),
            apiKey,
            properties.getEmbeddingModel(),
            mapper
        );
    }

    @Bean(destroyMethod = "close")
    public MilvusClientFactory milvusClientFactory(UserMemoryProperties properties) {
        return new MilvusClientFactory(properties.getMilvusHost(), properties.getMilvusPort());
    }

    @Bean
    public MilvusUserMemoryStore userMemoryVectorStore(UserMemoryProperties properties,
                                                       MilvusClientFactory milvusClientFactory) {
        return new MilvusUserMemoryStore(
            milvusClientFactory.getClient(),
            properties.getMilvusUserMemoryCollection()
        );
    }

    @Bean
    public UserProfileSnapshotStore userProfileSnapshotStore(UserMemoryProperties properties,
                                                            RedisConnectionFactory connectionFactory) {
        return new RedisUserProfileSnapshotStore(connectionFactory, properties.getProfileKeyPrefix());
    }

    @Bean
    public ModelProviderRegistry userMemoryModelProviderRegistry(UserMemoryProperties properties) {
        ModelProviderRegistry registry = new ModelProviderRegistry();
        boolean registeredRealProvider = false;

        String deepSeekKey = firstNonBlank(
            properties.getDeepSeekApiKey(),
            System.getenv("DEEPSEEK_API_KEY"),
            System.getenv("ANTHROPIC_API_KEY")
        );
        String deepSeekUrl = firstNonBlank(
            properties.getDeepSeekBaseUrl(),
            System.getenv("DEEPSEEK_BASE_URL"),
            System.getenv("ANTHROPIC_BASE_URL"),
            "https://api.deepseek.com"
        );
        if (deepSeekKey != null) {
            registry.registerProvider(
                new DeepSeekProvider(deepSeekUrl, deepSeekKey),
                List.of(new ModelSpec("deepseek", properties.getDeepSeekModel(),
                    Set.of(ModelCapability.TEXT), true))
            );
            registeredRealProvider = true;
        }

        String compatibleKey = firstNonBlank(
            properties.getOpenAiCompatibleApiKey(),
            System.getenv("ALIYUN_BAILIAN_API_KEY"),
            System.getenv("OPENAI_API_KEY")
        );
        if (compatibleKey != null) {
            String providerId = properties.getOpenAiCompatibleProvider();
            registry.registerProvider(
                new OpenAICompatibleProvider(
                    providerId,
                    properties.getOpenAiCompatibleBaseUrl(),
                    compatibleKey
                ),
                List.of(new ModelSpec(providerId, properties.getOpenAiCompatibleModel(),
                    Set.of(ModelCapability.TEXT), !registeredRealProvider))
            );
            registeredRealProvider = true;
        }

        registry.registerProvider(
            new MockModelProvider(),
            List.of(new ModelSpec("mock", "mock-model", Set.of(ModelCapability.TEXT), !registeredRealProvider))
        );
        return registry;
    }

    @Bean
    public DynamicModelRouter userMemoryDynamicModelRouter(ModelProviderRegistry registry) {
        return new DynamicModelRouter(registry);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
