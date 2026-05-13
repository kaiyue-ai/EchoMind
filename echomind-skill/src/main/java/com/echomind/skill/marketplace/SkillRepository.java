package com.echomind.skill.marketplace;

import com.echomind.common.model.SkillState;
import jakarta.persistence.*;
import java.time.Instant;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Skill市场JPA实体，对应数据库表{@code echomind_skills}。
 *
 * <p>此实体存储Skill的完整元数据信息，用于Skill市场的持久化管理。
 * 每个Skill在数据库中有唯一UUID主键，支持按名称和版本的复合唯一查询。</p>
 *
 * <p>主要字段说明：</p>
 * <ul>
 *   <li><b>id</b> — UUID主键，由JPA自动生成</li>
 *   <li><b>name</b> — Skill名称（必填）</li>
 *   <li><b>version</b> — Skill版本号（必填），与name组成逻辑唯一键</li>
 *   <li><b>description</b> — Skill功能描述，最长2000字符</li>
 *   <li><b>parameterSchemaJson</b> — 参数JSON Schema，最长4000字符</li>
 *   <li><b>dependenciesJson</b> — 依赖项JSON数组，最长1000字符</li>
 *   <li><b>tagsJson</b> — 标签JSON数组，最长1000字符</li>
 *   <li><b>keywordsJson</b> — 显式触发关键词JSON数组，最长2000字符</li>
 *   <li><b>aliasesJson</b> — 关键词别名JSON对象，最长4000字符</li>
 *   <li><b>state</b> — Skill当前状态（枚举值存储为字符串）</li>
 *   <li><b>jarPath</b> — Skill JAR文件在服务器上的存储路径</li>
 * </ul>
 *
 * <p>生命周期回调：</p>
 * <ul>
 *   <li>{@link #prePersist()} — 首次持久化前自动设置{@code createdAt}和{@code updatedAt}</li>
 *   <li>{@link #preUpdate()} — 每次更新前自动刷新{@code updatedAt}</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see SkillEntityRepository
 * @see MarketplaceService
 */
@Entity
@Table(name = "echomind_skills")
@Getter
@Setter
public class SkillRepository {

    /**
     * Skill记录的唯一主键。
     * 使用UUID生成策略，确保分布式环境下的唯一性。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Skill名称，不可为空，与version组合唯一标识一个Skill */
    @Column(nullable = false)
    private String name;

    /** Skill版本号（语义化版本格式），不可为空 */
    @Column(nullable = false)
    private String version;

    /** Skill功能的详细描述，支持最长2000字符 */
    @Column(length = 2000)
    private String description;

    /**
     * 参数的JSON Schema定义，以JSON字符串形式存储。
     * 用于描述Skill接受的输入参数的结构和约束，最长4000字符。
     */
    @Column(length = 4000)
    private String parameterSchemaJson;

    /**
     * Skill依赖项的JSON数组字符串。
     * 记录此Skill依赖的其他库或Skill，最长1000字符。
     */
    @Column(length = 1000)
    private String dependenciesJson;

    /** Skill的作者或维护者信息 */
    private String author;

    /**
     * Skill标签的JSON数组字符串。
     * 用于分类和搜索，如{@code ["weather", "api", "forecast"]}，最长1000字符。
     */
    @Column(length = 1000)
    private String tagsJson;

    /**
     * Skill显式声明的触发关键词JSON数组。
     *
     * <p>当前不自动改表，因此该字段只作为运行时/未来迁移预留，不参与JPA持久化。
     * 工具匹配实际读取的是JAR中{@code SkillMetadata}的运行时元数据。</p>
     */
    @Transient
    private String keywordsJson;

    /**
     * Skill关键词别名JSON对象。
     * 结构示例：{@code {"invoice":["发票","票据"]}}。
     */
    @Transient
    private String aliasesJson;

    /** Skill的当前运行时状态，以字符串形式存储在数据库中（如LOADED、ENABLED、DISABLED） */
    @Enumerated(EnumType.STRING)
    private SkillState state;

    /** Skill JAR文件在服务器文件系统上的存储路径 */
    private String jarPath;

    /** 记录创建时间，由{@link #prePersist()}自动设置 */
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    /** 记录最后更新时间，由{@link #prePersist()}和{@link #preUpdate()}自动更新 */
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    /**
     * JPA生命周期回调：实体首次持久化前调用。
     * 自动设置创建时间和更新时间。
     */
    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    /**
     * JPA生命周期回调：实体更新前调用。
     * 自动刷新更新时间。
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
