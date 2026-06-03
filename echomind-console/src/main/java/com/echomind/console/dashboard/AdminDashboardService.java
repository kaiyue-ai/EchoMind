package com.echomind.console.dashboard;

import com.echomind.console.auth.UserAccountMapper;
import com.echomind.console.auth.UserAccountStatus;
import com.echomind.console.dashboard.AdminDashboardDtos.DashboardResponse;
import com.echomind.console.dashboard.AdminDashboardDtos.DashboardSummary;
import com.echomind.console.dashboard.AdminDashboardDtos.ModelDistribution;
import com.echomind.console.dashboard.AdminDashboardDtos.TokenTrendPoint;
import com.echomind.console.alerts.AlertEventMapper;
import com.echomind.console.sensitive.SensitiveEventMapper;
import com.echomind.console.usage.AiCallUsageEntity;
import com.echomind.console.usage.AiCallUsageMapper;
import com.echomind.console.usage.TokenUsageSource;
import com.echomind.console.usage.UsageDtos.CallUsage;
import com.echomind.console.usage.UsageDtos.TokenTotals;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final AiCallUsageMapper usageMapper;
    private final UserAccountMapper userMapper;
    private final SensitiveEventMapper SensitiveEventMapper;
    private final AlertEventMapper AlertEventMapper;
    private final Clock clock = Clock.systemDefaultZone();

    @Transactional(readOnly = true)
    public DashboardResponse dashboard(String range) {
        Instant rangeStart = rangeStart(range);
        Instant todayStart = startOfDay();
        TokenTotals totalTokens = totals(usageMapper.globalTotals());
        TokenTotals rangeTokens = totals(usageMapper.totalsSince(rangeStart));
        TokenTotals todayTokens = totals(usageMapper.totalsSince(todayStart));
        long rangeErrors = usageMapper.countByUsageSourceAndStatusAndCreatedAtGreaterThanEqual(
            TokenUsageSource.PROVIDER, "ERROR", rangeStart);
        double rangeErrorRate = rangeTokens.callCount() == 0 ? 0 : (rangeErrors * 100.0) / rangeTokens.callCount();
        DashboardSummary summary = new DashboardSummary(
            totalTokens,
            rangeTokens,
            todayTokens,
            userMapper.selectCountAll(),
            userMapper.countByStatus(UserAccountStatus.ACTIVE),
            userMapper.countByStatus(UserAccountStatus.DISABLED),
            rangeTokens.callCount(),
            todayTokens.callCount(),
            totalTokens.callCount(),
            usageMapper.averageDurationMs(),
            usageMapper.averageDurationMsSince(rangeStart),
            rangeErrorRate,
            SensitiveEventMapper.countByCreatedAtGreaterThanEqual(rangeStart),
            AlertEventMapper.countByCreatedAtGreaterThanEqual(rangeStart)
        );
        List<ModelDistribution> modelDistribution = usageMapper.modelTotalsSince(rangeStart).stream()
            .map(this::modelDistribution)
            .toList();
        List<TokenTrendPoint> tokenTrend = usageMapper.dailyTrendSince(rangeStart).stream()
            .map(this::trendPoint)
            .toList();
        List<CallUsage> recentCalls = usageMapper
            .findByUsageSourceAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                TokenUsageSource.PROVIDER,
                rangeStart,
                8
            )
            .stream()
            .map(this::call)
            .toList();
        return new DashboardResponse(summary, modelDistribution, tokenTrend, recentCalls);
    }

    private Instant rangeStart(String range) {
        return switch (range == null ? "7d" : range) {
            case "1d" -> Instant.now(clock).minus(1, ChronoUnit.DAYS);
            case "30d" -> Instant.now(clock).minus(30, ChronoUnit.DAYS);
            case "90d" -> Instant.now(clock).minus(90, ChronoUnit.DAYS);
            case "all" -> Instant.EPOCH;
            default -> Instant.now(clock).minus(7, ChronoUnit.DAYS);
        };
    }

    private Instant startOfDay() {
        return Instant.now(clock)
            .atZone(ZoneId.systemDefault())
            .truncatedTo(ChronoUnit.DAYS)
            .toInstant();
    }

    private ModelDistribution modelDistribution(Object[] row) {
        return new ModelDistribution(
            string(row, 0, "unknown"),
            number(row, 1),
            number(row, 2),
            number(row, 3),
            number(row, 4),
            decimal(row, 5)
        );
    }

    private TokenTrendPoint trendPoint(Object[] row) {
        return new TokenTrendPoint(
            date(row[0]),
            number(row, 1),
            number(row, 2),
            number(row, 3),
            number(row, 4),
            decimal(row, 5)
        );
    }

    private CallUsage call(AiCallUsageEntity entity) {
        return new CallUsage(
            entity.getId(),
            entity.getTraceId(),
            entity.getUserId(),
            entity.getUsername(),
            entity.getOperation(),
            entity.getAgentId(),
            entity.getSessionId(),
            entity.getModelId(),
            entity.getPromptTokens(),
            entity.getCompletionTokens(),
            entity.getTotalTokens(),
            entity.getUsageSource().name(),
            entity.getDurationMs(),
            entity.getStatus(),
            entity.getErrorMessage(),
            entity.getCreatedAt()
        );
    }

    private TokenTotals totals(Object[] row) {
        if (row != null && row.length == 1 && row[0] instanceof Object[] nested) {
            return totals(nested);
        }
        if (row == null || row.length < 4) {
            return new TokenTotals(0, 0, 0, 0);
        }
        return new TokenTotals(
            number(row, 0),
            number(row, 1),
            number(row, 2),
            number(row, 3)
        );
    }

    private String string(Object[] row, int index, String fallback) {
        if (row == null || row.length <= index || row[index] == null || String.valueOf(row[index]).isBlank()) {
            return fallback;
        }
        return String.valueOf(row[index]);
    }

    private long number(Object[] row, int index) {
        if (row == null || row.length <= index) {
            return 0;
        }
        Object value = row[index];
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0 : Long.parseLong(String.valueOf(value));
    }

    private double decimal(Object[] row, int index) {
        if (row == null || row.length <= index) {
            return 0;
        }
        Object value = row[index];
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return value == null ? 0 : Double.parseDouble(String.valueOf(value));
    }

    private LocalDate date(Object value) {
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }
}
