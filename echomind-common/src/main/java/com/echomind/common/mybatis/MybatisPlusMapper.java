package com.echomind.common.mybatis;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Plus Mapper 基础接口。
 *
 * <p>这里只封装项目常用的 MyBatis 操作语义，命名保持 Mapper 语义。</p>
 */
public interface MybatisPlusMapper<T> extends BaseMapper<T> {

    /**
     * 按主键存在性决定插入或更新。
     *
     * <p>主键为空时直接插入；字符串 UUID 等自动主键由实体上的 {@code @TableId} 策略生成。</p>
     */
    default T upsertById(T entity) {
        Serializable id = tableIdValue(entity);
        if (id == null || (id instanceof String value && value.isBlank())) {
            insert(entity);
            return entity;
        }
        if (selectById(id) == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    /** 按主键查询，返回 Optional 便于业务层表达缺失。 */
    default Optional<T> selectOptionalById(Serializable id) {
        return Optional.ofNullable(selectById(id));
    }

    /** 查询全部记录。 */
    default List<T> selectAll() {
        return selectList(null);
    }

    /** 统计全部记录。 */
    default long selectCountAll() {
        return selectCount(null);
    }

    /** 判断主键是否存在。 */
    default boolean existsById(Serializable id) {
        return selectById(id) != null;
    }

    /** 按实体主键删除。 */
    default void deleteEntity(T entity) {
        Serializable id = tableIdValue(entity);
        if (id != null) {
            deleteById(id);
        }
    }

    private Serializable tableIdValue(T entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity must not be null");
        }
        Field idField = tableIdField(entity.getClass());
        try {
            idField.setAccessible(true);
            Object value = idField.get(entity);
            if (value == null) {
                return null;
            }
            if (!(value instanceof Serializable serializable)) {
                throw new IllegalStateException("TableId field is not Serializable: " + idField.getName());
            }
            return serializable;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read TableId field: " + idField.getName(), e);
        }
    }

    private Field tableIdField(Class<?> type) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(TableId.class)) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        throw new IllegalStateException("Missing @TableId on entity: " + type.getName());
    }
}
