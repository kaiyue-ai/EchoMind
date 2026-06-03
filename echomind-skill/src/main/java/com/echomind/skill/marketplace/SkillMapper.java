package com.echomind.skill.marketplace;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/**
 * Skill市场数据访问层，基于 MyBatis-Plus 提供{@link SkillEntity}实体的CRUD操作。
 *
 * <p>继承项目通用 Mapper 获得标准的增删改查能力（findAll、findById、save、deleteById等）。</p>
 *
 * @author EchoMind Team
 * @see SkillEntity
 * @see MarketplaceService
 */
@Mapper
public interface SkillMapper extends MybatisPlusMapper<SkillEntity> {

    /**
     * 按名称和版本精确查找Skill。
     * 用于在市场中检查Skill是否已存在（防止重复上传）。
     *
     * @param name    Skill名称
     * @param version Skill版本号
     * @return 匹配的Skill实体，如果未找到则为空
     */
    default Optional<SkillEntity> selectByNameAndVersion(String name, String version) {
        return Optional.ofNullable(selectOne(Wrappers.lambdaQuery(SkillEntity.class)
            .eq(SkillEntity::getName, name)
            .eq(SkillEntity::getVersion, version)
            .last("limit 1")));
    }
}
