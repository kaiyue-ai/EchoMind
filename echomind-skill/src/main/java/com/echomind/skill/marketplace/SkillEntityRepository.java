package com.echomind.skill.marketplace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Skill市场数据访问层，基于Spring Data JPA提供{@link SkillRepository}实体的CRUD操作。
 *
 * <p>继承{@link JpaRepository}获得标准的增删改查能力（findAll、findById、save、deleteById等）。
 * 通过方法命名约定定义自定义查询，Spring Data自动生成实现。</p>
 *
 * <p>查询方法说明：</p>
 * <ul>
 *   <li>{@link #findByNameAndVersion(String, String)} — 按名称和版本精确查询，用于去重检查</li>
 *   <li>{@link #findByName(String)} — 按名称查询所有版本，支持同一Skill的多版本共存</li>
 *   <li>{@link #findByState(String)} — 按运行时状态过滤，用于获取启用/禁用/已加载的Skill列表</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see SkillRepository
 * @see MarketplaceService
 */
@Repository
public interface SkillEntityRepository extends JpaRepository<SkillRepository, String> {

    /**
     * 按名称和版本精确查找Skill。
     * 用于在市场中检查Skill是否已存在（防止重复上传）。
     *
     * @param name    Skill名称
     * @param version Skill版本号
     * @return 匹配的Skill实体，如果未找到则为空
     */
    Optional<SkillRepository> findByNameAndVersion(String name, String version);

    /**
     * 按名称查找所有版本的Skill。
     * 同一名称的Skill可能有多个版本共存。
     *
     * @param name Skill名称
     * @return 所有匹配名称的Skill实体列表
     */
    List<SkillRepository> findByName(String name);

    /**
     * 按运行时状态查找Skill。
     * 状态值对应{@link com.echomind.common.model.SkillState}枚举的名称。
     *
     * @param state 状态字符串（如"ENABLED"、"DISABLED"）
     * @return 匹配状态的Skill实体列表
     */
    List<SkillRepository> findByState(String state);
}
