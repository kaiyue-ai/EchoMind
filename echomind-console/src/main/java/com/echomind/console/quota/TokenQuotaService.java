package com.echomind.console.quota;

import com.echomind.console.auth.AuthUser;
import com.echomind.console.auth.UserAccountEntity;
import com.echomind.console.auth.UserAccountMapper;
import com.echomind.console.auth.UserAccountStatus;
import com.echomind.console.quota.TokenQuotaDtos.TokenQuotaListResponse;
import com.echomind.console.quota.TokenQuotaDtos.TokenQuotaView;
import com.echomind.console.quota.TokenQuotaDtos.UpdateTokenQuotaRequest;
import com.echomind.console.reservation.TokenReservationService;
import com.echomind.console.usage.AiCallUsageMapper;
import com.echomind.console.usage.UsageDtos.TokenTotals;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Token配额服务
 * 
 * <p>负责用户级Token配额的管理和结算，包括：
 * <ul>
 *   <li>配额检查（请求前快速检查）</li>
 *   <li>配额列表查询</li>
 *   <li>配额更新</li>
 *   <li>配额结算（原子累加，并发安全）</li>
 * </ul>
 * </p>
 * 
 * <p>配额结算按模型返回的真实用量原子累加；请求前检查负责拒绝已满额用户。</p>
 */
@Service
public class TokenQuotaService {

    /** 每日配额范围标识 */
    private static final String DAILY = "daily";
    
    /** 每月配额范围标识 */
    private static final String MONTHLY = "monthly";

    /** 配额配置Mapper */
    private final TokenQuotaMapper quotaMapper;
    
    /** 用户账户Mapper */
    private final UserAccountMapper userMapper;
    
    /** AI调用使用量Mapper */
    private final AiCallUsageMapper usageMapper;
    
    /** 配额使用账本Mapper */
    private final TokenQuotaUsageMapper quotaUsageMapper;

    /** Redis Token 预留服务。 */
    private final ObjectProvider<TokenReservationService> reservationService;
    
    /** 系统时钟 */
    private final Clock clock;

    /**
     * 构造函数（Spring依赖注入）
     */
    @Autowired
    public TokenQuotaService(TokenQuotaMapper quotaMapper,
                             UserAccountMapper userMapper,
                             AiCallUsageMapper usageMapper,
                             TokenQuotaUsageMapper quotaUsageMapper,
                             ObjectProvider<TokenReservationService> reservationService) {
        this(quotaMapper, userMapper, usageMapper, quotaUsageMapper, reservationService, Clock.systemDefaultZone());
    }

    public TokenQuotaService(TokenQuotaMapper quotaMapper,
                             UserAccountMapper userMapper,
                             AiCallUsageMapper usageMapper,
                             TokenQuotaUsageMapper quotaUsageMapper) {
        this(quotaMapper, userMapper, usageMapper, quotaUsageMapper, null, Clock.systemDefaultZone());
    }

    /**
     * 构造函数（测试用，可注入自定义时钟）
     */
    TokenQuotaService(TokenQuotaMapper quotaMapper,
                      UserAccountMapper userMapper,
                      AiCallUsageMapper usageMapper,
                      TokenQuotaUsageMapper quotaUsageMapper,
                      Clock clock) {
        this(quotaMapper, userMapper, usageMapper, quotaUsageMapper, null, clock);
    }

