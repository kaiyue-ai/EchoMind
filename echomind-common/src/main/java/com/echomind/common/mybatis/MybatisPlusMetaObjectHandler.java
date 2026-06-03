package com.echomind.common.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.time.Instant;

/**
 * 统一填充 MySQL 审计时间字段。
 *
 * <p>实体的 {@code createdAt/updatedAt} 由 MyBatis-Plus 插入和更新钩子维护。</p>
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
