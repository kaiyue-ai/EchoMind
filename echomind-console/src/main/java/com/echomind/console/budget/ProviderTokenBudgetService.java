package com.echomind.console.budget;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.budget.ProviderTokenBudgetGuard;
import com.echomind.console.alerts.AlertService;
import com.echomind.console.budget.ProviderTokenBudgetDtos.ProviderTokenBudgetListResponse;
import com.echomind.console.budget.ProviderTokenBudgetDtos.ProviderTokenBudgetView;
import com.echomind.console.budget.ProviderTokenBudgetDtos.UpdateProviderTokenBudgetsRequest;
import com.echomind.console.reservation.TokenReservationService;
import com.echomind.console.usage.AiCallUsageEntity;
import com.echomind.console.usage.AiCallUsageMapper;
import com.echomind.llm.router.ModelProviderRegistry;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ProviderTokenBudgetUsageMapper budgetUsageMapper;
    private final AlertService alertService;
    private final ModelProviderRegistry providerRegistry;
    private final ObjectProvider<TokenReservationService> reservationService;
    private final Clock clock;

    @Autowired
    public ProviderTokenBudgetService(ProviderTokenBudgetMapper budgetMapper,
                                      AiCallUsageMapper usageMapper,
                                      ProviderTokenBudgetUsageMapper budgetUsageMapper,
                                      AlertService alertService,
                                      ModelProviderRegistry providerRegistry,
                                      ObjectProvider<TokenReservationService> reservationService) {
        this(budgetMapper, usageMapper, budgetUsageMapper, alertService, providerRegistry, reservationService,
            Clock.system(BUDGET_ZONE));
    }

    ProviderTokenBudgetService(ProviderTokenBudgetMapper budgetMapper,
                               AiCallUsageMapper usageMapper,
                               ProviderTokenBudgetUsageMapper budgetUsageMapper,
                               AlertService alertService,
                               ModelProviderRegistry providerRegistry,
                               ObjectProvider<TokenReservationService> reservationService,
                               Clock clock) {
        this.budgetMapper = budgetMapper;
        this.usageMapper = usageMapper;
        this.budgetUsageMapper = budgetUsageMapper;
        this.alertService = alertService;
        this.providerRegistry = providerRegistry;
        this.reservationService = reservationService;
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
            .filter(this::hasConfiguredLimit)
            .ifPresent(budget -> reserveBudget(providerId, ctx));
    }

    @Transactional
    public void recordUsageAndWarnings(AiCallUsageEntity usage) {
        String providerId = providerId(usage);
        if (providerId == null || providerId.isBlank()) {
            return;
        }
        budgetMapper.selectOptionalById(providerId)
            .filter(budget -> budget.getStatus() == ProviderTokenBudgetStatus.ACTIVE)
            .ifPresent(budget -> {
                settleUsage(providerId, budget, usage == null ? 0 : usage.getTotalTokens());
                emitSignals(providerId, budget, usage);
            });
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

    private void reserveBudget(String providerId, PipelineContext ctx) {
        TokenReservationService service = reservationService == null ? null : reservationService.getIfAvailable();
        if (service == null) {
            throw new com.echomind.console.reservation.TokenReservationUnavailableException(
                "Redis unavailable for provider token budget reservation");
        }
        try {
            List<String> reservationIds = service.reserveProvider(providerId, ctx == null ? null : ctx.getTraceId(),
                service.defaultReserveTokens());
            if (!reservationIds.isEmpty() && ctx != null) {
                ctx.getAttributes().put(PipelineContext.ATTR_PROVIDER_TOKEN_RESERVATION_IDS, reservationIds);
            }
        } catch (ProviderTokenBudgetExceededException e) {
            alertService.emitProviderBudgetExceeded(providerId, ctx == null ? null : ctx.getTraceId(),
                ctx == null ? null : ctx.getAgentId(), ctx == null ? null : ctx.getSessionId(),
                e.scope(), e.usedTokens(), e.limitTokens());
            throw e;
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
        long todayUsed = usedTokens(providerId, "daily", todayBucket());
        long weekUsed = usedTokens(providerId, "weekly", weekBucket());
        long monthUsed = usedTokens(providerId, "monthly", monthBucket());
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
        addSignal(signals, "daily", usedTokens(providerId, "daily", todayBucket()),
            budget.getDailyLimitTokens(), budget.getWarningThresholdPercent());
        addSignal(signals, "weekly", usedTokens(providerId, "weekly", weekBucket()),
            budget.getWeeklyLimitTokens(), budget.getWarningThresholdPercent());
        addSignal(signals, "monthly", usedTokens(providerId, "monthly", monthBucket()),
            budget.getMonthlyLimitTokens(), budget.getWarningThresholdPercent());
        return signals;
    }

    private void settleUsage(String providerId, ProviderTokenBudgetEntity budget, long totalTokens) {
        if (totalTokens <= 0) {
            return;
        }
        settleBucket(providerId, "daily", todayBucket(), budget.getDailyLimitTokens(), totalTokens);
        settleBucket(providerId, "weekly", weekBucket(), budget.getWeeklyLimitTokens(), totalTokens);
        settleBucket(providerId, "monthly", monthBucket(), budget.getMonthlyLimitTokens(), totalTokens);
    }

    private void settleBucket(String providerId, String scope, LocalDate bucketStart, Long limit, long totalTokens) {
        if (limit == null || limit <= 0) {
            return;
        }
        budgetUsageMapper.insertIgnoreBucket(providerId, scope, bucketStart);
        budgetUsageMapper.incrementUsedTokens(providerId, scope, bucketStart, totalTokens);
    }

    private long usedTokens(String providerId, String scope, LocalDate bucketStart) {
        Long used = budgetUsageMapper.selectUsedTokens(providerId, scope, bucketStart);
        return used == null ? 0 : used;
    }

    private void addSignal(List<BudgetSignal> signals, String scope, long used, Long limit, int threshold) {
        if (limit == null || limit <= 0) {
            return;
        }
        double usagePercent = percent(used, limit);
        signals.add(new BudgetSignal(scope, used, limit, usagePercent,
            warning(usagePercent, threshold), exceeded(used, limit)));
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

    private Long normalizeLimit(Long value) {
        return value == null || value <= 0 ? null : value;
    }

    private boolean hasConfiguredLimit(ProviderTokenBudgetEntity budget) {
        return budget != null && (positive(budget.getDailyLimitTokens())
            || positive(budget.getWeeklyLimitTokens())
            || positive(budget.getMonthlyLimitTokens()));
    }

    private boolean positive(Long value) {
        return value != null && value > 0;
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
