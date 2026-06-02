package com.echomind.common.mybatis;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

/**
 * Common MyBatis-Plus mapper helpers used by runtime persistence stores.
 */
public interface MybatisPlusMapper<T> extends BaseMapper<T> {

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

    default Optional<T> selectOptionalById(Serializable id) {
        return Optional.ofNullable(selectById(id));
    }

    default List<T> selectAll() {
        return selectList(null);
    }

    default long selectCountAll() {
        return selectCount(null);
    }

    default boolean existsById(Serializable id) {
        return selectById(id) != null;
    }

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
