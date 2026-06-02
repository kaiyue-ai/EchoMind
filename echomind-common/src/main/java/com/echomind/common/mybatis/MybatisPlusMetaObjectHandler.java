package com.echomind.common.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.time.Instant;

/**
 * Maintains MyBatis-Plus createdAt and updatedAt audit fields.
 */
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = Instant.now();
        fillIfEmpty(metaObject, "createdAt", now);
        fillIfEmpty(metaObject, "updatedAt", now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasSetter("updatedAt")) {
            setFieldValByName("updatedAt", Instant.now(), metaObject);
        }
    }

    private void fillIfEmpty(MetaObject metaObject, String fieldName, Instant value) {
        if (metaObject.hasSetter(fieldName) && metaObject.getValue(fieldName) == null) {
            setFieldValByName(fieldName, value, metaObject);
        }
    }
}
