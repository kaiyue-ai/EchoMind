# ============================
# EchoMind 后端 Dockerfile
# 多阶段构建：编译 → 运行
# ============================

# ---- 阶段1: 编译 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 复制 Maven 配置和源码
COPY pom.xml ./
COPY .mvn/docker-settings.xml ./.mvn/docker-settings.xml
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

# 复制源代码并编译。
#
# 测试在CI/本地验证阶段单独执行；镜像构建只需要生产产物。
# 使用 maven.test.skip=true 可以跳过测试源码编译，避免生产镜像构建被 test scope
# 依赖下载拖慢或阻塞。
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -s .mvn/docker-settings.xml clean package -Dmaven.test.skip=true

# ---- 阶段2: 运行 ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# 创建非 root 用户
RUN groupadd -r echomind && useradd -r -g echomind echomind
RUN mkdir -p /app/data/objects /app/data/marketplace /app/skills && \
    chown -R echomind:echomind /app

# 复制编译产物
COPY --from=build /app/echomind-app/target/echomind-app-*.jar /app/echomind-app.jar
COPY --from=build /app/external-mcp/nowcoder-java-interview-mcp-server-1.0.0.jar /app/mcp/nowcoder-java-interview-mcp-server-1.0.0.jar
# 复制预编译的 Skill JAR
COPY --from=build /app/skills/skill-weather/target/*-jar-with-dependencies.jar /app/skills/
COPY --from=build /app/skills/skill-calculator/target/*-jar-with-dependencies.jar /app/skills/
COPY --from=build /app/skills/skill-websearch/target/*-jar-with-dependencies.jar /app/skills/
COPY --from=build /app/skills/skill-markdown-code/target/*-jar-with-dependencies.jar /app/skills/
COPY --from=build /app/skills/skill-date-query/target/*-jar-with-dependencies.jar /app/skills/
COPY --from=build /app/skills/skill-github-intel/target/*-jar-with-dependencies.jar /app/skills/
COPY --from=build /app/skills/skill-railway-12306/target/*-jar-with-dependencies.jar /app/skills/
COPY --from=build /app/skills/skill-travel-planning/target/*-jar-with-dependencies.jar /app/skills/
RUN chown -R echomind:echomind /app

# 切换到非 root 用户
USER echomind

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD java -cp /app/echomind-app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
      --server.port=8080 2>&1 | grep -q "Started" || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/echomind-app.jar"]
