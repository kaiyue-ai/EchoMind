package com.echomind.console.reservation;

import com.echomind.console.budget.ProviderTokenBudgetEntity;
import com.echomind.console.budget.ProviderTokenBudgetMapper;
import com.echomind.console.budget.ProviderTokenBudgetStatus;
import com.echomind.console.budget.ProviderTokenBudgetUsageMapper;
import com.echomind.console.quota.TokenQuotaEntity;
import com.echomind.console.quota.TokenQuotaMapper;
import com.echomind.console.quota.TokenQuotaStatus;
import com.echomind.console.quota.TokenQuotaUsageMapper;
import com.echomind.console.reservation.TokenReservation.OwnerType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class TokenReservationService {

    private static final ZoneId BUDGET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DAILY = "daily";
    private static final String WEEKLY = "weekly";
    private static final String MONTHLY = "monthly";
    private static final Duration RETAIN_AFTER_BUCKET = Duration.ofHours(48);

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>("""
        local used = tonumber(redis.call('HGET', KEYS[1], 'used') or '0')
        local reserved = tonumber(redis.call('HGET', KEYS[1], 'reserved') or '0')
        local reserve = tonumber(ARGV[1])
        local limit = tonumber(ARGV[2])
        local ttl = tonumber(ARGV[3])
        if used + reserved + reserve > limit then
            return 0
        end
        redis.call('HINCRBY', KEYS[1], 'reserved', reserve)
        redis.call('EXPIRE', KEYS[1], ttl)
        return used + reserved + reserve
        """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
        local reserve = tonumber(ARGV[1])
        local ttl = tonumber(ARGV[2])
        local reserved = tonumber(redis.call('HGET', KEYS[1], 'reserved') or '0')
        local nextReserved = reserved - reserve
        if nextReserved < 0 then
            nextReserved = 0
        end
        redis.call('HSET', KEYS[1], 'reserved', nextReserved)
        redis.call('EXPIRE', KEYS[1], ttl)
        return nextReserved
        """, Long.class);

    private static final DefaultRedisScript<Long> SETTLE_SCRIPT = new DefaultRedisScript<>("""
        local reserve = tonumber(ARGV[1])
        local actual = tonumber(ARGV[2])
        local ttl = tonumber(ARGV[3])
        local used = tonumber(redis.call('HGET', KEYS[1], 'used') or '0')
        local reserved = tonumber(redis.call('HGET', KEYS[1], 'reserved') or '0')
        local nextReserved = reserved - reserve
        if nextReserved < 0 then
            nextReserved = 0
        end
        local nextUsed = used + actual
        redis.call('HSET', KEYS[1], 'reserved', nextReserved, 'used', nextUsed)
        redis.call('EXPIRE', KEYS[1], ttl)
        return nextUsed
        """, Long.class);

    private final StringRedisTemplate redis;
    private final TokenQuotaMapper quotaMapper;
    private final TokenQuotaUsageMapper quotaUsageMapper;
    private final ProviderTokenBudgetMapper providerBudgetMapper;
    private final ProviderTokenBudgetUsageMapper providerUsageMapper;
    private final long defaultReserveTokens;
    private final long reservationTtlSeconds;
    private final Clock clock;

    @Autowired
    public TokenReservationService(ObjectProvider<RedisConnectionFactory> connectionFactoryProvider,
                                   TokenQuotaMapper quotaMapper,
                                   TokenQuotaUsageMapper quotaUsageMapper,
                                   ProviderTokenBudgetMapper providerBudgetMapper,
                                   ProviderTokenBudgetUsageMapper providerUsageMapper,
                                   @Value("${echomind.governance.token-reservation.default-reserve-tokens:4096}")
                                   long defaultReserveTokens,
                                   @Value("${echomind.governance.token-reservation.ttl-seconds:900}")
                                   long reservationTtlSeconds) {
        this(createRedis(connectionFactoryProvider), quotaMapper, quotaUsageMapper, providerBudgetMapper,
            providerUsageMapper, defaultReserveTokens, reservationTtlSeconds, Clock.system(BUDGET_ZONE));
    }

    TokenReservationService(StringRedisTemplate redis,
                            TokenQuotaMapper quotaMapper,
                            TokenQuotaUsageMapper quotaUsageMapper,
                            ProviderTokenBudgetMapper providerBudgetMapper,
                            ProviderTokenBudgetUsageMapper providerUsageMapper,
                            long defaultReserveTokens,
                            long reservationTtlSeconds,
                            Clock clock) {
        this.redis = redis;
        this.quotaMapper = quotaMapper;
        this.quotaUsageMapper = quotaUsageMapper;
        this.providerBudgetMapper = providerBudgetMapper;
        this.providerUsageMapper = providerUsageMapper;
        this.defaultReserveTokens = defaultReserveTokens > 0 ? defaultReserveTokens : 4096;
        this.reservationTtlSeconds = reservationTtlSeconds > 0 ? reservationTtlSeconds : 900;
        this.clock = clock == null ? Clock.system(BUDGET_ZONE) : clock;
    }

    public long defaultReserveTokens() {
        return defaultReserveTokens;
    }

    public List<String> reserveUser(String userId, String requestId, long reserveTokens) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        TokenQuotaEntity quota = quotaMapper.selectOptionalById(userId)
            .filter(entity -> entity.getStatus() == TokenQuotaStatus.ACTIVE)
            .orElse(null);
        if (quota == null) {
            return List.of();
        }
        List<Bucket> buckets = new ArrayList<>(2);
        addBucket(buckets, OwnerType.USER, userId, DAILY, todayBucket(), quota.getDailyLimitTokens());
        addBucket(buckets, OwnerType.USER, userId, MONTHLY, monthBucket(), quota.getMonthlyLimitTokens());
        return reserveBuckets(buckets, reserveTokens);
    }

    public List<String> reserveProvider(String providerId, String requestId, long reserveTokens) {
        if (providerId == null || providerId.isBlank()) {
            return List.of();
        }
        ProviderTokenBudgetEntity budget = providerBudgetMapper.selectOptionalById(providerId)
            .filter(entity -> entity.getStatus() == ProviderTokenBudgetStatus.ACTIVE)
            .orElse(null);
        if (budget == null) {
            return List.of();
        }
        List<Bucket> buckets = new ArrayList<>(3);
        addBucket(buckets, OwnerType.PROVIDER, providerId, DAILY, todayBucket(), budget.getDailyLimitTokens());
        addBucket(buckets, OwnerType.PROVIDER, providerId, WEEKLY, weekBucket(), budget.getWeeklyLimitTokens());
        addBucket(buckets, OwnerType.PROVIDER, providerId, MONTHLY, monthBucket(), budget.getMonthlyLimitTokens());
        return reserveBuckets(buckets, reserveTokens);
    }

    public void settle(List<String> reservationIds, long actualTokens) {
        if (reservationIds == null || reservationIds.isEmpty()) {
            return;
        }
        for (String reservationId : reservationIds) {
            TokenReservation reservation = TokenReservation.parse(reservationId);
            settle(reservation, Math.max(actualTokens, 0));
        }
    }

    public void release(List<String> reservationIds) {
        if (reservationIds == null || reservationIds.isEmpty()) {
            return;
        }
        for (String reservationId : reservationIds) {
            try {
                release(TokenReservation.parse(reservationId));
            } catch (RuntimeException e) {
                log.warn("Failed to release token reservation {}: {}", reservationId, e.getMessage());
            }
        }
    }

    public long usedTokens(OwnerType ownerType, String ownerId, String scope, LocalDate bucketStart) {
        if (redis == null) {
            return mysqlUsed(ownerType, ownerId, scope, bucketStart);
        }
        try {
            String key = bucketKey(ownerType, ownerId, scope, bucketStart);
            String used = redis.<String, String>opsForHash().get(key, "used");
            if (used != null && !used.isBlank()) {
                return Long.parseLong(used);
            }
            long mysqlUsed = mysqlUsed(ownerType, ownerId, scope, bucketStart);
            redis.opsForHash().putIfAbsent(key, "used", String.valueOf(mysqlUsed));
            redis.expire(key, ttlFor(scope, bucketStart));
            return mysqlUsed;
        } catch (RuntimeException e) {
            log.warn("Failed to read Redis token bucket owner={} id={} scope={}: {}",
                ownerType, ownerId, scope, e.getMessage());
            return mysqlUsed(ownerType, ownerId, scope, bucketStart);
        }
    }

    private List<String> reserveBuckets(List<Bucket> buckets, long requestedReserveTokens) {
        if (buckets.isEmpty()) {
            return List.of();
        }
        if (redis == null) {
            throw new TokenReservationUnavailableException("Redis unavailable for token reservation");
        }
        long reserveTokens = requestedReserveTokens > 0 ? requestedReserveTokens : defaultReserveTokens;
        List<TokenReservation> reserved = new ArrayList<>(buckets.size());
        try {
            for (Bucket bucket : buckets) {
                ensureRedisUsed(bucket);
                TokenReservation reservation = TokenReservation.create(bucket.ownerType(), bucket.ownerId(),
                    bucket.scope(), bucket.bucketStart(), reserveTokens);
                Long result = redis.execute(RESERVE_SCRIPT, Collections.singletonList(bucket.key()),
                    String.valueOf(reserveTokens), String.valueOf(bucket.limit()), String.valueOf(ttlSeconds(bucket)));
                if (result == null || result <= 0) {
                    throwExceeded(bucket);
                }
                reserved.add(reservation);
            }
            return reserved.stream().map(TokenReservation::id).toList();
        } catch (RuntimeException e) {
            reserved.forEach(this::releaseQuietly);
            if (e instanceof com.echomind.console.quota.TokenQuotaExceededException
                || e instanceof com.echomind.console.budget.ProviderTokenBudgetExceededException) {
                throw e;
            }
            throw new TokenReservationUnavailableException("Redis token reservation failed", e);
        }
    }

    private void settle(TokenReservation reservation, long actualTokens) {
        try {
            String key = bucketKey(reservation);
            redis.execute(SETTLE_SCRIPT, Collections.singletonList(key),
                String.valueOf(reservation.reservedTokens()), String.valueOf(actualTokens),
                String.valueOf(ttlSeconds(reservation.scope(), reservation.bucketStart())));
        } catch (RuntimeException e) {
            log.warn("Failed to settle Redis token reservation {}: {}", reservation.id(), e.getMessage());
        }
    }

    private void release(TokenReservation reservation) {
        if (redis == null) {
            return;
        }
        redis.execute(RELEASE_SCRIPT, Collections.singletonList(bucketKey(reservation)),
            String.valueOf(reservation.reservedTokens()),
            String.valueOf(ttlSeconds(reservation.scope(), reservation.bucketStart())));
    }

    private void releaseQuietly(TokenReservation reservation) {
        try {
            release(reservation);
        } catch (RuntimeException e) {
            log.warn("Failed to rollback token reservation {}: {}", reservation.id(), e.getMessage());
        }
    }

    private void ensureRedisUsed(Bucket bucket) {
        String used = redis.<String, String>opsForHash().get(bucket.key(), "used");
        if (used == null || used.isBlank()) {
            redis.opsForHash().putIfAbsent(bucket.key(), "used",
                String.valueOf(mysqlUsed(bucket.ownerType(), bucket.ownerId(), bucket.scope(), bucket.bucketStart())));
        }
        redis.expire(bucket.key(), Duration.ofSeconds(ttlSeconds(bucket)));
    }

    private void throwExceeded(Bucket bucket) {
        long used = usedTokens(bucket.ownerType(), bucket.ownerId(), bucket.scope(), bucket.bucketStart());
        if (bucket.ownerType() == OwnerType.USER) {
            throw new com.echomind.console.quota.TokenQuotaExceededException(
                bucket.ownerId(), bucket.scope(), used, bucket.limit());
        }
        throw new com.echomind.console.budget.ProviderTokenBudgetExceededException(
            bucket.ownerId(), bucket.scope(), used, bucket.limit());
    }

    private void addBucket(List<Bucket> buckets, OwnerType ownerType, String ownerId, String scope,
                           LocalDate bucketStart, Long limit) {
        if (limit == null || limit <= 0) {
            return;
        }
        buckets.add(new Bucket(ownerType, ownerId, scope, bucketStart, limit,
            bucketKey(ownerType, ownerId, scope, bucketStart)));
    }

    private long mysqlUsed(OwnerType ownerType, String ownerId, String scope, LocalDate bucketStart) {
        Long used = ownerType == OwnerType.USER
            ? quotaUsageMapper.selectUsedTokens(ownerId, scope, bucketStart)
            : providerUsageMapper.selectUsedTokens(ownerId, scope, bucketStart);
        return used == null ? 0 : used;
    }

    private String bucketKey(TokenReservation reservation) {
        return bucketKey(reservation.ownerType(), reservation.ownerId(), reservation.scope(), reservation.bucketStart());
    }

    private String bucketKey(OwnerType ownerType, String ownerId, String scope, LocalDate bucketStart) {
        String prefix = ownerType == OwnerType.USER ? "echomind:quota:" : "echomind:provider-budget:";
        return prefix + ownerId + ":" + scope + ":" + bucketStart;
    }

    private long ttlSeconds(Bucket bucket) {
        return ttlSeconds(bucket.scope(), bucket.bucketStart());
    }

    private long ttlSeconds(String scope, LocalDate bucketStart) {
        return Math.max(1, ttlFor(scope, bucketStart).getSeconds());
    }

    private Duration ttlFor(String scope, LocalDate bucketStart) {
        LocalDate next = switch (scope) {
            case DAILY -> bucketStart.plusDays(1);
            case WEEKLY -> bucketStart.plusWeeks(1);
            case MONTHLY -> bucketStart.plusMonths(1);
            default -> todayBucket().plusDays(1);
        };
        ZonedDateTime now = Instant.now(clock).atZone(BUDGET_ZONE);
        ZonedDateTime expiresAt = next.atStartOfDay(BUDGET_ZONE).plus(RETAIN_AFTER_BUCKET);
        long seconds = Duration.between(now, expiresAt).getSeconds();
        return Duration.ofSeconds(Math.max(seconds, reservationTtlSeconds));
    }

    private LocalDate todayBucket() {
        return Instant.now(clock).atZone(BUDGET_ZONE).toLocalDate();
    }

    private LocalDate weekBucket() {
        return todayBucket().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private LocalDate monthBucket() {
        return todayBucket().withDayOfMonth(1);
    }

    private static StringRedisTemplate createRedis(ObjectProvider<RedisConnectionFactory> provider) {
        RedisConnectionFactory factory = provider.getIfAvailable();
        if (factory == null) {
            return null;
        }
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    private record Bucket(
        OwnerType ownerType,
        String ownerId,
        String scope,
        LocalDate bucketStart,
        long limit,
        String key
    ) {
    }
}
