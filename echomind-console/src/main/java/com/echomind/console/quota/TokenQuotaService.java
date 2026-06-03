package com.echomind.console.quota;

import com.echomind.console.auth.AuthUser;
import com.echomind.console.auth.UserAccountEntity;
import com.echomind.console.auth.UserAccountMapper;
import com.echomind.console.auth.UserAccountStatus;
import com.echomind.console.quota.TokenQuotaDtos.TokenQuotaListResponse;
import com.echomind.console.quota.TokenQuotaDtos.TokenQuotaView;
import com.echomind.console.quota.TokenQuotaDtos.UpdateTokenQuotaRequest;
import com.echomind.console.usage.AiCallUsageMapper;
import com.echomind.console.usage.UsageDtos.TokenTotals;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TokenQuotaService {

    private static final String DAILY = "daily";
    private static final String MONTHLY = "monthly";

    private final TokenQuotaMapper quotaMapper;
    private final UserAccountMapper userMapper;
    private final AiCallUsageMapper usageMapper;
    private final TokenQuotaUsageMapper quotaUsageMapper;
    private final Clock clock;

    @Autowired
    public TokenQuotaService(TokenQuotaMapper quotaMapper,
                             UserAccountMapper userMapper,
                             AiCallUsageMapper usageMapper,
                             TokenQuotaUsageMapper quotaUsageMapper) {
        this(quotaMapper, userMapper, usageMapper, quotaUsageMapper, Clock.systemDefaultZone());
    }

    TokenQuotaService(TokenQuotaMapper quotaMapper,
                      UserAccountMapper userMapper,
                      AiCallUsageMapper usageMapper,
                      TokenQuotaUsageMapper quotaUsageMapper,
                      Clock clock) {
        this.quotaMapper = quotaMapper;
        this.userMapper = userMapper;
        this.usageMapper = usageMapper;
        this.quotaUsageMapper = quotaUsageMapper;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Transactional(readOnly = true)
    public void assertAllowed(AuthUser user) {
        if (user == null || !user.authenticated()) {
            return;
        }
        quotaMapper.selectOptionalById(user.userId())
            .filter(quota -> quota.getStatus() == TokenQuotaStatus.ACTIVE)
            .ifPresent(quota -> assertQuota(user.userId(), quota));
    }

    @Transactional(readOnly = true)
    public TokenQuotaListResponse list() {
        Map<String, TokenQuotaEntity> quotas = new HashMap<>();
        quotaMapper.selectAll().forEach(quota -> quotas.put(quota.getUserId(), quota));
        Map<String, TokenTotals> totals = new HashMap<>();
        for (Object[] row : usageMapper.totalsByUser()) {
            totals.put(String.valueOf(row[0]), totals(row, 1));
        }
        return new TokenQuotaListResponse(userMapper.selectAll().stream()
            .map(user -> view(user, quotas.get(user.getUserId()), totals.get(user.getUserId())))
            .sorted(Comparator.comparing((TokenQuotaView view) -> view.totalUsedTokens()).reversed()
                .thenComparing(TokenQuotaView::username))
            .toList());
    }

    @Transactional
    public TokenQuotaView update(String userId, UpdateTokenQuotaRequest request) {
        UserAccountEntity user = userMapper.selectOptionalById(userId)
            .orElseThrow(() -> new IllegalArgumentException("客户端用户不存在"));
        TokenQuotaEntity quota = quotaMapper.selectOptionalById(userId).orElseGet(() -> {
            TokenQuotaEntity created = new TokenQuotaEntity();
            created.setUserId(userId);
            return created;
        });
        quota.setDailyLimitTokens(normalizeLimit(request == null ? null : request.dailyLimitTokens()));
        quota.setMonthlyLimitTokens(normalizeLimit(request == null ? null : request.monthlyLimitTokens()));
        quota.setWarningThresholdPercent(normalizeThreshold(request == null ? null : request.warningThresholdPercent()));
        quota.setStatus(request == null || request.status() == null ? TokenQuotaStatus.ACTIVE : request.status());
        TokenQuotaEntity saved = quotaMapper.upsertById(quota);
        return view(user, saved, totals(usageMapper.totalsByUserId(userId)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleUsage(AuthUser user, long currentCallTokens) {
        if (user == null || !user.authenticated() || currentCallTokens <= 0) {
            return;
        }
        quotaMapper.selectOptionalById(user.userId())
            .filter(quota -> quota.getStatus() == TokenQuotaStatus.ACTIVE)
            .ifPresent(quota -> settleQuota(user.userId(), quota, currentCallTokens));
    }

    private void assertQuota(String userId, TokenQuotaEntity quota) {
        long todayUsed = usedTokens(userId, DAILY, todayBucket());
        long monthUsed = usedTokens(userId, MONTHLY, monthBucket());
        if (quota.getDailyLimitTokens() != null && todayUsed >= quota.getDailyLimitTokens()) {
            throw new TokenQuotaExceededException(userId, DAILY, todayUsed, quota.getDailyLimitTokens());
        }
        if (quota.getMonthlyLimitTokens() != null && monthUsed >= quota.getMonthlyLimitTokens()) {
            throw new TokenQuotaExceededException(userId, MONTHLY, monthUsed, quota.getMonthlyLimitTokens());
        }
    }

    private void settleQuota(String userId, TokenQuotaEntity quota, long currentCallTokens) {
        List<SettlementBucket> buckets = new ArrayList<>(2);
        addLockedBucket(buckets, userId, DAILY, todayBucket(), quota.getDailyLimitTokens(), currentCallTokens);
        addLockedBucket(buckets, userId, MONTHLY, monthBucket(), quota.getMonthlyLimitTokens(), currentCallTokens);
        for (SettlementBucket bucket : buckets) {
            quotaUsageMapper.incrementUsedTokens(userId, bucket.scope(), bucket.bucketStart(), currentCallTokens);
        }
    }

    private void addLockedBucket(List<SettlementBucket> buckets, String userId, String scope, LocalDate bucketStart,
                                 Long limit, long currentCallTokens) {
        if (limit == null || limit <= 0) {
            return;
        }
        quotaUsageMapper.insertIgnoreBucket(userId, scope, bucketStart);
        long used = lockedUsedTokens(userId, scope, bucketStart);
        long nextUsed = used + currentCallTokens;
        if (nextUsed > limit) {
            throw new TokenQuotaExceededException(userId, scope, nextUsed, limit);
        }
        buckets.add(new SettlementBucket(scope, bucketStart));
    }

    private TokenQuotaView view(UserAccountEntity user, TokenQuotaEntity quota, TokenTotals totals) {
        TokenTotals safeTotals = totals == null ? new TokenTotals(0, 0, 0, 0) : totals;
        Long dailyLimit = quota == null ? null : quota.getDailyLimitTokens();
        Long monthlyLimit = quota == null ? null : quota.getMonthlyLimitTokens();
        int threshold = quota == null ? 80 : quota.getWarningThresholdPercent();
        TokenQuotaStatus status = quota == null ? TokenQuotaStatus.ACTIVE : quota.getStatus();
        long todayUsed = usedTokens(user.getUserId(), DAILY, todayBucket());
        long monthUsed = usedTokens(user.getUserId(), MONTHLY, monthBucket());
        double dailyPercent = percent(todayUsed, dailyLimit);
        double monthlyPercent = percent(monthUsed, monthlyLimit);
        return new TokenQuotaView(
            user.getUserId(),
            user.getUsername(),
            user.getStatus() == UserAccountStatus.ACTIVE,
            dailyLimit,
            monthlyLimit,
            threshold,
            status,
            todayUsed,
            monthUsed,
            safeTotals.totalTokens(),
            safeTotals.callCount(),
            dailyPercent,
            monthlyPercent,
            exceeded(todayUsed, dailyLimit),
            exceeded(monthUsed, monthlyLimit),
            warning(dailyPercent, threshold),
            warning(monthlyPercent, threshold),
            quota == null ? null : quota.getUpdatedAt()
        );
    }

    private LocalDate todayBucket() {
        return Instant.now(clock).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private LocalDate monthBucket() {
        return todayBucket().withDayOfMonth(1);
    }

    private long usedTokens(String userId, String scope, LocalDate bucketStart) {
        Long used = quotaUsageMapper.selectUsedTokens(userId, scope, bucketStart);
        return used == null ? 0 : used;
    }

    private long lockedUsedTokens(String userId, String scope, LocalDate bucketStart) {
        Long used = quotaUsageMapper.selectUsedTokensForUpdate(userId, scope, bucketStart);
        return used == null ? 0 : used;
    }

    private Long normalizeLimit(Long value) {
        return value == null || value <= 0 ? null : value;
    }

    private int normalizeThreshold(Integer value) {
        if (value == null) {
            return 80;
        }
        return Math.max(1, Math.min(100, value));
    }

    private boolean exceeded(long used, Long limit) {
        return limit != null && used >= limit;
    }

    private boolean warning(double percent, int threshold) {
        return percent >= threshold && percent < 100;
    }

    private double percent(long used, Long limit) {
        if (limit == null || limit <= 0) {
            return 0;
        }
        return Math.min(999, (used * 100.0) / limit);
    }

    private TokenTotals totals(Object[] row) {
        return totals(row, 0);
    }

    private TokenTotals totals(Object[] row, int offset) {
        if (row != null && row.length == 1 && row[0] instanceof Object[] nested) {
            return totals(nested, offset);
        }
        if (row == null || row.length < offset + 4) {
            return new TokenTotals(0, 0, 0, 0);
        }
        return new TokenTotals(
            number(row[offset]),
            number(row[offset + 1]),
            number(row[offset + 2]),
            number(row[offset + 3])
        );
    }

    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0 : Long.parseLong(String.valueOf(value));
    }

    private record SettlementBucket(String scope, LocalDate bucketStart) {
    }
}
