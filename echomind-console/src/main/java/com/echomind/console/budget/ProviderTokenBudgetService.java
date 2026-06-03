package com.echomind.console.budget;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.budget.ProviderTokenBudgetGuard;
import com.echomind.console.alerts.AlertService;
import com.echomind.console.budget.ProviderTokenBudgetDtos.ProviderTokenBudgetListResponse;
import com.echomind.console.budget.ProviderTokenBudgetDtos.ProviderTokenBudgetView;
import com.echomind.console.budget.ProviderTokenBudgetDtos.UpdateProviderTokenBudgetsRequest;
import com.echomind.console.usage.AiCallUsageEntity;
import com.echomind.console.usage.AiCallUsageMapper;
import com.echomind.llm.router.ModelProviderRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ProviderTokenBudgetService implements ProviderTokenBudgetGuard {

    private static final ZoneId BUDGET_ZONE = ZoneId.of("Asia/Shanghai");

    private final ProviderTokenBudgetMapper budgetMapper;
    private final AiCallUsageMapper usageMapper;
    private final AlertService alertService;
    private final ModelProviderRegistry providerRegistry;
    private final Clock clock;

    @Autowired
    public ProviderTokenBudgetService(ProviderTokenBudgetMapper budgetMapper,
                                      AiCallUsageMapper usageMapper,
                                      AlertService alertService,
                                      ModelProviderRegistry providerRegistry) {
        this(budgetMapper, usageMapper, alertService, providerRegistry, Clock.system(BUDGET_ZONE));
    }

    ProviderTokenBudgetService(ProviderTokenBudgetMapper budgetMapper,
                               AiCallUsageMapper usageMapper,
                               AlertService alertService,
                               ModelProviderRegistry providerRegistry,
                               Clock clock) {
        this.budgetMapper = budgetMapper;
        this.usageMapper = usageMapper;
        this.alertService = alertService;
        this.providerRegistry = providerRegistry;
        this.clock = clock == null ? Clock.system(BUDGET_ZONE) : clock;
    }

    @Override
    @Transactional(readOnly = true)
    public void assertAllowed(PipelineContext ctx) {
        String providerId = providerId(ctx);
        if (providerId == null || providerId.isBlank()) {
            return;
        }
        budgetMapper.selectOptionalById(providerId)
            .filter(budget -> budget.getStatus() == ProviderTokenBudgetStatus.ACTIVE)
            .ifPresent(budget -> assertBudget(providerId, budget, ctx));
    }

    @Transactional
    public void recordWarnings(AiCallUsageEntity usage) {
        String providerId = providerId(usage);
        if (providerId == null || providerId.isBlank()) {
            return;
        }
        budgetMapper.selectOptionalById(providerId)
            .filter(budget -> budget.getStatus() == ProviderTokenBudgetStatus.ACTIVE)
            .ifPresent(budget -> emitSignals(providerId, budget, usage));
    }

    @Transactional(readOnly = true)
    public ProviderTokenBudgetListResponse list() {
        Set<String> providerIds = new LinkedHashSet<>();
        providerRegistry.providerIds().stream().sorted().forEach(providerIds::add);
        budgetMapper.selectAllOrderByProviderIdAsc().forEach(budget -> providerIds.add(budget.getProviderId()));
        usageMapper.providerIdsWithUsage().forEach(providerIds::add);
        List<ProviderTokenBudgetView> budgets = providerIds.stream()
            .filter(providerId -> providerId != null && !providerId.isBlank())
            .map(providerId -> view(providerId, budgetMapper.selectOptionalById(providerId).orElse(null)))
            .sorted(Comparator.comparing(ProviderTokenBudgetView::providerId))
            .toList();
        return new ProviderTokenBudgetListResponse(budgets);
    }

    @Transactional
    public ProviderTokenBudgetListResponse update(UpdateProviderTokenBudgetsRequest request) {
        if (request != null && request.budgets() != null) {
            for (ProviderTokenBudgetView view : request.budgets()) {
                if (view == null || view.providerId() == null || view.providerId().isBlank()) {
                    continue;
                }
                ProviderTokenBudgetEntity entity = budgetMapper.selectOptionalById(view.providerId())
                    .orElseGet(ProviderTokenBudgetEntity::new);
                entity.setProviderId(view.providerId().trim());
                entity.setDailyLimitTokens(normalizeLimit(view.dailyLimitTokens()));
                entity.setWeeklyLimitTokens(normalizeLimit(view.weeklyLimitTokens()));
                entity.setMonthlyLimitTokens(normalizeLimit(view.monthlyLimitTokens()));
                entity.setWarningThresholdPercent(normalizeThreshold(view.warningThresholdPercent()));
                entity.setStatus(view.status() == null ? ProviderTokenBudgetStatus.ACTIVE : view.status());
                budgetMapper.upsertById(entity);
            }
        }
        return list();
    }

    private void assertBudget(String providerId, ProviderTokenBudgetEntity budget, PipelineContext ctx) {
        for (BudgetSignal signal : signals(providerId, budget)) {
            if (!signal.exceeded()) {
                continue;
            }
            alertService.emitProviderBudgetExceeded(providerId, ctx == null ? null : ctx.getTraceId(),
                ctx == null ? null : ctx.getAgentId(), ctx == null ? null : ctx.getSessionId(),
                signal.scope(), signal.usedTokens(), signal.limitTokens());
            throw new ProviderTokenBudgetExceededException(providerId, signal.scope(),
                signal.usedTokens(), signal.limitTokens());
        }
    }

    private void emitSignals(String providerId, ProviderTokenBudgetEntity budget, AiCallUsageEntity usage) {
        for (BudgetSignal signal : signals(providerId, budget)) {
            if (signal.exceeded()) {
                alertService.emitProviderBudgetExceeded(providerId, usage.getTraceId(), usage.getAgentId(),
                    usage.getSessionId(), signal.scope(), signal.usedTokens(), signal.limitTokens());
            } else if (signal.warning()) {
                alertService.emitProviderBudgetWarning(providerId, usage.getTraceId(), usage.getAgentId(),
                    usage.getSessionId(), signal.scope(), signal.usedTokens(), signal.limitTokens(),
                    signal.usagePercent());
            }
        }
    }

    private ProviderTokenBudgetView view(String providerId, ProviderTokenBudgetEntity budget) {
        Long dailyLimit = budget == null ? null : budget.getDailyLimitTokens();
        Long weeklyLimit = budget == null ? null : budget.getWeeklyLimitTokens();
        Long monthlyLimit = budget == null ? null : budget.getMonthlyLimitTokens();
        int threshold = budget == null ? 80 : budget.getWarningThresholdPercent();
        ProviderTokenBudgetStatus status = budget == null ? ProviderTokenBudgetStatus.ACTIVE : budget.getStatus();
        long todayUsed = usageMapper.totalTokensByProviderIdSince(providerId, startOfDay());
        long weekUsed = usageMapper.totalTokensByProviderIdSince(providerId, startOfWeek());
        long monthUsed = usageMapper.totalTokensByProviderIdSince(providerId, startOfMonth());
        double dailyPercent = percent(todayUsed, dailyLimit);
        double weeklyPercent = percent(weekUsed, weeklyLimit);
        double monthlyPercent = percent(monthUsed, monthlyLimit);
        return new ProviderTokenBudgetView(
            providerId,
            dailyLimit,
            weeklyLimit,
            monthlyLimit,
            threshold,
            status,
            todayUsed,
            weekUsed,
            monthUsed,
            dailyPercent,
            weeklyPercent,
            monthlyPercent,
            exceeded(todayUsed, dailyLimit),
            exceeded(weekUsed, weeklyLimit),
            exceeded(monthUsed, monthlyLimit),
            warning(dailyPercent, threshold),
            warning(weeklyPercent, threshold),
            warning(monthlyPercent, threshold),
            budget == null ? null : budget.getUpdatedAt()
        );
    }

    private List<BudgetSignal> signals(String providerId, ProviderTokenBudgetEntity budget) {
        List<BudgetSignal> signals = new ArrayList<>();
        addSignal(signals, "daily", usageMapper.totalTokensByProviderIdSince(providerId, startOfDay()),
            budget.getDailyLimitTokens(), budget.getWarningThresholdPercent());
        addSignal(signals, "weekly", usageMapper.totalTokensByProviderIdSince(providerId, startOfWeek()),
            budget.getWeeklyLimitTokens(), budget.getWarningThresholdPercent());
        addSignal(signals, "monthly", usageMapper.totalTokensByProviderIdSince(providerId, startOfMonth()),
            budget.getMonthlyLimitTokens(), budget.getWarningThresholdPercent());
        return signals;
    }

    private void addSignal(List<BudgetSignal> signals, String scope, long used, Long limit, int threshold) {
        if (limit == null || limit <= 0) {
            return;
        }
        double usagePercent = percent(used, limit);
        signals.add(new BudgetSignal(scope, used, limit, usagePercent,
            warning(usagePercent, threshold), exceeded(used, limit)));
    }

    private Instant startOfDay() {
        return LocalDate.now(clock).atStartOfDay(BUDGET_ZONE).toInstant();
    }

    private Instant startOfWeek() {
        return LocalDate.now(clock)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(BUDGET_ZONE)
            .toInstant();
    }

    private Instant startOfMonth() {
        return LocalDate.now(clock)
            .withDayOfMonth(1)
            .atStartOfDay(BUDGET_ZONE)
            .toInstant();
    }

    private Long normalizeLimit(Long value) {
        return value == null || value <= 0 ? null : value;
    }

    private int normalizeThreshold(int value) {
        return Math.max(1, Math.min(100, value <= 0 ? 80 : value));
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

    private String providerId(PipelineContext ctx) {
        if (ctx == null) {
            return null;
        }
        if (ctx.getResolvedModel() != null && ctx.getResolvedModel().providerId() != null
            && !ctx.getResolvedModel().providerId().isBlank()) {
            return ctx.getResolvedModel().providerId();
        }
        return providerFromModelId(ctx.getModelId());
    }

    private String providerId(AiCallUsageEntity usage) {
        if (usage == null) {
            return null;
        }
        if (usage.getProviderId() != null && !usage.getProviderId().isBlank()) {
            return usage.getProviderId();
        }
        return providerFromModelId(usage.getModelId());
    }

    private String providerFromModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        int separator = modelId.indexOf(':');
        return separator > 0 ? modelId.substring(0, separator) : modelId;
    }

    private record BudgetSignal(
        String scope,
        long usedTokens,
        long limitTokens,
        double usagePercent,
        boolean warning,
        boolean exceeded
    ) {
    }
}
