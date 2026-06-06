package com.echomind.usermemory.config;

import com.echomind.llm.provider.DeepSeekProvider;
import com.echomind.llm.provider.MockModelProvider;
import com.echomind.llm.provider.OpenAICompatibleProvider;
import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import com.echomind.memory.embedding.DisabledEmbeddingModel;
import com.echomind.memory.embedding.DisabledVectorStore;
import com.echomind.memory.usermemory.impl.RedisUserProfileSnapshotStore;
import com.echomind.memory.usermemory.UserProfileSnapshotStore;
import com.echomind.memory.usermemory.UserMemoryStore;
import com.echomind.memory.usermemory.impl.NoopUserMemoryStore;
import com.echomind.memory.usermemory.impl.SpringAiUserMemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.List;
import java.util.Set;

@Configuration
public class UserMemoryWorkerConfig {

    private static final String MILVUS_HNSW_INDEX_PARAMS = "{\"M\":16,\"efConstruction\":256}";

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean("userMemoryEmbeddingModel")
    public EmbeddingModel userMemoryEmbeddingModel(UserMemoryProperties properties) {
        String apiKey = embeddingApiKey(properties);
        if (!embeddingAvailable(properties)) {
            return new DisabledEmbeddingModel(properties.getEmbeddingDimension());
        }
        OpenAiApi openAiApi = OpenAiApi.builder()
            .baseUrl(properties.getEmbeddingBaseUrl())
            .apiKey(apiKey)
            .embeddingsPath("/v1/embeddings")
            .build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
            .model(properties.getEmbeddingModel())
            .dimensions(properties.getEmbeddingDimension())
            .build();
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, options);
    }

    @Bean(destroyMethod = "close")
    @Lazy
    public MilvusServiceClient milvusServiceClient(UserMemoryProperties properties) {
        return new MilvusServiceClient(ConnectParam.newBuilder()
            .withHost(properties.getMilvusHost())
            .withPort(properties.getMilvusPort())
            .build());
    }

    @Bean("userMemoryVectorStore")
    public VectorStore userMemoryVectorStore(UserMemoryProperties properties,
                                             ObjectProvider<MilvusServiceClient> milvusServiceClient,
                                             @Qualifier("userMemoryEmbeddingModel") EmbeddingModel embeddingModel) throws Exception {
        if (!embeddingAvailable(properties)) {
            return new DisabledVectorStore();
        }
        MilvusVectorStore store = MilvusVectorStore.builder(milvusServiceClient.getObject(), embeddingModel)
            .collectionName(properties.getMilvusUserMemoryCollection())
            .embeddingDimension(properties.getEmbeddingDimension())
            .metricType(MetricType.COSINE)
            .indexType(IndexType.HNSW)
            .indexParameters(MILVUS_HNSW_INDEX_PARAMS)
            .initializeSchema(true)
            .build();
        store.afterPropertiesSet();
        return store;
    }

    @Bean
    public UserMemoryStore userMemoryStore(UserMemoryProperties properties,
                                           @Qualifier("userMemoryVectorStore") VectorStore vectorStore) {
        if (!embeddingAvailable(properties)) {
            return new NoopUserMemoryStore();
        }
        return new SpringAiUserMemoryStore(vectorStore);
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

    private String embeddingApiKey(UserMemoryProperties properties) {
        return firstNonBlank(
            properties.getEmbeddingApiKey(),
            System.getenv("ALIYUN_BAILIAN_API_KEY"),
            System.getenv("DASHSCOPE_API_KEY")
        );
    }

    private boolean embeddingAvailable(UserMemoryProperties properties) {
        return properties.isEnabled() && embeddingApiKey(properties) != null;
    }
}
