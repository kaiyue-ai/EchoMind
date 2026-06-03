package com.echomind.skill.marketplace;

import com.echomind.common.exception.SkillLoadException;
import com.echomind.common.model.SkillState;
import com.echomind.skill.api.Skill;
import com.echomind.skill.loader.SkillJarLoader;
import com.echomind.skill.registry.SkillRegistry;
import com.echomind.skill.storage.ObjectStorageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Skill市场服务，提供Skill的上传、查询和删除等管理功能。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li><b>上传（Upload）</b>：加载JAR文件，提取元数据，持久化到数据库，复制JAR到市场目录，并在运行时注册</li>
 *   <li><b>查询（List）</b>：从数据库查询所有已上传的Skill</li>
 *   <li><b>删除（Delete）</b>：从运行时注销，从数据库删除记录，删除JAR文件</li>
 * </ul>
 *
 * <p>上传流程详解：</p>
 * <ol>
 *   <li>通过{@link SkillJarLoader#load(Path)}加载JAR并实例化Skill</li>
 *   <li>检查数据库中是否已存在同名同版本的Skill（防止重复上传）</li>
 *   <li>复制JAR文件到市场目录（目标文件名格式：{@code skillId@ → skillId-}.jar）</li>
 *   <li>创建{@link SkillEntity}实体并持久化到数据库</li>
 *   <li>在运行时{@link SkillRegistry}中注册Skill</li>
 * </ol>
 *
 * <p>数据存储：</p>
 * <ul>
 *   <li>元数据通过 MyBatis-Plus 持久化到数据库（H2/MySQL）</li>
 *   <li>JAR文件存储在本地市场目录中</li>
 *   <li>运行时实例保存在{@link SkillRegistry}的内存注册表中</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see SkillMapper
 * @see SkillEntity
 * @see SkillJarLoader
 */
@Slf4j
public class MarketplaceService {

    /** JSON序列化器，用于将元数据对象转换为JSON字符串存储到数据库 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Skill市场数据访问层，提供数据库CRUD操作 */
    private final SkillMapper skillMapper;

    /** JAR文件加载器，负责从JAR中提取Skill实例 */
    private final SkillJarLoader jarLoader;

    /** Skill运行时注册中心，用于注册已加载的Skill */
    private final SkillRegistry registry;

    /** 市场目录路径，上传的JAR文件将复制到此处 */
    private final Path marketplaceDir;

    /** Skill JAR 的对象存储服务，优先写 OSS，配置缺失时写本地兜底目录 */
    private final ObjectStorageService storageService;

    public MarketplaceService(SkillMapper skillMapper, SkillJarLoader jarLoader,
                              SkillRegistry registry, String marketplaceDirPath,
                              ObjectStorageService storageService) {
        this.skillMapper = skillMapper;
        this.jarLoader = jarLoader;
        this.registry = registry;
        this.marketplaceDir = Paths.get(marketplaceDirPath);
        this.storageService = storageService;
        try {
            Files.createDirectories(marketplaceDir);
        } catch (Exception e) {
            throw new RuntimeException("Cannot create marketplace dir: " + marketplaceDirPath, e);
        }
    }

    /**
     * 上传 Skill 到市场。
     *
     * <p>流程：加载 → 去重 → 存储 → 注册 → 持久化。</p>
     *
     * @param jarFile JAR 文件路径
     * @return Skill 实体
     * @throws SkillLoadException 加载失败时抛出
     */
    public SkillEntity upload(Path jarFile) {
        try {
            // 步骤 1：加载 JAR，获取 Skill 实例和 ClassLoader
            SkillJarLoader.SkillLoadResult result = jarLoader.load(jarFile);
            Skill skill = result.skill();// 获取skill
            var metadata = skill.metadata(); // 获取元数据

            // 步骤 2：去重检查,检查数据库中是否已存在同名同版本的Skill
            Optional<SkillEntity> existing = skillMapper.selectByNameAndVersion(
                metadata.name(), metadata.version());
            if (existing.isPresent()) {
                throw new SkillLoadException("Skill already exists: " + metadata.skillId());
            }

            // 步骤 3：复制到本地缓存
            Path destJar = marketplaceDir.resolve(metadata.skillId().replace("@", "-") + ".jar");
            Files.copy(jarFile, destJar, StandardCopyOption.REPLACE_EXISTING);

            // 步骤 4：上传到 OSS（持久化存储，支持分布式共享）
            String objectKey = "skills/" + metadata.name() + "/" + metadata.version() + "/"
                + metadata.skillId().replace("@", "-") + ".jar";
            var storedJar = storageService.putObject(objectKey, destJar, "application/java-archive");

            // 步骤 5：创建数据库实体
            SkillEntity entity = new SkillEntity();
            entity.setName(metadata.name());
            entity.setVersion(metadata.version());
            entity.setDescription(metadata.description());
            entity.setParameterSchemaJson(toJson(metadata.parameterSchema()));
            entity.setDependenciesJson(toJson(metadata.dependencies()));
            entity.setAuthor(metadata.author());
            entity.setTagsJson(toJson(metadata.tags()));
            entity.setKeywordsJson(toJson(metadata.keywords()));
            entity.setAliasesJson(toJson(metadata.aliases()));
            entity.setJarPath(storedJar.uri());

            // 步骤 6：运行时注册（ClassLoader 隔离不同 Skill 的类）
            registry.register(skill, result.classLoader());// 注册进skill
            entity.setState(SkillState.ENABLED);// 设置状态为启用

            // 步骤 7：持久化到数据库
            entity = skillMapper.upsertById(entity);

            return entity;
        } catch (SkillLoadException e) {
            throw e;
        } catch (Exception e) {
            throw new SkillLoadException("Failed to upload skill", e);
        }
    }

    /**
     * 列出市场中所有Skill。
     *
     * @return 所有已上传Skill实体的列表
     */
    public List<SkillEntity> listAll() {
        return skillMapper.selectAll();
    }

    /**
     * 更新已持久化 Skill 的生命周期状态。
     *
     * @param identifier 数据库ID或{@code name@version}
     * @param state      目标状态
     */
    public Optional<SkillEntity> updateState(String identifier, SkillState state) {
        return findByIdOrSkillId(identifier)
            .map(entity -> {
                entity.setState(state);
                return skillMapper.upsertById(entity);
            });
    }

    /**
     * 启动时从数据库恢复所有已上传的Skill到运行时注册中心。
     *
     * <p>恢复逻辑：</p>
     * <ol>
     *   <li>查询数据库中所有Skill记录</li>
     *   <li>跳过已在注册中心中的Skill（例如已通过autoLoadPath加载的）</li>
     *   <li>从市场目录中定位JAR文件并重新加载</li>
     *   <li>加载成功后在运行时注册中心注册</li>
     * </ol>
     *
     * <p>此方法在应用启动时由自动装配调用，确保上传的Skill在重启后仍然可用。</p>
     */
    public void restoreFromDatabase() {
        List<SkillEntity> allSkills = skillMapper.selectAll();
        Set<String> existingIds = registry.listAll().stream()
            .map(r -> r.getMetadata().skillId())
            .collect(Collectors.toSet());

        for (SkillEntity entity : allSkills) {
            String skillId = toSkillId(entity);
            if (existingIds.contains(skillId)) {
                log.info("Skill {} already registered, skipping restore", skillId);
                continue;
            }
            try {
                Path jarPath = resolveRestoreJar(entity);
                SkillJarLoader.SkillLoadResult result = jarLoader.load(jarPath);
                registry.register(result.skill(), result.classLoader());
                if (entity.getState() == SkillState.DISABLED) {
                    registry.disable(skillId);
                }
                log.info("Restored skill from marketplace: {}", skillId);
            } catch (Exception e) {
                log.error("Failed to restore skill {}: {}", skillId, e.getMessage());
            }
        }
    }

    /**
     * 删除已退役的内置 Skill 记录，避免历史数据库记录在市场页继续展示。
     */
    public void purgeRetiredSkills(Set<String> retiredSkillNames) {
        if (retiredSkillNames == null || retiredSkillNames.isEmpty()) {
            return;
        }
        for (SkillEntity entity : skillMapper.selectAll()) {
            if (retiredSkillNames.contains(entity.getName())) {
                delete(toSkillId(entity));
            }
        }
    }

    /**
     * 从市场中删除指定的Skill。
     *
     * <p>删除流程：</p>
     * <ol>
     *   <li>从运行时注册中心注销Skill</li>
     *   <li>从数据库删除记录</li>
     *   <li>删除市场目录中的JAR文件（失败时记录警告但不中断）</li>
     * </ol>
     *
     * @param identifier 要删除的Skill标识；可以是数据库ID，也可以是{@code name@version}
     * @return 被删除Skill的运行时skillId，格式为{@code name@version}
     */
    public Optional<String> delete(String identifier) {
        Optional<SkillEntity> entity = findByIdOrSkillId(identifier);
        if (entity.isPresent()) {
            SkillEntity skill = entity.get();
            String skillId = toSkillId(skill);
            registry.unregister(skillId);
            skillMapper.deleteById(skill.getId());
            try {
                String jarPath = skill.getJarPath();
                if (jarPath != null && storageService.supports(jarPath)) {
                    storageService.deleteObject(jarPath);
                } else if (jarPath != null) {
                    Files.deleteIfExists(Paths.get(jarPath));
                }
                Files.deleteIfExists(marketplaceDir.resolve(skillId.replace("@", "-") + ".jar"));
            } catch (Exception e) {
                log.warn("Failed to delete skill JAR: {}", e.getMessage());
            }
            return Optional.of(skillId);
        }
        return Optional.empty();
    }

    /**
     * 根据数据库ID或运行时skillId查找市场记录。
     */
    private Optional<SkillEntity> findByIdOrSkillId(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }

        Optional<SkillEntity> byId = skillMapper.selectOptionalById(identifier);
        if (byId.isPresent()) {
            return byId;
        }

        int separator = identifier.lastIndexOf('@');
        if (separator <= 0 || separator == identifier.length() - 1) {
            return Optional.empty();
        }
        String name = identifier.substring(0, separator);
        String version = identifier.substring(separator + 1);
        return skillMapper.selectByNameAndVersion(name, version);
    }

    private String toSkillId(SkillEntity skill) {
        return skill.getName() + "@" + skill.getVersion();
    }

    private Path resolveRestoreJar(SkillEntity entity) throws Exception {
        String skillId = toSkillId(entity);
        Path cachedJar = marketplaceDir.resolve(skillId.replace("@", "-") + ".jar");
        if (Files.exists(cachedJar)) {
            return cachedJar;
        }

        String storedPath = entity.getJarPath();
        if (storedPath == null || storedPath.isBlank()) {
            throw new SkillLoadException("JAR file not found for skill " + skillId);
        }

        if (!storageService.supports(storedPath)) {
            Path directPath = Paths.get(storedPath);
            if (Files.exists(directPath)) {
                Files.copy(directPath, cachedJar, StandardCopyOption.REPLACE_EXISTING);
                return cachedJar;
            }
            throw new SkillLoadException("JAR file not found for skill " + skillId + ": " + storedPath);
        }

        Files.createDirectories(cachedJar.getParent());
        Files.write(cachedJar, storageService.readObject(storedPath));
        return cachedJar;
    }

    /**
     * 将对象序列化为JSON字符串。
     *
     * <p>用于将Skill元数据中的复杂字段（参数Schema、依赖项、标签等）
     * 转换为JSON字符串存储在数据库的文本列中。</p>
     *
     * @param obj 要序列化的对象，可以为null
     * @return JSON字符串，如果对象为null或序列化失败则返回{@code "[]"}
     */
    private String toJson(Object obj) {
        try {
            return obj != null ? MAPPER.writeValueAsString(obj) : "[]";
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