    TokenQuotaService(TokenQuotaMapper quotaMapper,
                      UserAccountMapper userMapper,
                      AiCallUsageMapper usageMapper,
                      TokenQuotaUsageMapper quotaUsageMapper,
                      ObjectProvider<TokenReservationService> reservationService,
                      Clock clock) {
        this.quotaMapper = quotaMapper;
        this.userMapper = userMapper;
        this.usageMapper = usageMapper;
        this.quotaUsageMapper = quotaUsageMapper;
        this.reservationService = reservationService;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    /**
     * 检查用户Token配额是否允许调用
     * 
     * <p>请求前快速检查，基于实时统计判断是否超限。
     * 未认证用户跳过检查。</p>
     * 
     * @param user 认证用户
     * @throws TokenQuotaExceededException 当配额超限时抛出
     */
    @Transactional(readOnly = true)
    public void assertAllowed(AuthUser user) {
        if (user == null || !user.authenticated()) {
            return;
        }
        quotaMapper.selectOptionalById(user.userId())
            .filter(quota -> quota.getStatus() == TokenQuotaStatus.ACTIVE)
            .ifPresent(quota -> assertQuota(user.userId(), quota));
    }

    public List<String> reserveUsage(AuthUser user, String requestId, long estimatedTokens) {
        if (user == null || !user.authenticated()) {
            return List.of();
        }
        TokenReservationService service = reservationService == null ? null : reservationService.getIfAvailable();
        if (service == null) {
            return List.of();
        }
        return service.reserveUser(user.userId(), requestId,
            estimatedTokens > 0 ? estimatedTokens : service.defaultReserveTokens());
    }

    /**
     * 获取所有用户的配额列表
     * 
     * <p>返回用户列表，包含配额配置和使用统计信息。</p>
     * 
     * @return 配额列表响应
     */
    @Transactional(readOnly = true)
    public TokenQuotaListResponse list() {
        // Step 1: 获取所有配额配置
        Map<String, TokenQuotaEntity> quotas = new HashMap<>();
        quotaMapper.selectAll().forEach(quota -> quotas.put(quota.getUserId(), quota));
        
        // Step 2: 获取用户Token使用统计
        Map<String, TokenTotals> totals = new HashMap<>();
        for (Object[] row : usageMapper.totalsByUser()) {
            totals.put(String.valueOf(row[0]), totals(row, 1));
        }
        
        // Step 3: 组装视图并按使用量降序排序
        return new TokenQuotaListResponse(userMapper.selectAll().stream()
            .map(user -> view(user, quotas.get(user.getUserId()), totals.get(user.getUserId())))
            .sorted(Comparator.comparing((TokenQuotaView view) -> view.totalUsedTokens()).reversed()
                .thenComparing(TokenQuotaView::username))
            .toList());
    }

    /**
     * 更新用户配额配置
     * 
     * @param userId  用户ID
     * @param request 更新请求
     * @return 更新后的配额视图
     * @throws IllegalArgumentException 当用户不存在时抛出
     */
    @Transactional
    public TokenQuotaView update(String userId, UpdateTokenQuotaRequest request) {
        // Step 1: 验证用户存在
        UserAccountEntity user = userMapper.selectOptionalById(userId)
            .orElseThrow(() -> new IllegalArgumentException("客户端用户不存在"));
        
        // Step 2: 获取或创建配额实体
        TokenQuotaEntity quota = quotaMapper.selectOptionalById(userId).orElseGet(() -> {
            TokenQuotaEntity created = new TokenQuotaEntity();
            created.setUserId(userId);
            return created;
        });
        
        // Step 3: 更新配额配置
        quota.setDailyLimitTokens(normalizeLimit(request == null ? null : request.dailyLimitTokens()));
        quota.setMonthlyLimitTokens(normalizeLimit(request == null ? null : request.monthlyLimitTokens()));
        quota.setStatus(request == null || request.status() == null ? TokenQuotaStatus.ACTIVE : request.status());
        
        // Step 4: 保存并返回视图
        TokenQuotaEntity saved = quotaMapper.upsertById(quota);
        return view(user, saved, totals(usageMapper.totalsByUserId(userId)));
    }

    /**
     * 结算Token使用量（原子操作）
     * 
     * <p>使用新事务确保结算操作的原子性，避免与主事务冲突。
     * 对每日和每月配额进行原子累加。后置结算不拒绝已经完成的模型调用，
     * 即使本次真实用量使 bucket 超过限额，也由下一次请求前检查拦截。</p>
     * 
     * @param user            认证用户
     * @param currentCallTokens 当前调用的Token消耗
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleUsage(AuthUser user, long currentCallTokens) {
        if (user == null || !user.authenticated() || currentCallTokens <= 0) {
            return;
        }
        quotaMapper.selectOptionalById(user.userId())
            .filter(quota -> quota.getStatus() == TokenQuotaStatus.ACTIVE)
            .ifPresent(quota -> settleQuota(user.userId(), quota, currentCallTokens));
    }

    /**
     * 检查配额是否超限
     * 
     * @param userId 用户ID
     * @param quota  配额配置
     * @throws TokenQuotaExceededException 当配额超限时抛出
     */
    private void assertQuota(String userId, TokenQuotaEntity quota) {
        long todayUsed = usedTokens(userId, DAILY, todayBucket());
        long monthUsed = usedTokens(userId, MONTHLY, monthBucket());
        
        // 检查每日配额
        if (quota.getDailyLimitTokens() != null && todayUsed >= quota.getDailyLimitTokens()) {
            throw new TokenQuotaExceededException(userId, DAILY, todayUsed, quota.getDailyLimitTokens());
        }
        // 检查每月配额
        if (quota.getMonthlyLimitTokens() != null && monthUsed >= quota.getMonthlyLimitTokens()) {
            throw new TokenQuotaExceededException(userId, MONTHLY, monthUsed, quota.getMonthlyLimitTokens());
        }
    }

    /**
     * 结算配额（原子累加）
     * 
     * <p>对每日和每月桶进行原子累加，不做后置超限拒绝。</p>
     * 
     * @param userId            用户ID
     * @param quota             配额配置
     * @param currentCallTokens 当前调用的Token消耗
     */
    private void settleQuota(String userId, TokenQuotaEntity quota, long currentCallTokens) {
        settleBucket(userId, DAILY, todayBucket(), quota.getDailyLimitTokens(), currentCallTokens);
        settleBucket(userId, MONTHLY, monthBucket(), quota.getMonthlyLimitTokens(), currentCallTokens);
    }

    /**
     * 结算单个配额桶
     * 
     * <p>执行以下操作：
     * <ol>
     *   <li>确保桶记录存在（INSERT IGNORE）</li>
     *   <li>直接累加真实Token使用量</li>
     * </ol>
     * </p>
     * 
     * @param userId            用户ID
     * @param scope             范围（daily/monthly）
     * @param bucketStart       桶起始日期
     * @param limit             配额限制
     * @param currentCallTokens 当前调用的Token消耗
     */
    private void settleBucket(String userId, String scope, LocalDate bucketStart, Long limit, long currentCallTokens) {
        // 无限制则跳过
        if (limit == null || limit <= 0) {
            return;
        }
        
        // Step 1: 确保桶记录存在
        quotaUsageMapper.insertIgnoreBucket(userId, scope, bucketStart);

        // Step 2: 真实用量后置结算不拒绝已完成调用
        quotaUsageMapper.incrementUsedTokens(userId, scope, bucketStart, currentCallTokens);
    }

    /**
     * 构建配额视图
     * 
     * @param user   用户账户实体
     * @param quota  配额实体
     * @param totals Token使用统计
     * @return 配额视图
     */
    private TokenQuotaView view(UserAccountEntity user, TokenQuotaEntity quota, TokenTotals totals) {
        TokenTotals safeTotals = totals == null ? new TokenTotals(0, 0, 0, 0) : totals;
        Long dailyLimit = quota == null ? null : quota.getDailyLimitTokens();
        Long monthlyLimit = quota == null ? null : quota.getMonthlyLimitTokens();
        TokenQuotaStatus status = quota == null ? TokenQuotaStatus.ACTIVE : quota.getStatus();
        
        // 获取今日和本月使用量
        long todayUsed = usedTokens(user.getUserId(), DAILY, todayBucket());
        long monthUsed = usedTokens(user.getUserId(), MONTHLY, monthBucket());
        
        // 计算使用百分比
        double dailyPercent = percent(todayUsed, dailyLimit);
        double monthlyPercent = percent(monthUsed, monthlyLimit);
        
        return new TokenQuotaView(
            user.getUserId(),
            user.getUsername(),
            user.getStatus() == UserAccountStatus.ACTIVE,
            dailyLimit,
            monthlyLimit,
            status,
            todayUsed,
            monthUsed,
            safeTotals.totalTokens(),
            safeTotals.callCount(),
            dailyPercent,
            monthlyPercent,
            exceeded(todayUsed, dailyLimit),
            exceeded(monthUsed, monthlyLimit),
            quota == null ? null : quota.getUpdatedAt()
        );
    }

    /**
     * 获取今日桶日期
     * 
     * @return 今日日期
     */
    private LocalDate todayBucket() {
        return Instant.now(clock).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 获取本月桶日期（本月第一天）
     * 
     * @return 本月第一天
     */
    private LocalDate monthBucket() {
        return todayBucket().withDayOfMonth(1);
    }

    /**
     * 获取Token使用量（不加锁）
     * 
     * @param userId      用户ID
     * @param scope       范围（daily/monthly）
     * @param bucketStart 桶起始日期
     * @return Token使用量
     */
    private long usedTokens(String userId, String scope, LocalDate bucketStart) {
        Long used = quotaUsageMapper.selectUsedTokens(userId, scope, bucketStart);
        return used == null ? 0 : used;
    }

    /**
     * 规范化配额限制（小于等于0转为null）
     * 
     * @param value 输入值
     * @return 规范化后的值
     */
    private Long normalizeLimit(Long value) {
        return value == null || value <= 0 ? null : value;
    }

    /**
     * 判断是否超限
     * 
     * @param used  使用量
     * @param limit 限制值
     * @return true表示已超限
     */
    private boolean exceeded(long used, Long limit) {
        return limit != null && used >= limit;
    }

    /**
     * 计算使用百分比
     * 
     * @param used  使用量
     * @param limit 限制值
     * @return 百分比（最大999）
     */
    private double percent(long used, Long limit) {
        if (limit == null || limit <= 0) {
            return 0;
        }
        return Math.min(999, (used * 100.0) / limit);
    }

    /**
     * 从数据库查询结果构建TokenTotals
     * 
     * @param row 数据库行
     * @return TokenTotals对象
     */
    private TokenTotals totals(Object[] row) {
        return totals(row, 0);
    }

    /**
     * 从数据库查询结果构建TokenTotals（带偏移量）
     * 
     * @param row    数据库行
     * @param offset 偏移量
     * @return TokenTotals对象
     */
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

    /**
     * 将对象转换为long类型
     * 
     * @param value 输入值
     * @return long值
     */
    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0 : Long.parseLong(String.valueOf(value));
    }

}
