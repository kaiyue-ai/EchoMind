package com.echomind.console.usage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.echomind.common.mybatis.MybatisPlusMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Mapper
public interface AiCallUsageMapper extends MybatisPlusMapper<AiCallUsageEntity> {

    default List<AiCallUsageEntity> findByUserIdAndUsageSourceOrderByCreatedAtDesc(String userId,
                                                                                   TokenUsageSource usageSource,
                                                                                   int limit) {
        return selectList(Wrappers.lambdaQuery(AiCallUsageEntity.class)
            .eq(AiCallUsageEntity::getUserId, userId)
            .eq(AiCallUsageEntity::getUsageSource, usageSource)
            .orderByDesc(AiCallUsageEntity::getCreatedAt)
            .last("limit " + Math.max(1, limit)));
    }

    default List<AiCallUsageEntity> findByUsageSourceOrderByCreatedAtDesc(TokenUsageSource usageSource, int limit) {
        return selectList(Wrappers.lambdaQuery(AiCallUsageEntity.class)
            .eq(AiCallUsageEntity::getUsageSource, usageSource)
            .orderByDesc(AiCallUsageEntity::getCreatedAt)
            .last("limit " + Math.max(1, limit)));
    }

    default List<AiCallUsageEntity> findByUsageSourceAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
        TokenUsageSource usageSource,
        Instant createdAt,
        int limit
    ) {
        return selectList(Wrappers.lambdaQuery(AiCallUsageEntity.class)
            .eq(AiCallUsageEntity::getUsageSource, usageSource)
            .ge(AiCallUsageEntity::getCreatedAt, createdAt)
            .orderByDesc(AiCallUsageEntity::getCreatedAt)
            .last("limit " + Math.max(1, limit)));
    }

    default long countByUserId(String userId) {
        return selectCount(Wrappers.lambdaQuery(AiCallUsageEntity.class)
            .eq(AiCallUsageEntity::getUserId, userId));
    }

    default long countByUsageSourceAndCreatedAtGreaterThanEqual(TokenUsageSource usageSource, Instant createdAt) {
        return selectCount(Wrappers.lambdaQuery(AiCallUsageEntity.class)
            .eq(AiCallUsageEntity::getUsageSource, usageSource)
            .ge(AiCallUsageEntity::getCreatedAt, createdAt));
    }

    default long countByUsageSourceAndStatusAndCreatedAtGreaterThanEqual(TokenUsageSource usageSource,
                                                                        String status,
                                                                        Instant createdAt) {
        return selectCount(Wrappers.lambdaQuery(AiCallUsageEntity.class)
            .eq(AiCallUsageEntity::getUsageSource, usageSource)
            .eq(AiCallUsageEntity::getStatus, status)
            .ge(AiCallUsageEntity::getCreatedAt, createdAt));
    }

    default long deleteByUserId(String userId) {
        return delete(Wrappers.lambdaQuery(AiCallUsageEntity.class)
            .eq(AiCallUsageEntity::getUserId, userId));
    }

    @Select("""
        select coalesce(sum(prompt_tokens), 0) as promptTokens,
               coalesce(sum(completion_tokens), 0) as completionTokens,
               coalesce(sum(total_tokens), 0) as totalTokens,
               count(*) as callCount
        from echomind_ai_call_usage
        where usage_source = 'PROVIDER'
        """)
    Map<String, Object> globalTotalsRow();

    default Object[] globalTotals() {
        return totals(globalTotalsRow());
    }

    @Select("""
        select coalesce(sum(prompt_tokens), 0) as promptTokens,
               coalesce(sum(completion_tokens), 0) as completionTokens,
               coalesce(sum(total_tokens), 0) as totalTokens,
               count(*) as callCount
        from echomind_ai_call_usage
        where user_id = #{userId}
          and usage_source = 'PROVIDER'
        """)
    Map<String, Object> totalsByUserIdRow(@Param("userId") String userId);

    default Object[] totalsByUserId(String userId) {
        return totals(totalsByUserIdRow(userId));
    }

    @Select("""
        select coalesce(sum(total_tokens), 0)
        from echomind_ai_call_usage
        where coalesce(nullif(provider_id, ''), substring_index(model_id, ':', 1)) = #{providerId}
          and created_at >= #{from}
          and usage_source = 'PROVIDER'
        """)
    long totalTokensByProviderIdSince(@Param("providerId") String providerId, @Param("from") Instant from);

    @Select("""
        select distinct coalesce(nullif(provider_id, ''), substring_index(model_id, ':', 1)) as providerId
        from echomind_ai_call_usage
        where usage_source = 'PROVIDER'
          and coalesce(nullif(provider_id, ''), substring_index(model_id, ':', 1)) is not null
          and coalesce(nullif(provider_id, ''), substring_index(model_id, ':', 1)) <> ''
        order by providerId
        """)
    List<String> providerIdsWithUsage();

    @Select("""
        select coalesce(sum(prompt_tokens), 0) as promptTokens,
               coalesce(sum(completion_tokens), 0) as completionTokens,
               coalesce(sum(total_tokens), 0) as totalTokens,
               count(*) as callCount
        from echomind_ai_call_usage
        where created_at >= #{from}
          and usage_source = 'PROVIDER'
        """)
    Map<String, Object> totalsSinceRow(@Param("from") Instant from);

    default Object[] totalsSince(Instant from) {
        return totals(totalsSinceRow(from));
    }

    @Select("""
        select coalesce(avg(duration_ms), 0)
        from echomind_ai_call_usage
        where usage_source = 'PROVIDER'
        """)
    double averageDurationMs();

    @Select("""
        select coalesce(avg(duration_ms), 0)
        from echomind_ai_call_usage
        where created_at >= #{from}
          and usage_source = 'PROVIDER'
        """)
    double averageDurationMsSince(@Param("from") Instant from);

    @Select("""
        select user_id as userId,
               coalesce(sum(prompt_tokens), 0) as promptTokens,
               coalesce(sum(completion_tokens), 0) as completionTokens,
               coalesce(sum(total_tokens), 0) as totalTokens,
               count(*) as callCount
        from echomind_ai_call_usage
        where usage_source = 'PROVIDER'
        group by user_id
        """)
    List<Map<String, Object>> totalsByUserRows();

    default List<Object[]> totalsByUser() {
        return totalsByUserRows().stream()
            .map(row -> new Object[]{
                value(row, "userId"),
                value(row, "promptTokens"),
                value(row, "completionTokens"),
                value(row, "totalTokens"),
                value(row, "callCount")
            })
            .toList();
    }

    @Select("""
        select user_id as userId,
               model_id as modelId,
               coalesce(sum(prompt_tokens), 0) as promptTokens,
               coalesce(sum(completion_tokens), 0) as completionTokens,
               coalesce(sum(total_tokens), 0) as totalTokens,
               count(*) as callCount,
               coalesce(avg(duration_ms), 0) as averageDurationMs
        from echomind_ai_call_usage
        where usage_source = 'PROVIDER'
        group by user_id, model_id
        order by coalesce(sum(total_tokens), 0) desc
        """)
    List<Map<String, Object>> totalsByUserAndModelRows();

    default List<Object[]> totalsByUserAndModel() {
        return totalsByUserAndModelRows().stream()
            .map(row -> new Object[]{
                value(row, "userId"),
                value(row, "modelId"),
                value(row, "promptTokens"),
                value(row, "completionTokens"),
                value(row, "totalTokens"),
                value(row, "callCount"),
                value(row, "averageDurationMs")
            })
            .toList();
    }

    @Select("""
        select model_id as modelId,
               count(*) as callCount,
               coalesce(sum(prompt_tokens), 0) as promptTokens,
               coalesce(sum(completion_tokens), 0) as completionTokens,
               coalesce(sum(total_tokens), 0) as totalTokens,
               coalesce(avg(duration_ms), 0) as averageDurationMs
        from echomind_ai_call_usage
        where created_at >= #{from}
          and usage_source = 'PROVIDER'
        group by model_id
        order by coalesce(sum(total_tokens), 0) desc
        """)
    List<Map<String, Object>> modelTotalsSinceRows(@Param("from") Instant from);

    default List<Object[]> modelTotalsSince(Instant from) {
        return modelTotalsSinceRows(from).stream()
            .map(row -> new Object[]{
                value(row, "modelId"),
                value(row, "callCount"),
                value(row, "promptTokens"),
                value(row, "completionTokens"),
                value(row, "totalTokens"),
                value(row, "averageDurationMs")
            })
            .toList();
    }

    @Select("""
        select date(created_at) as bucket,
               coalesce(sum(prompt_tokens), 0) as promptTokens,
               coalesce(sum(completion_tokens), 0) as completionTokens,
               coalesce(sum(total_tokens), 0) as totalTokens,
               count(*) as callCount,
               coalesce(avg(duration_ms), 0) as averageDurationMs
        from echomind_ai_call_usage
        where created_at >= #{from}
          and usage_source = 'PROVIDER'
        group by date(created_at)
        order by bucket
        """)
    List<Map<String, Object>> dailyTrendSinceRows(@Param("from") Instant from);

    default List<Object[]> dailyTrendSince(Instant from) {
        return dailyTrendSinceRows(from).stream()
            .map(row -> new Object[]{
                value(row, "bucket"),
                value(row, "promptTokens"),
                value(row, "completionTokens"),
                value(row, "totalTokens"),
                value(row, "callCount"),
                value(row, "averageDurationMs")
            })
            .toList();
    }

    private static Object[] totals(Map<String, Object> row) {
        return new Object[]{
            value(row, "promptTokens"),
            value(row, "completionTokens"),
            value(row, "totalTokens"),
            value(row, "callCount")
        };
    }

    private static Object value(Map<String, Object> row, String key) {
        if (row == null || row.isEmpty()) {
            return 0L;
        }
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return 0L;
    }
}
