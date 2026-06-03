package com.echomind.skill.marketplace;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.echomind.common.model.SkillState;
import java.time.Instant;

import lombok.Getter;
import lombok.Setter;

/**
 * Skill市场持久化实体，对应数据库表{@code echomind_skills}。
 *
 * <p>此实体存储Skill的完整元数据信息，用于Skill市场的持久化管理。
 * 每个Skill在数据库中有唯一UUID主键，支持按名称和版本的复合唯一查询。</p>
 *
 * <p>主要字段说明：</p>
 * <ul>
 *   <li><b>id</b> — UUID主键，由MyBatis-Plus自动生成</li>
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
 * @author EchoMind Team
 * @see SkillMapper
 * @see MarketplaceService
 */
@TableName("echomind_skills")
@Getter
@Setter
public class SkillEntity {

    /**
     * Skill记录的唯一主键。
     * 使用UUID生成策略，确保分布式环境下的唯一性。
     */
    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    /** Skill名称，不可为空，与version组合唯一标识一个Skill */
    private String name;

    /** Skill版本号（语义化版本格式），不可为空 */
    private String version;

    /** Skill功能的详细描述，支持最长2000字符 */
    private String description;

    /**
     * 参数的JSON Schema定义，以JSON字符串形式存储。
     * 用于描述Skill接受的输入参数的结构和约束，最长4000字符。
     */
    @TableField("parameter_schema_json")
    private String parameterSchemaJson;

    /**
     * Skill依赖项的JSON数组字符串。
     * 记录此Skill依赖的其他库或Skill，最长1000字符。
     */
    @TableField("dependencies_json")
    private String dependenciesJson;

    /** Skill的作者或维护者信息 */
    private String author;

    /**
     * Skill标签的JSON数组字符串。
     * 用于分类和搜索，如{@code ["weather", "api", "forecast"]}，最长1000字符。
     */
    @TableField("tags_json")
    private String tagsJson;

    /**
     * Skill显式声明的触发关键词JSON数组。
     *
     * <p>当前不自动改表，因此该字段只作为运行时/未来迁移预留，不参与 MyBatis 持久化。
     * 工具匹配实际读取的是JAR中{@code SkillMetadata}的运行时元数据。</p>
     */
    @TableField(exist = false)
    private String keywordsJson;

    /**
     * Skill关键词别名JSON对象。
     * 结构示例：{@code {"invoice":["发票","票据"]}}。
     */
    @TableField(exist = false)
    private String aliasesJson;

    /** Skill的当前运行时状态，以字符串形式存储在数据库中（如LOADED、ENABLED、DISABLED） */
    private SkillState state;

    /** Skill JAR文件在服务器文件系统上的存储路径 */
    @TableField("jar_path")
    private String jarPath;

    /** 记录创建时间，由 MyBatis-Plus 自动填充。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    /** 记录最后更新时间，由 MyBatis-Plus 自动填充。 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
