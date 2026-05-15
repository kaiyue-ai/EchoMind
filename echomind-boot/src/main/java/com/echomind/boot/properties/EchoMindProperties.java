package com.echomind.boot.properties;

import java.util.List;
import java.util.Map;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * EchoMind 平台全局配置属性 —— 映射 {@code echomind.*} 命名空间的 YAML/Properties 配置。
 *
 * <p>本类通过嵌套静态内部类的方式组织各模块的配置，对应如下配置结构：
 * <pre>{@code
 * echomind:
 *   models:
 *     default-provider: deepseek
 *     providers:
 *       deepseek:
 *         api-key: ...
 *         base-url: ...
 *         models: [...]
 *   memory:
 *     short-term-window: 100
 *     redis-ttl-seconds: 604800
 *     vector-store: redis-stack
 *     embedding-model: tongyi-embedding-vision-plus
 *   skill:
 *     auto-load-path: ./skills/
 *     hot-reload: true
 *     marketplace-dir: ./data/marketplace/
 *   mcp:
 *     external-servers: [...]
 *   agents:
 *     - agent-id: default
 *       name: EchoMind Assistant
 *       system-prompt: ...
 *       model-id: ...
 *       skill-ids: [...]
 * }</pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li>所有属性均提供合理的默认值，确保开箱即用。</li>
 *   <li>嵌套类均声明为 {@code public static}，支持 Spring Boot 的
 *       {@code @ConfigurationProperties} 宽松绑定。</li>
 *   <li>Agent 定义列表 {@code agents} 允许在配置文件中声明式定义多 Agent。</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "echomind")
public class EchoMindProperties {

    /** 模型配置（提供商、默认模型等） */
    private Models models = new Models();
    /** 记忆系统配置（近期上下文、摘要、向量检索等） */
    private Memory memory = new Memory();
    /** 技能系统配置（自动加载路径、热重载等） */
    private Skill skill = new Skill();
    /** 对象存储配置（Skill JAR、聊天图片等二进制文件） */
    private Storage storage = new Storage();
    /** 用户长期画像配置。 */
    private UserMemory userMemory = new UserMemory();
    /** 外部MCP服务接入配置 */
    private Mcp mcp = new Mcp();
    /** 预定义 Agent 列表 */
    private List<AgentDef> agents = List.of();

    /**
     * 模型子系统配置 —— 管理 LLM 提供商及其模型定义。
     *
     * <p>支持配置多个提供商（如 deepseek、openai-compatible、aliyun-bailian），
     * 每个提供商可以绑定多个模型并设置默认模型。
     */
    @Data
    public static class Models {
        /** 默认提供商 ID，未指定时使用 "deepseek" */
        private String defaultProvider = "deepseek";
        /** 提供商配置映射，key 为提供商 ID（如 "deepseek"），value 为提供商配置 */
        private Map<String, ProviderConfig> providers = Map.of();
    }

    /**
     * 单个 LLM 提供商的配置。
     *
     * <p>包含 API 密钥、基础 URL 以及该提供商下可用的模型列表。
     * API 密钥也可以通过环境变量（如 {@code DEEPSEEK_API_KEY}）提供，
     * 配置文件和环境变量二者的优先级由 {@link com.echomind.boot.autoconfigure.EchoMindAutoConfiguration}
     * 中的 Bean 创建逻辑决定。
     */
    @Data
    public static class ProviderConfig {
        /** API 密钥（可被环境变量覆盖） */
        private String apiKey;
        /** API 基础 URL（可被环境变量覆盖） */
        private String baseUrl;
        /** 该提供商下的模型列表 */
        private List<ModelConfig> models = List.of();
    }

    /**
     * 单个 LLM 模型的配置。
     *
     * <p>定义模型名称、能力集合（如 TEXT、FUNCTION、VISION 等）
     * 以及是否作为该提供商的默认模型。
     */
    @Data
    public static class ModelConfig {
        /** 模型名称，如 "deepseek-v4-flash" */
        private String name;
        /** 模型能力标签列表，如 ["TEXT", "FUNCTION"] */
        private List<String> capabilities = List.of();
        /** 是否为该提供商的默认模型 */
        private boolean isDefault;
    }

