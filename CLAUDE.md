# CLAUDE.md — EchoMind AI Agent Platform

## Project Overview
EchoMind is a modular AI Agent platform built on Spring Boot 3.3 / Java 17, providing:
- Multi-model LLM abstraction with runtime switching (adapted Spring AI)
- Plugin-based Skill marketplace with classloader isolation
- MCP protocol compatibility for external tool discovery
- Session memory (short-term window + long-term persistence)
- Optional multi-Agent Team collaboration with role-based workflow

## Build Commands
```bash
# Full project build (skip tests)
mvn -f D:\claudeWorkSpace\ai-agent\pom.xml clean package -DskipTests

# Full project build with tests
mvn -f D:\claudeWorkSpace\ai-agent\pom.xml clean verify

# Build single module
mvn -f D:\claudeWorkSpace\ai-agent\echomind-common\pom.xml clean install

# Run the application
mvn -f D:\claudeWorkSpace\ai-agent\echomind-app\pom.xml spring-boot:run

# Run tests for a module
mvn -f D:\claudeWorkSpace\ai-agent\echomind-llm\pom.xml test

# Maven settings override (if needed)
mvn -s D:\mvn\apache-maven-3.8.6\conf\settings.xml clean package
```

## Module Map
```
pom.xml                          Parent POM, dependency management, 12 sub-modules
echomind-common/                 Shared models (AgentMessage, SkillMetadata), exceptions, JSON Schema validation
echomind-skill-api/              SPI: Skill interface — THE extension contract. No dependencies.
echomind-llm/                    DynamicModelRouter, ProviderRegistry, AnthropicProvider, OpenAIProvider
echomind-memory/                 ConversationWindow (Deque), FileLongTermStore, RedisLongTermStore
echomind-mcp/                    MCPServer, MCPClient, JsonRpcMessage (2024-11-05 spec)
echomind-skill/                  SkillRegistry, SkillClassLoader, SkillJarLoader, SkillDirectoryWatcher
echomind-agent/                  Agent, AgentFactory, ExecutionPipeline (5 stages), AgentOrchestrator
echomind-agent-team/             AgentTeam, TeamCoordinator, TeamMessageBus, ConsensusEngine, MermaidGenerator
echomind-console/                REST controllers, Thymeleaf web UI, Spring Shell CLI
echomind-boot/                   Spring Boot auto-configuration, EchoMindProperties
echomind-app/                    @SpringBootApplication entry point, application.yml
skills/skill-weather/            Weather Skill (OpenWeatherMap API or mock)
skills/skill-calculator/         Calculator Skill (exp4j)
skills/skill-websearch/          Web Search Skill (DuckDuckGo API)
skills/skill-filesystem/         MCP File System Skill (MCP + standard Skill dual-mode)
```

## Dependency Direction (strict — no cycles)
```
echomind-skill-api  ←──  (zero deps, pure SPI)
echomind-common     ←──  (zero deps)
echomind-llm        ←──  common + Spring AI
echomind-memory     ←──  common
echomind-mcp        ←──  common
echomind-skill      ←──  skill-api, common, memory
echomind-agent      ←──  skill-api, common, llm, memory, mcp
echomind-agent-team ←──  agent, skill-api
echomind-console    ←──  agent, agent-team(optional), skill, mcp
echomind-boot       ←──  console, agent, skill, llm, memory, mcp
echomind-app        ←──  boot
skills/*            ←──  skill-api (provided scope, isolated classloader)
```

