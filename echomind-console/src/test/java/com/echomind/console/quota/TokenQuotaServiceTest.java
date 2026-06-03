package com.echomind.console.quota;

import com.echomind.console.auth.AuthUser;
import com.echomind.console.auth.UserAccountEntity;
import com.echomind.console.auth.UserAccountMapper;
import com.echomind.console.usage.AiCallUsageMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TokenQuotaServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-06-03T10:15:30Z"),
        ZoneId.of("Asia/Shanghai")
    );

    @Test
    void allowsUserWhenQuotaIsNotConfigured() {
        TokenQuotaMapper quotaMapper = mock(TokenQuotaMapper.class);
        when(quotaMapper.selectOptionalById("user-a")).thenReturn(Optional.empty());
        AiCallUsageMapper usageMapper = mock(AiCallUsageMapper.class);
        TokenQuotaUsageMapper quotaUsageMapper = mock(TokenQuotaUsageMapper.class);
        TokenQuotaService service = new TokenQuotaService(
            quotaMapper,
            mock(UserAccountMapper.class),
            usageMapper,
            quotaUsageMapper,
            FIXED_CLOCK
        );

        service.assertAllowed(new AuthUser("user-a", "alice", true));

        verifyNoInteractions(usageMapper);
        verifyNoInteractions(quotaUsageMapper);
    }

    @Test
    void blocksUserWhenDailyQuotaIsReached() {
        TokenQuotaEntity quota = new TokenQuotaEntity();
        quota.setUserId("user-a");
        quota.setDailyLimitTokens(100L);
        quota.setMonthlyLimitTokens(1000L);
        quota.setStatus(TokenQuotaStatus.ACTIVE);

        TokenQuotaMapper quotaMapper = mock(TokenQuotaMapper.class);
        when(quotaMapper.selectOptionalById("user-a")).thenReturn(Optional.of(quota));
        AiCallUsageMapper usageMapper = mock(AiCallUsageMapper.class);
        TokenQuotaUsageMapper quotaUsageMapper = mock(TokenQuotaUsageMapper.class);
        LocalDate today = LocalDate.of(2026, 6, 3);
        when(quotaUsageMapper.selectUsedTokens("user-a", "daily", today)).thenReturn(100L);
        TokenQuotaService service = new TokenQuotaService(
            quotaMapper,
            mock(UserAccountMapper.class),
            usageMapper,
            quotaUsageMapper,
            FIXED_CLOCK
        );

        assertThatThrownBy(() -> service.assertAllowed(new AuthUser("user-a", "alice", true)))
            .isInstanceOf(TokenQuotaExceededException.class)
            .hasMessageContaining("daily");

        verifyNoInteractions(usageMapper);
    }

    @Test
    void settlesUsageIntoDailyAndMonthlyBuckets() {
        TokenQuotaEntity quota = activeQuota("user-a", 100L, 1000L);
        TokenQuotaMapper quotaMapper = mock(TokenQuotaMapper.class);
        when(quotaMapper.selectOptionalById("user-a")).thenReturn(Optional.of(quota));
        TokenQuotaUsageMapper quotaUsageMapper = mock(TokenQuotaUsageMapper.class);
        LocalDate today = LocalDate.of(2026, 6, 3);
        LocalDate month = LocalDate.of(2026, 6, 1);
        when(quotaUsageMapper.selectUsedTokensForUpdate("user-a", "daily", today)).thenReturn(60L);
        when(quotaUsageMapper.selectUsedTokensForUpdate("user-a", "monthly", month)).thenReturn(600L);
        TokenQuotaService service = new TokenQuotaService(
            quotaMapper,
            mock(UserAccountMapper.class),
            mock(AiCallUsageMapper.class),
            quotaUsageMapper,
            FIXED_CLOCK
        );

        service.settleUsage(new AuthUser("user-a", "alice", true), 30);

        verify(quotaUsageMapper).insertIgnoreBucket("user-a", "daily", today);
        verify(quotaUsageMapper).insertIgnoreBucket("user-a", "monthly", month);
        verify(quotaUsageMapper).incrementUsedTokens("user-a", "daily", today, 30);
        verify(quotaUsageMapper).incrementUsedTokens("user-a", "monthly", month, 30);
    }

    @Test
    void rejectsSettlementWhenCurrentCallWouldExceedLimit() {
        TokenQuotaEntity quota = activeQuota("user-a", 100L, 1000L);
        TokenQuotaMapper quotaMapper = mock(TokenQuotaMapper.class);
        when(quotaMapper.selectOptionalById("user-a")).thenReturn(Optional.of(quota));
        TokenQuotaUsageMapper quotaUsageMapper = mock(TokenQuotaUsageMapper.class);
        LocalDate today = LocalDate.of(2026, 6, 3);
        when(quotaUsageMapper.selectUsedTokensForUpdate("user-a", "daily", today)).thenReturn(90L);
        TokenQuotaService service = new TokenQuotaService(
            quotaMapper,
            mock(UserAccountMapper.class),
            mock(AiCallUsageMapper.class),
            quotaUsageMapper,
            FIXED_CLOCK
        );

        assertThatThrownBy(() -> service.settleUsage(new AuthUser("user-a", "alice", true), 20))
            .isInstanceOf(TokenQuotaExceededException.class)
            .hasMessageContaining("daily used=110 limit=100");

        verify(quotaUsageMapper, never()).incrementUsedTokens(any(), any(), any(), any(Long.class));
    }

    @Test
    void concurrentSettlementKeepsBucketWithinLimit() throws Exception {
        TokenQuotaEntity quota = activeQuota("user-a", 100L, null);
        TokenQuotaMapper quotaMapper = mock(TokenQuotaMapper.class);
        when(quotaMapper.selectOptionalById("user-a")).thenReturn(Optional.of(quota));
        InMemoryQuotaUsageMapper quotaUsageMapper = new InMemoryQuotaUsageMapper();
        TokenQuotaService service = new TokenQuotaService(
            quotaMapper,
            mock(UserAccountMapper.class),
            mock(AiCallUsageMapper.class),
            quotaUsageMapper,
            FIXED_CLOCK
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Callable<Boolean> settlement = () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                service.settleUsage(new AuthUser("user-a", "alice", true), 60);
                return true;
            } catch (TokenQuotaExceededException e) {
                return false;
            }
        };

        Future<Boolean> first = executor.submit(settlement);
        Future<Boolean> second = executor.submit(settlement);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        long successes = (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
            + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
        executor.shutdownNow();

        assertThat(successes).isEqualTo(1);
        assertThat(quotaUsageMapper.used("user-a", "daily", LocalDate.of(2026, 6, 3))).isEqualTo(60);
    }

    @Test
    void listShowsSettledBucketUsageAndAuditTotals() {
        TokenQuotaEntity quota = activeQuota("user-a", 100L, 1000L);
        TokenQuotaMapper quotaMapper = mock(TokenQuotaMapper.class);
        when(quotaMapper.selectAll()).thenReturn(List.of(quota));
        UserAccountEntity user = new UserAccountEntity();
        user.setUserId("user-a");
        user.setUsername("alice");
        user.setStatus(com.echomind.console.auth.UserAccountStatus.ACTIVE);
        UserAccountMapper userMapper = mock(UserAccountMapper.class);
        when(userMapper.selectAll()).thenReturn(List.of(user));
        AiCallUsageMapper usageMapper = mock(AiCallUsageMapper.class);
        when(usageMapper.totalsByUser()).thenReturn(List.<Object[]>of(new Object[]{"user-a", 10L, 5L, 15L, 1L}));
        TokenQuotaUsageMapper quotaUsageMapper = mock(TokenQuotaUsageMapper.class);
        when(quotaUsageMapper.selectUsedTokens("user-a", "daily", LocalDate.of(2026, 6, 3))).thenReturn(90L);
        when(quotaUsageMapper.selectUsedTokens("user-a", "monthly", LocalDate.of(2026, 6, 1))).thenReturn(300L);
        TokenQuotaService service = new TokenQuotaService(
            quotaMapper,
            userMapper,
            usageMapper,
            quotaUsageMapper,
            FIXED_CLOCK
        );

        TokenQuotaDtos.TokenQuotaView view = service.list().users().get(0);

        assertThat(view.todayUsedTokens()).isEqualTo(90);
        assertThat(view.monthUsedTokens()).isEqualTo(300);
        assertThat(view.totalUsedTokens()).isEqualTo(15);
        assertThat(view.dailyWarning()).isTrue();
    }

    private TokenQuotaEntity activeQuota(String userId, Long dailyLimit, Long monthlyLimit) {
        TokenQuotaEntity quota = new TokenQuotaEntity();
        quota.setUserId(userId);
        quota.setDailyLimitTokens(dailyLimit);
        quota.setMonthlyLimitTokens(monthlyLimit);
        quota.setStatus(TokenQuotaStatus.ACTIVE);
        return quota;
    }

    private static final class InMemoryQuotaUsageMapper implements TokenQuotaUsageMapper {
        private final Map<String, Long> buckets = new HashMap<>();
        private final Map<String, ReentrantLock> locks = new HashMap<>();

        @Override
        public synchronized int insertIgnoreBucket(String userId, String scope, LocalDate bucketStart) {
            buckets.putIfAbsent(key(userId, scope, bucketStart), 0L);
            return 0;
        }

        @Override
        public synchronized Long selectUsedTokens(String userId, String scope, LocalDate bucketStart) {
            return buckets.get(key(userId, scope, bucketStart));
        }

        @Override
        public Long selectUsedTokensForUpdate(String userId, String scope, LocalDate bucketStart) {
            String key = key(userId, scope, bucketStart);
            lock(key).lock();
            synchronized (this) {
                return buckets.get(key);
            }
        }

        @Override
        public int incrementUsedTokens(String userId, String scope, LocalDate bucketStart, long tokens) {
            String key = key(userId, scope, bucketStart);
            try {
                synchronized (this) {
                    buckets.merge(key, tokens, Long::sum);
                    return 1;
                }
            } finally {
                lock(key).unlock();
            }
        }

        @Override
        public synchronized long countByUserId(String userId) {
            String prefix = userId + "|";
            return buckets.keySet().stream().filter(key -> key.startsWith(prefix)).count();
        }

        @Override
        public synchronized long deleteByUserId(String userId) {
            long count = countByUserId(userId);
            buckets.keySet().removeIf(key -> key.startsWith(userId + "|"));
            return count;
        }

        synchronized long used(String userId, String scope, LocalDate bucketStart) {
            return buckets.getOrDefault(key(userId, scope, bucketStart), 0L);
        }

        private String key(String userId, String scope, LocalDate bucketStart) {
            return userId + "|" + scope + "|" + bucketStart;
        }

        private synchronized ReentrantLock lock(String key) {
            return locks.computeIfAbsent(key, ignored -> new ReentrantLock());
        }
    }
}
