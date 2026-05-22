package com.echomind.console.usage;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AiCallUsageRepository extends JpaRepository<AiCallUsageEntity, String> {

    List<AiCallUsageEntity> findByUserIdAndUsageSourceOrderByCreatedAtDesc(String userId, TokenUsageSource usageSource,
                                                                            Pageable pageable);

    List<AiCallUsageEntity> findByUsageSourceOrderByCreatedAtDesc(TokenUsageSource usageSource, Pageable pageable);

    List<AiCallUsageEntity> findByUsageSourceAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
        TokenUsageSource usageSource,
        Instant createdAt,
        Pageable pageable
    );

    long countByUserId(String userId);

    long countByUsageSourceAndCreatedAtGreaterThanEqual(TokenUsageSource usageSource, Instant createdAt);

    long countByUsageSourceAndStatusAndCreatedAtGreaterThanEqual(TokenUsageSource usageSource, String status,
                                                                 Instant createdAt);

    long deleteByUserId(String userId);

    @Query("""
        select coalesce(sum(u.promptTokens), 0),
               coalesce(sum(u.completionTokens), 0),
               coalesce(sum(u.totalTokens), 0),
               count(u)
        from AiCallUsageEntity u
        where u.usageSource = com.echomind.console.usage.TokenUsageSource.PROVIDER
        """)
    Object[] globalTotals();

    @Query("""
        select coalesce(sum(u.promptTokens), 0),
               coalesce(sum(u.completionTokens), 0),
               coalesce(sum(u.totalTokens), 0),
               count(u)
        from AiCallUsageEntity u
        where u.userId = :userId
          and u.usageSource = com.echomind.console.usage.TokenUsageSource.PROVIDER
        """)
    Object[] totalsByUserId(@Param("userId") String userId);

    @Query("""
        select coalesce(sum(u.totalTokens), 0)
        from AiCallUsageEntity u
        where u.userId = :userId
          and u.createdAt >= :from
          and u.usageSource = com.echomind.console.usage.TokenUsageSource.PROVIDER
        """)
    long totalTokensByUserIdSince(@Param("userId") String userId, @Param("from") Instant from);

    @Query("""
        select coalesce(sum(u.promptTokens), 0),
               coalesce(sum(u.completionTokens), 0),
               coalesce(sum(u.totalTokens), 0),
               count(u)
        from AiCallUsageEntity u
        where u.createdAt >= :from
          and u.usageSource = com.echomind.console.usage.TokenUsageSource.PROVIDER
        """)
    Object[] totalsSince(@Param("from") Instant from);

    @Query("""
        select coalesce(avg(u.durationMs), 0)
        from AiCallUsageEntity u
        where u.usageSource = com.echomind.console.usage.TokenUsageSource.PROVIDER
        """)
    double averageDurationMs();

    @Query("""
        select coalesce(avg(u.durationMs), 0)
        from AiCallUsageEntity u
        where u.createdAt >= :from
          and u.usageSource = com.echomind.console.usage.TokenUsageSource.PROVIDER
        """)
    double averageDurationMsSince(@Param("from") Instant from);

    @Query("""
        select u.userId,
               coalesce(sum(u.promptTokens), 0),
               coalesce(sum(u.completionTokens), 0),
               coalesce(sum(u.totalTokens), 0),
               count(u)
        from AiCallUsageEntity u
        where u.usageSource = com.echomind.console.usage.TokenUsageSource.PROVIDER
        group by u.userId
        """)
    List<Object[]> totalsByUser();

    @Query("""
        select u.userId,
               u.modelId,
               coalesce(sum(u.promptTokens), 0),
               coalesce(sum(u.completionTokens), 0),
               coalesce(sum(u.totalTokens), 0),
               count(u),
               coalesce(avg(u.durationMs), 0)
        from AiCallUsageEntity u
        where u.usageSource = com.echomind.console.usage.TokenUsageSource.PROVIDER
        group by u.userId, u.modelId
        order by coalesce(sum(u.totalTokens), 0) desc
        """)
    List<Object[]> totalsByUserAndModel();

    @Query("""
        select u.modelId,
               count(u),
               coalesce(sum(u.promptTokens), 0),
               coalesce(sum(u.completionTokens), 0),
               coalesce(sum(u.totalTokens), 0),
               coalesce(avg(u.durationMs), 0)
        from AiCallUsageEntity u
        where u.createdAt >= :from
          and u.usageSource = com.echomind.console.usage.TokenUsageSource.PROVIDER
        group by u.modelId
        order by coalesce(sum(u.totalTokens), 0) desc
        """)
    List<Object[]> modelTotalsSince(@Param("from") Instant from);

    @Query(value = """
        select date(u.created_at) as bucket,
               coalesce(sum(u.prompt_tokens), 0) as prompt_tokens,
               coalesce(sum(u.completion_tokens), 0) as completion_tokens,
               coalesce(sum(u.total_tokens), 0) as total_tokens,
               count(*) as call_count,
               coalesce(avg(u.duration_ms), 0) as average_duration_ms
        from echomind_ai_call_usage u
        where u.created_at >= :from
          and u.usage_source = 'PROVIDER'
        group by date(u.created_at)
        order by bucket
        """, nativeQuery = true)
    List<Object[]> dailyTrendSince(@Param("from") Instant from);
}
