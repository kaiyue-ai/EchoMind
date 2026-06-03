package com.echomind.console.quota;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

@Mapper
public interface TokenQuotaUsageMapper {

    @Insert("""
        INSERT IGNORE INTO echomind_token_quota_usage
            (user_id, scope, bucket_start, used_tokens, created_at, updated_at)
        VALUES
            (#{userId}, #{scope}, #{bucketStart}, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
        """)
    int insertIgnoreBucket(@Param("userId") String userId,
                           @Param("scope") String scope,
                           @Param("bucketStart") LocalDate bucketStart);

    @Select("""
        SELECT used_tokens
        FROM echomind_token_quota_usage
        WHERE user_id = #{userId}
          AND scope = #{scope}
          AND bucket_start = #{bucketStart}
        """)
    Long selectUsedTokens(@Param("userId") String userId,
                          @Param("scope") String scope,
                          @Param("bucketStart") LocalDate bucketStart);

    @Select("""
        SELECT used_tokens
        FROM echomind_token_quota_usage
        WHERE user_id = #{userId}
          AND scope = #{scope}
          AND bucket_start = #{bucketStart}
        FOR UPDATE
        """)
    Long selectUsedTokensForUpdate(@Param("userId") String userId,
                                   @Param("scope") String scope,
                                   @Param("bucketStart") LocalDate bucketStart);

    @Update("""
        UPDATE echomind_token_quota_usage
        SET used_tokens = used_tokens + #{tokens},
            updated_at = CURRENT_TIMESTAMP(6)
        WHERE user_id = #{userId}
          AND scope = #{scope}
          AND bucket_start = #{bucketStart}
        """)
    int incrementUsedTokens(@Param("userId") String userId,
                            @Param("scope") String scope,
                            @Param("bucketStart") LocalDate bucketStart,
                            @Param("tokens") long tokens);

    @Select("""
        SELECT COUNT(*)
        FROM echomind_token_quota_usage
        WHERE user_id = #{userId}
        """)
    long countByUserId(@Param("userId") String userId);

    @Delete("""
        DELETE FROM echomind_token_quota_usage
        WHERE user_id = #{userId}
        """)
    long deleteByUserId(@Param("userId") String userId);
}