    /**
     * 记忆子系统配置 —— 控制近期上下文、摘要和向量检索行为。
     */
    @Data
    public static class Memory {
        /** LLM prompt 使用的 Redis 近期上下文最大消息数，默认 100 条。完整历史保存在 MySQL 中。 */
        private int shortTermWindow = 100;
        /** Redis 近期缓存的 TTL（秒），默认 604800（7 天）；0 或负数表示永不过期 */
        private long redisTtlSeconds = 604800;
        /** 向量检索开关。 */
        private boolean embeddingEnabled = true;
        /** 向量索引实现，正式路径为 redis-stack；mysql-linear 仅保留为弃用兼容值。 */
        private String vectorStore = "redis-stack";
        /** Redis Stack RediSearch 索引名。 */
        private String vectorIndexName = "idx:echomind:memory:vectors";
        /** Redis Stack 向量 Hash key 前缀。 */
        private String vectorKeyPrefix = "echomind:memory:vector:";
        /** 百炼向量接口 Base URL，默认使用 DashScope 原生地址。 */
        private String embeddingBaseUrl = "https://dashscope.aliyuncs.com";
        /** 百炼向量 API Key，默认从 ALIYUN_BAILIAN_API_KEY 读取。 */
        private String embeddingApiKey;
        /** 向量模型，按需求默认使用 tongyi-embedding-vision-plus。 */
        private String embeddingModel = "tongyi-embedding-vision-plus";
        /** 已弃用：普通聊天历史不再在主链路做向量召回。 */
        private int retrievalTopK = 0;
        /** 普通聊天记忆持久化 RabbitMQ 队列名。 */
        private String persistQueueName = "echomind.chat-memory.persist.requests";
        /** 是否启用普通聊天记忆异步持久化发布。 */
        private boolean asyncPersistEnabled = true;
        /** 摘要最大字符数。 */
        private int summaryMaxChars = 3000;
        /** 每隔多少条消息刷新一次摘要。 */
        private int summaryRefreshInterval = 6;
        /** Agent 私有知识库召回条数。 */
        private int knowledgeTopK = 4;
        /** Agent 私有知识库单片最大字符数。 */
        private int knowledgeChunkSize = 1000;
        /** Agent 私有知识库切片重叠字符数。 */
        private int knowledgeChunkOverlap = 150;
        /** Agent 私有知识库向量召回最小相似度，低于该值的片段不注入提示词。 */
        private double knowledgeMinVectorSimilarity = 0.25;
        /** Agent 私有知识库混合召回中向量相似度的权重。 */
        private double knowledgeVectorWeight = 0.75;
        /** Agent 私有知识库混合召回中关键词匹配的权重。 */
        private double knowledgeKeywordWeight = 0.25;
        /** Agent 私有知识库关键词粗召回最大候选数。 */
        private int knowledgeKeywordCandidateLimit = 40;
        /** Agent 私有知识库 Redis Stack 索引名。 */
        private String knowledgeVectorIndexName = "idx:echomind:agent:knowledge:vectors";
        /** Agent 私有知识库 Redis Stack Hash key 前缀。 */
        private String knowledgeVectorKeyPrefix = "echomind:agent:knowledge:vector:";
        /** 是否启用扫描版 PDF OCR。 */
        private boolean knowledgeOcrEnabled = true;
        /** OCR 语言包，chi_sim+eng 表示简体中文和英文混合识别。 */
        private String knowledgeOcrLanguage = "chi_sim+eng";
        /** OCR 渲染 DPI；越高越准但越慢。 */
        private int knowledgeOcrDpi = 200;
        /** PDFBox 抽取文本少于该字符数时才触发 OCR。 */
        private int knowledgeOcrMinTextChars = 80;
        /** 单个 PDF 最多 OCR 页数，防止超大文件拖垮服务。 */
        private int knowledgeOcrMaxPages = 20;
        /** Tesseract 命令名或绝对路径。 */
        private String knowledgeOcrTesseractCommand = "tesseract";
        /** 最终发给模型的用户 prompt 最大字符数，给不同来源上下文做统一兜底。 */
        private int promptMaxChars = 24000;
        /** 知识库、摘要等 system 注入消息单条最大字符数。 */
        private int promptMaxSystemMessageChars = 12000;
        /** 普通历史消息单条最大字符数。 */
        private int promptMaxHistoryMessageChars = 4000;
    }

