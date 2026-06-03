package com.echomind.console.usage;

import com.echomind.console.auth.UserAccountMapper;
import com.echomind.console.auth.UserAccountStatus;
import com.echomind.console.auth.UserAccountEntity;
import com.echomind.console.usage.UsageDtos.AllCallsResponse;
import com.echomind.console.usage.UsageDtos.CallUsage;
import com.echomind.console.usage.UsageDtos.ClientUserListResponse;
import com.echomind.console.usage.UsageDtos.ClientUserUsage;
import com.echomind.console.usage.UsageDtos.TokenTotals;
import com.echomind.console.usage.UsageDtos.UsageSummary;
import com.echomind.console.usage.UsageDtos.UserCallsResponse;
import com.echomind.console.usage.UsageDtos.UserModelTokenResponse;
import com.echomind.console.usage.UsageDtos.UserModelTokenUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UsageQueryService {

    private final UserAccountMapper userMapper;
    private final AiCallUsageMapper usageMapper;

    @Transactional(readOnly = true)
    public UsageSummary summary() {
        return new UsageSummary(totals(usageMapper.globalTotals()));
    }

    @Transactional(readOnly = true)
    public ClientUserListResponse users() {
        Map<String, TokenTotals> totals = new HashMap<>();
        for (Object[] row : usageMapper.totalsByUser()) {
            totals.put(String.valueOf(row[0]), totals(row, 1));
        }
        List<ClientUserUsage> users = userMapper.selectAll().stream()
            .map(user -> new ClientUserUsage(
                user.getUserId(),
                user.getUsername(),
                user.getStatus() == UserAccountStatus.ACTIVE,
                user.getCreatedAt(),
                totals.getOrDefault(user.getUserId(), new TokenTotals(0, 0, 0, 0))
            ))
            .sorted(Comparator.comparing((ClientUserUsage user) -> user.totals().totalTokens()).reversed()
                .thenComparing(ClientUserUsage::username))
            .toList();
        return new ClientUserListResponse(totals(usageMapper.globalTotals()), users);
    }

    @Transactional(readOnly = true)
    public UserCallsResponse calls(String userId, Integer limit) {
        var user = userMapper.selectOptionalById(userId)
            .orElseThrow(() -> new IllegalArgumentException("客户端用户不存在"));
        int safeLimit = Math.max(1, Math.min(200, limit == null ? 50 : limit));
        List<CallUsage> calls = usageMapper.findByUserIdAndUsageSourceOrderByCreatedAtDesc(
                userId, TokenUsageSource.PROVIDER, safeLimit)
            .stream()
            .map(this::call)
            .toList();
        return new UserCallsResponse(user.getUserId(), user.getUsername(), totals(usageMapper.totalsByUserId(userId)), calls);
    }

    @Transactional(readOnly = true)
    public AllCallsResponse allCalls(Integer limit) {
        int safeLimit = Math.max(1, Math.min(1000, limit == null ? 200 : limit));
        List<CallUsage> calls = usageMapper.findByUsageSourceOrderByCreatedAtDesc(
                TokenUsageSource.PROVIDER, safeLimit)
            .stream()
            .map(this::call)
            .toList();
        return new AllCallsResponse(totals(usageMapper.globalTotals()), calls);
    }

    @Transactional(readOnly = true)
    public UserModelTokenResponse userModelTokens() {
        Map<String, UserAccountEntity> users = new HashMap<>();
        for (UserAccountEntity user : userMapper.selectAll()) {
            users.put(user.getUserId(), user);
        }
        List<UserModelTokenUsage> rows = usageMapper.totalsByUserAndModel().stream()
            .map(row -> {
                String userId = String.valueOf(row[0]);
                UserAccountEntity user = users.get(userId);
                return new UserModelTokenUsage(
                    userId,
                    user == null ? userId : user.getUsername(),
                    user != null && user.getStatus() == UserAccountStatus.ACTIVE,
                    String.valueOf(row[1]),
                    totals(row, 2),
                    number(row[6])
                );
            })
            .toList();
        return new UserModelTokenResponse(totals(usageMapper.globalTotals()), rows);
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
}
