package com.echomind.console.budget;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

@Mapper
public interface ProviderTokenBudgetUsageMapper {

    @Insert("""
        INSERT IGNORE INTO echomind_provider_token_budget_usage
            (provider_id, scope, bucket_start, used_tokens, created_at, updated_at)
        VALUES
            (#{providerId}, #{scope}, #{bucketStart}, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
        """)
    int insertIgnoreBucket(@Param("providerId") String providerId,
                           @Param("scope") String scope,
                           @Param("bucketStart") LocalDate bucketStart);

    @Select("""
        SELECT used_tokens
        FROM echomind_provider_token_budget_usage
        WHERE provider_id = #{providerId}
          AND scope = #{scope}
          AND bucket_start = #{bucketStart}
        """)
    Long selectUsedTokens(@Param("providerId") String providerId,
                          @Param("scope") String scope,
                          @Param("bucketStart") LocalDate bucketStart);

    @Update("""
        UPDATE echomind_provider_token_budget_usage
        SET used_tokens = used_tokens + #{tokens},
            updated_at = CURRENT_TIMESTAMP(6)
        WHERE provider_id = #{providerId}
          AND scope = #{scope}
          AND bucket_start = #{bucketStart}
        """)
    int incrementUsedTokens(@Param("providerId") String providerId,
                            @Param("scope") String scope,
                            @Param("bucketStart") LocalDate bucketStart,
                            @Param("tokens") long tokens);
}