    /** 用户长期画像配置。 */
    @Data
    public static class UserMemory {
        /** 是否启用用户长期画像检索和异步写入。 */
        private boolean enabled = true;
        /** RabbitMQ 队列名。 */
        private String queueName = "echomind.user-memory.requests";
        /** 画像召回条数。 */
        private int topK = 5;
        /** 注入画像的最低置信度。 */
        private double minConfidence = 0.3;
        /** Redis Stack 用户画像索引名。 */
        private String vectorIndexName = "idx:user:memory:vectors";
        /** Redis Stack 用户画像 Hash key 前缀。 */
        private String vectorKeyPrefix = "user:memory:vector:";
        /** 微服务提取时最多读取多少条既有画像作为上下文。 */
        private int existingProfileLimit = 30;
        /** 单次提取最多写入多少条画像。 */
        private int maxExtractedEntries = 10;
        /** 微服务提取画像使用的模型 ID，为空时使用默认模型。 */
        private String extractorModelId;
    }

    /**
     * 技能子系统配置 —— 控制技能自动加载和热重载行为。
     */
    @Data
    public static class Skill {
        /** 技能 JAR 自动加载目录，默认 "./skills/" */
        private String autoLoadPath = "./skills/";
        /** 是否启用热重载（监听目录变化自动重新加载），默认 true */
        private boolean hotReload = true;
        /** 技能市场持久化目录，默认 "./data/marketplace/" */
        private String marketplaceDir = "./data/marketplace/";
    }

    /**
     * 对象存储配置。
     *
     * <p>生产环境建议 mode=oss，并通过环境变量注入 endpoint、AccessKeyID、
     * AccessKeySecret 和 bucket；开发测试环境可以保持 local。</p>
     */
    @Data
    public static class Storage {
        /** 存储模式：oss 或 local。 */
        private String mode = "local";
        /** 本地兜底目录。 */
        private String localDir = "./data/objects/";
        /** OSS Endpoint，例如 https://oss-cn-hangzhou.aliyuncs.com。 */
        private String endpoint;
        /** OSS Bucket，默认按用户要求使用 my-dev-oss。 */
        private String bucket = "my-dev-oss";
        /** OSS AccessKey ID。 */
        private String accessKeyId;
        /** OSS AccessKey Secret。 */
        private String accessKeySecret;
    }

    /**
     * 外部 MCP 接入配置。
     */
    @Data
    public static class Mcp {
        /** 外部 MCP Server 列表，启动时会注册进 Agent 工具中心。 */
        private List<ExternalMcpServer> externalServers = List.of();
    }

    /**
     * 外部 MCP Server 配置。
     *
     * <p>当前支持 stdio 方式：主项目启动一个本地命令，把它当 MCP 子进程通信。</p>
     */
    @Data
    public static class ExternalMcpServer {
        /** 服务器唯一标识，用于日志和工具来源追踪。 */
        private String id;
        /** 是否启用该外部 MCP Server。 */
        private boolean enabled = true;
        /** 传输方式，当前实现 stdio。 */
        private String transport = "stdio";
        /** 启动命令，例如 ["java", "-jar", "/app/mcp/nowcoder.jar"]。 */
        private List<String> command = List.of();
        /** 子进程工作目录，可为空。 */
        private String workingDirectory;
    }

    /**
     * 单个 Agent 的配置定义 —— 用于在配置文件中声明式定义 Agent。
     *
     * <p>每个 Agent 定义包括：唯一标识、显示名称、系统提示词、
     * 绑定的模型 ID 以及可调用的技能 ID 列表。
     */
    @Data
    public static class AgentDef {
        /** Agent 唯一标识 */
        private String agentId;
        /** Agent 显示名称 */
        private String name;
        /** 系统提示词，定义 Agent 的角色和行为 */
        private String systemPrompt;
        /** 绑定的模型 ID，格式为 "providerId:modelName" */
        private String modelId;
        /** 可调用的技能 ID 列表 */
        private List<String> skillIds = List.of();
    }
}
