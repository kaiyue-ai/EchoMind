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
 *   <li>创建{@link SkillRepository}实体并持久化到数据库</li>
 *   <li>在运行时{@link SkillRegistry}中注册Skill</li>
 * </ol>
 *
 * <p>数据存储：</p>
 * <ul>
 *   <li>元数据通过JPA持久化到数据库（H2/MySQL/PostgreSQL）</li>
 *   <li>JAR文件存储在本地市场目录中</li>
 *   <li>运行时实例保存在{@link SkillRegistry}的内存注册表中</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see SkillEntityRepository
 * @see SkillRepository
 * @see SkillJarLoader
 */
@Slf4j
public class MarketplaceService {

    /** JSON序列化器，用于将元数据对象转换为JSON字符串存储到数据库 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Skill市场数据访问层，提供数据库CRUD操作 */
    private final SkillEntityRepository repository;

    /** JAR文件加载器，负责从JAR中提取Skill实例 */
    private final SkillJarLoader jarLoader;

    /** Skill运行时注册中心，用于注册已加载的Skill */
    private final SkillRegistry registry;

    /** 市场目录路径，上传的JAR文件将复制到此处 */
    private final Path marketplaceDir;

    /** Skill JAR 的对象存储服务，优先写 OSS，配置缺失时写本地兜底目录 */
    private final ObjectStorageService storageService;

    /**
     * 构造市场服务。
     *
     * <p>构造时会自动创建市场目录（如果不存在）。</p>
     *
     * @param repository         数据访问层
     * @param jarLoader          JAR加载器
     * @param registry           运行时注册中心
     * @param marketplaceDirPath 市场目录的路径字符串
     * @throws RuntimeException 如果无法创建市场目录
     */
    public MarketplaceService(SkillEntityRepository repository, SkillJarLoader jarLoader,
                              SkillRegistry registry, String marketplaceDirPath) {
        this(repository, jarLoader, registry, marketplaceDirPath,
            new com.echomind.skill.storage.LocalObjectStorageService(Paths.get(marketplaceDirPath)));
    }

    public MarketplaceService(SkillEntityRepository repository, SkillJarLoader jarLoader,
                              SkillRegistry registry, String marketplaceDirPath,
                              ObjectStorageService storageService) {
        this.repository = repository;
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
     * 上传Skill到市场。
     *
     * <p>完整的Skill上传流程：加载 → 去重检查 → 复制JAR → 持久化 → 运行时注册。
     * 所有步骤在同一个事务上下文中执行，任一步骤失败都会回滚。</p>
     *
     * @param jarFile 要上传的JAR文件路径
     * @return 持久化后的Skill实体，包含自动生成的ID和时间戳
     * @throws SkillLoadException 如果JAR加载失败、Skill已存在或持久化失败
     */
    public SkillRepository upload(Path jarFile) {
        try {
            SkillJarLoader.SkillLoadResult result = jarLoader.load(jarFile);
            Skill skill = result.skill();
            var metadata = skill.metadata();

            Optional<SkillRepository> existing = repository.findByNameAndVersion(
                metadata.name(), metadata.version());
            if (existing.isPresent()) {
                throw new SkillLoadException("Skill already exists: " + metadata.skillId());
            }

            // 先保留一份本地运行时缓存，再把 JAR 写入对象存储作为持久化事实。
            Path destJar = marketplaceDir.resolve(metadata.skillId().replace("@", "-") + ".jar");
            Files.copy(jarFile, destJar, StandardCopyOption.REPLACE_EXISTING);
            String objectKey = "skills/" + metadata.name() + "/" + metadata.version() + "/"
                + metadata.skillId().replace("@", "-") + ".jar";
            var storedJar = storageService.putObject(objectKey, destJar, "application/java-archive");

            SkillRepository entity = new SkillRepository();
            entity.setName(metadata.name());
            entity.setVersion(metadata.version());
            entity.setDescription(metadata.description());
            entity.setParameterSchemaJson(toJson(metadata.parameterSchema()));
            entity.setDependenciesJson(toJson(metadata.dependencies()));
            entity.setAuthor(metadata.author());
            entity.setTagsJson(toJson(metadata.tags()));
            entity.setKeywordsJson(toJson(metadata.keywords()));
            entity.setAliasesJson(toJson(metadata.aliases()));
            entity.setState(SkillState.LOADED);
            entity.setJarPath(storedJar.uri());

            entity = repository.save(entity);

            // 在运行时注册中心注册
            registry.register(skill, result.classLoader());

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
    public List<SkillRepository> listAll() {
        return repository.findAll();
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
        Optional<SkillRepository> entity = findByIdOrSkillId(identifier);
        if (entity.isPresent()) {
            SkillRepository skill = entity.get();
            String skillId = toSkillId(skill);
            registry.unregister(skillId);
            repository.deleteById(skill.getId());
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
    public Optional<SkillRepository> findByIdOrSkillId(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }

        Optional<SkillRepository> byId = repository.findById(identifier);
        if (byId.isPresent()) {
            return byId;
        }

        int separator = identifier.lastIndexOf('@');
        if (separator <= 0 || separator == identifier.length() - 1) {
            return Optional.empty();
        }
        String name = identifier.substring(0, separator);
        String version = identifier.substring(separator + 1);
        return repository.findByNameAndVersion(name, version);
    }

    private String toSkillId(SkillRepository skill) {
        return skill.getName() + "@" + skill.getVersion();
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
