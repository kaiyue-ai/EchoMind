package com.echomind.console.quota;

import com.echomind.console.auth.AuthUser;
import com.echomind.console.auth.UserAccountEntity;
import com.echomind.console.auth.UserAccountRepository;
import com.echomind.console.auth.UserAccountStatus;
import com.echomind.console.quota.TokenQuotaDtos.TokenQuotaListResponse;
import com.echomind.console.quota.TokenQuotaDtos.TokenQuotaView;
import com.echomind.console.quota.TokenQuotaDtos.UpdateTokenQuotaRequest;
import com.echomind.console.usage.AiCallUsageRepository;
import com.echomind.console.usage.UsageDtos.TokenTotals;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TokenQuotaService {

    private final TokenQuotaRepository quotaRepository;
    private final UserAccountRepository userRepository;
    private final AiCallUsageRepository usageRepository;
    private final Clock clock = Clock.systemDefaultZone();

    @Transactional(readOnly = true)
    public void assertAllowed(AuthUser user) {
        if (user == null || !user.authenticated()) {
            return;
        }
        quotaRepository.findById(user.userId())
            .filter(quota -> quota.getStatus() == TokenQuotaStatus.ACTIVE)
            .ifPresent(quota -> assertQuota(user.userId(), quota));
    }

    @Transactional(readOnly = true)
    public List<QuotaSignal> warningSignals(AuthUser user) {
        if (user == null || !user.authenticated()) {
            return List.of();
        }
        return quotaRepository.findById(user.userId())
            .filter(quota -> quota.getStatus() == TokenQuotaStatus.ACTIVE)
            .map(quota -> signals(user.userId(), quota))
            .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public TokenQuotaListResponse list() {
        Map<String, TokenQuotaEntity> quotas = new HashMap<>();
        quotaRepository.findAll().forEach(quota -> quotas.put(quota.getUserId(), quota));
        Map<String, TokenTotals> totals = new HashMap<>();
        for (Object[] row : usageRepository.totalsByUser()) {
            totals.put(String.valueOf(row[0]), totals(row, 1));
        }
        return new TokenQuotaListResponse(userRepository.findAll().stream()
            .map(user -> view(user, quotas.get(user.getUserId()), totals.get(user.getUserId())))
            .sorted(Comparator.comparing((TokenQuotaView view) -> view.totalUsedTokens()).reversed()
                .thenComparing(TokenQuotaView::username))
            .toList());
    }

    @Transactional
    public TokenQuotaView update(String userId, UpdateTokenQuotaRequest request) {
        UserAccountEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("客户端用户不存在"));
        TokenQuotaEntity quota = quotaRepository.findById(userId).orElseGet(() -> {
            TokenQuotaEntity created = new TokenQuotaEntity();
            created.setUserId(userId);
            return created;
        });
        quota.setDailyLimitTokens(normalizeLimit(request == null ? null : request.dailyLimitTokens()));
        quota.setMonthlyLimitTokens(normalizeLimit(request == null ? null : request.monthlyLimitTokens()));
        quota.setWarningThresholdPercent(normalizeThreshold(request == null ? null : request.warningThresholdPercent()));
        quota.setStatus(request == null || request.status() == null ? TokenQuotaStatus.ACTIVE : request.status());
        TokenQuotaEntity saved = quotaRepository.save(quota);
        return view(user, saved, totals(usageRepository.totalsByUserId(userId)));
    }

    private void assertQuota(String userId, TokenQuotaEntity quota) {
        long todayUsed = usageRepository.totalTokensByUserIdSince(userId, startOfDay());
        long monthUsed = usageRepository.totalTokensByUserIdSince(userId, startOfMonth());
        if (quota.getDailyLimitTokens() != null && todayUsed >= quota.getDailyLimitTokens()) {
            throw new TokenQuotaExceededException(userId, "daily", todayUsed, quota.getDailyLimitTokens());
        }
        if (quota.getMonthlyLimitTokens() != null && monthUsed >= quota.getMonthlyLimitTokens()) {
            throw new TokenQuotaExceededException(userId, "monthly", monthUsed, quota.getMonthlyLimitTokens());
        }
    }

    private List<QuotaSignal> signals(String userId, TokenQuotaEntity quota) {
        List<QuotaSignal> signals = new ArrayList<>();
        long todayUsed = usageRepository.totalTokensByUserIdSince(userId, startOfDay());
        long monthUsed = usageRepository.totalTokensByUserIdSince(userId, startOfMonth());
        addSignal(signals, "daily", todayUsed, quota.getDailyLimitTokens(), quota.getWarningThresholdPercent());
        addSignal(signals, "monthly", monthUsed, quota.getMonthlyLimitTokens(), quota.getWarningThresholdPercent());
        return signals;
    }

    private void addSignal(List<QuotaSignal> signals, String scope, long used, Long limit, int threshold) {
        if (limit == null || limit <= 0) {
            return;
        }
        double usagePercent = percent(used, limit);
        signals.add(new QuotaSignal(scope, used, limit, usagePercent, warning(usagePercent, threshold),
            exceeded(used, limit)));
    }

    private TokenQuotaView view(UserAccountEntity user, TokenQuotaEntity quota, TokenTotals totals) {
        TokenTotals safeTotals = totals == null ? new TokenTotals(0, 0, 0, 0) : totals;
        Long dailyLimit = quota == null ? null : quota.getDailyLimitTokens();
        Long monthlyLimit = quota == null ? null : quota.getMonthlyLimitTokens();
        int threshold = quota == null ? 80 : quota.getWarningThresholdPercent();
        TokenQuotaStatus status = quota == null ? TokenQuotaStatus.ACTIVE : quota.getStatus();
        long todayUsed = usageRepository.totalTokensByUserIdSince(user.getUserId(), startOfDay());
        long monthUsed = usageRepository.totalTokensByUserIdSince(user.getUserId(), startOfMonth());
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

    private Instant startOfDay() {
        return Instant.now(clock)
            .atZone(ZoneId.systemDefault())
            .truncatedTo(ChronoUnit.DAYS)
            .toInstant();
    }

    private Instant startOfMonth() {
        var now = Instant.now(clock).atZone(ZoneId.systemDefault());
        return now.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();
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

    public record QuotaSignal(
        String scope,
        long usedTokens,
        long limitTokens,
        double usagePercent,
        boolean warning,
        boolean exceeded
    ) {
    }
}