## Module Descriptions
- **echomind-common**: Shared models (`AgentMessage`, `SkillMetadata`, `SkillState`, `ToolCall`), JSON Schema validation utilities, and the exception hierarchy rooted at `EchoMindException`. Zero external dependencies beyond Jackson and SLF4J.
- **echomind-llm**: "Adapted Spring AI" — wraps Spring AI's `ChatModel` with a `DynamicModelRouter` that selects providers at runtime based on session context. Providers implement a unified `ModelProvider` interface. Also contains the AOP observer (`@Observable` + `AgentInvocationObserver`) for agent call instrumentation.
- **echomind-memory**: Short-term memory via `ConversationWindow` (bounded `Deque<AgentMessage>`), long-term persistence via `LongTermMemoryStore` interface with `FileLongTermStore` (JSON files) and optional `RedisLongTermStore`. `AutoSummarizer` compresses old windows via LLM.
- **echomind-mcp**: MCP 2024-11-05 protocol implementation. `MCPServer` registers tools and handles JSON-RPC over stdio/HTTP. `MCPClient` discovers and invokes remote MCP tools. `MCPToolAdapter` bridges MCP tools into the Skill ecosystem.
- **echomind-skill-api**: THE extension contract. Single `Skill` interface with lifecycle hooks. Skills are packaged as JARs with `echomind-skill-api` at `provided` scope. This module has NO other dependencies — it's the shared contract between platform and plugins.
- **echomind-skill**: Runtime for skills. `SkillClassLoader` provides parent-first delegation for `java.*` and `com.echomind.skill.api.*`, self-first for skill classes. `SkillJarLoader` reads JAR manifests for the `EchoMind-Skill-Class` entry. `SkillDirectoryWatcher` watches `./skills/` for hot-reload. `MarketplaceService` persists metadata via JPA.
- **echomind-agent**: Agent instance (`Agent`) with `ExecutionPipeline` (5 stages: context enrichment → tool resolution → skill invocation → result aggregation → memory persistence). `AgentOrchestrator` drives the pipeline. `TaskRouter` matches user intent to skills/agents.
- **echomind-agent-team**: Multi-agent collaboration. `TeamCoordinator` runs Planner→Executor→Reviewer cycles over a `TeamMessageBus`. `ConsensusEngine` resolves disputes. `MermaidGenerator` produces flow diagrams from `TeamTraceRecorder` events. `DynamicDecisionEngine` uses LLM for next-action decisions (bonus).
- **echomind-console**: MVC controllers (REST + Thymeleaf views), Spring Shell CLI commands. Web UI uses Bootstrap 5. WebSocket (STOMP) for real-time team dashboard updates.
- **echomind-boot**: Auto-configuration via `EchoMindAutoConfiguration` with `@ConditionalOnProperty` guards. Properties under `echomind.*` namespace via `EchoMindProperties`.
- **echomind-app**: Entry point. Contains `EchoMindApplication`, `application.yml`, and multi-profile configs (`application-dev.yml`, `application-prod.yml`).

## Coding Conventions

### Package Naming
- All EchoMind code under `com.echomind.<module>`
- Sub-packages by concern: `.controller`, `.service`, `.config`, `.model`

### Bean Naming
- Service beans: camelCase class name (Spring default), e.g. `skillRegistry`
- Configuration properties: `echomind.<module>.<property>`

### Exception Handling
- Base: `EchoMindException extends RuntimeException`
- Specific: `SkillLoadException`, `ModelRoutingException`, `MemoryPersistenceException`, `MCPTransportException`
- Controllers use `@ControllerAdvice` returning RFC 7807 Problem Details

### MVC Layering
```
Controller (@RestController / @Controller)
    ↓
Service / Orchestrator (@Service)
    ↓
Domain / SPI (interfaces in -api modules)
```

## Key Extension Points

### Skill Interface (echomind-skill-api)
```java
public interface Skill {
    SkillMetadata metadata();
    CompletableFuture<SkillResult> execute(SkillRequest request);
    default void onEnable() {}
    default void onDisable() {}
    default void onDestroy() {}
}
```

### ModelProvider Interface (echomind-llm)
```java
public interface ModelProvider {
    String providerId();
    Set<ModelCapability> capabilities();
    ChatResponse chat(ChatRequest request, ModelSpec model);
    Flux<ChatResponse> stream(ChatRequest request, ModelSpec model);
}
```

### PipelineStage Interface (echomind-agent)
```java
public interface PipelineStage {
    int order();
    PipelineContext process(PipelineContext ctx);
}
```

### LongTermMemoryStore Interface (echomind-memory)
```java
public interface LongTermMemoryStore {
    void save(String sessionId, List<AgentMessage> messages);
    List<AgentMessage> load(String sessionId);
    List<AgentMessage> query(String sessionId, String keyword);
    void delete(String sessionId);
}
```

## Environment Variables
| Variable | Required | Description |
|---|---|---|
| `ANTHROPIC_API_KEY` | Yes | Anthropic API key |
| `ANTHROPIC_BASE_URL` | Yes | Anthropic API base URL |
| `OPENAI_API_KEY` | No | OpenAI API key (optional) |
| `ECHOMIND_SKILL_DIR` | No | Skill auto-load directory (default: `./skills/`) |
| `ECHOMIND_MEMORY_DIR` | No | Memory persistence directory (default: `./data/memory/`) |

## Skill Development Process
1. Create a new Maven module under `skills/`
2. Add `echomind-skill-api` as `provided` scope dependency
3. Implement the `Skill` interface
4. Set `EchoMind-Skill-Class` in JAR manifest to the FQCN
5. Build with `mvn package`
6. Place JAR in `./skills/` directory (auto-hot-load) or upload via Web/CLI

## Testing
- **Unit tests**: `mvn test` — JUnit 5 + Mockito
- **Integration tests**: `mvn verify` — `@SpringBootTest` on `echomind-app` with test profile
- Key test patterns:
  - `SkillClassLoader` isolation: verify skill classes loaded from skill JAR, not app classpath
  - `DynamicModelRouter`: verify routing based on session model preference
  - `FileLongTermStore`: write messages, create new instance, verify read-back
  - MCP JSON-RPC: verify parse/serialize round-trip
