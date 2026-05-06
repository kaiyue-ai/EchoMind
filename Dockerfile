# ============================
# EchoMind 后端 Dockerfile
# 多阶段构建：编译 → 运行
# ============================

# ---- 阶段1: 编译 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 复制 Maven 配置和源码
COPY pom.xml ./
COPY echomind-common/pom.xml ./echomind-common/
COPY echomind-skill-api/pom.xml ./echomind-skill-api/
COPY echomind-llm/pom.xml ./echomind-llm/
COPY echomind-memory/pom.xml ./echomind-memory/
COPY echomind-mcp/pom.xml ./echomind-mcp/
COPY echomind-skill/pom.xml ./echomind-skill/
COPY echomind-agent/pom.xml ./echomind-agent/
COPY echomind-agent-team/pom.xml ./echomind-agent-team/
COPY echomind-console/pom.xml ./echomind-console/
COPY echomind-boot/pom.xml ./echomind-boot/
COPY echomind-app/pom.xml ./echomind-app/
COPY skills/ ./skills/

# 先下载依赖（利用 Docker 缓存层）
RUN mvn -B dependency:go-offline -DskipTests || true

# 复制源代码并编译
COPY . .
RUN mvn -B clean package -DskipTests

# ---- 阶段2: 运行 ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# 创建非 root 用户
RUN groupadd -r echomind && useradd -r -g echomind echomind
RUN mkdir -p /app/data/memory /app/data/marketplace /app/skills && \
    chown -R echomind:echomind /app

# 复制编译产物
COPY --from=build /app/echomind-app/target/echomind-app-*.jar /app/echomind-app.jar
# 复制预编译的 Skill JAR
COPY --from=build /app/skills/skill-weather/target/*.jar /app/skills/
COPY --from=build /app/skills/skill-calculator/target/*.jar /app/skills/
COPY --from=build /app/skills/skill-websearch/target/*.jar /app/skills/
COPY --from=build /app/skills/skill-filesystem/target/*.jar /app/skills/

# 切换到非 root 用户
USER echomind

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD java -cp /app/echomind-app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
      --server.port=8080 2>&1 | grep -q "Started" || exit 1

ENTRYPOINT ["java", "-jar", "/app/echomind-app.jar"]
