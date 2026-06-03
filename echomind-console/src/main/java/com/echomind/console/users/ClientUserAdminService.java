package com.echomind.console.users;

import com.echomind.console.auth.UserAccountEntity;
import com.echomind.console.auth.UserAccountMapper;
import com.echomind.console.auth.UserAccountStatus;
import com.echomind.console.quota.TokenQuotaMapper;
import com.echomind.console.quota.TokenQuotaUsageMapper;
import com.echomind.console.usage.AiCallUsageMapper;
import com.echomind.console.usage.UsageDtos.TokenTotals;
import com.echomind.console.users.ClientUserAdminDtos.ClientUserDeletionStats;
import com.echomind.console.users.ClientUserAdminDtos.ClientUserListResponse;
import com.echomind.console.users.ClientUserAdminDtos.ClientUserSummary;
import com.echomind.console.users.ClientUserAdminDtos.ClientUserView;
import com.echomind.console.users.ClientUserAdminDtos.DeleteClientUserResponse;
import com.echomind.console.users.ClientUserAdminDtos.UpdateClientUserStatusRequest;
import com.echomind.memory.persistence.mapper.ChatMessageMapper;
import com.echomind.memory.persistence.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 项目三管理端用于管理客户端用户账号。 */
@Service
@RequiredArgsConstructor
public class ClientUserAdminService {

    private final UserAccountMapper userMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final AiCallUsageMapper usageMapper;
    private final TokenQuotaMapper quotaMapper;
    private final TokenQuotaUsageMapper quotaUsageMapper;
    private final ClientUserRedisCleanupService redisCleanupService;

    @Transactional(readOnly = true)
    public ClientUserListResponse list() {
        Map<String, Long> sessionCounts = countMap(sessionMapper.countByUser());
        Map<String, Long> messageCounts = countMap(messageMapper.countByUser());
        Map<String, TokenTotals> tokenTotals = new HashMap<>();
        long totalSessions = 0;
        long totalMessages = 0;
        for (Object[] row : usageMapper.totalsByUser()) {
            tokenTotals.put(String.valueOf(row[0]), totals(row, 1));
        }
        for (Long count : sessionCounts.values()) {
            totalSessions += count == null ? 0 : count;
        }
        for (Long count : messageCounts.values()) {
            totalMessages += count == null ? 0 : count;
        }
        List<UserAccountEntity> accounts = userMapper.selectAll();
        List<ClientUserView> users = accounts.stream()
            .map(user -> view(
                user,
                sessionCounts.getOrDefault(user.getUserId(), 0L),
                messageCounts.getOrDefault(user.getUserId(), 0L),
                tokenTotals.getOrDefault(user.getUserId(), new TokenTotals(0, 0, 0, 0))
            ))
            .sorted(Comparator.comparing((ClientUserView user) -> user.tokenTotals().totalTokens()).reversed()
                .thenComparing(ClientUserView::username))
            .toList();
        long activeUsers = accounts.stream()
            .filter(user -> user.getStatus() == UserAccountStatus.ACTIVE)
            .count();
        ClientUserSummary summary = new ClientUserSummary(
            accounts.size(),
            activeUsers,
            accounts.size() - activeUsers,
            totalSessions,
            totalMessages,
            totals(usageMapper.globalTotals())
        );
        return new ClientUserListResponse(summary, users);
    }

    @Transactional
    public ClientUserView updateStatus(String userId, UpdateClientUserStatusRequest request) {
        UserAccountStatus status = request == null ? null : request.status();
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        UserAccountEntity user = userMapper.selectOptionalById(userId)
            .orElseThrow(() -> new IllegalArgumentException("客户端用户不存在"));
        user.setStatus(status);
        UserAccountEntity saved = userMapper.upsertById(user);
        return view(
            saved,
            sessionMapper.countByUserId(userId),
            messageMapper.countByUserId(userId),
            totals(usageMapper.totalsByUserId(userId))
        );
    }

    @Transactional
    public DeleteClientUserResponse delete(String userId) {
        UserAccountEntity user = userMapper.selectOptionalById(userId)
            .orElseThrow(() -> new IllegalArgumentException("客户端用户不存在"));
        List<String> sessionIds = sessionMapper.selectSessionIdsByUserId(userId);
        long sessions = sessionMapper.countByUserId(userId);
        long messages = messageMapper.countByUserId(userId);
        long usageRows = usageMapper.countByUserId(userId);
        long quotaRows = (quotaMapper.existsById(userId) ? 1 : 0) + quotaUsageMapper.countByUserId(userId);
        usageMapper.deleteByUserId(userId);
        if (quotaRows > 0) {
            quotaMapper.deleteById(userId);
            quotaUsageMapper.deleteByUserId(userId);
        }
        messageMapper.deleteByUserId(userId);
        sessionMapper.deleteByUserId(userId);
        userMapper.deleteEntity(user);
        long redisKeys = redisCleanupService.cleanup(userId, sessionIds);
        ClientUserDeletionStats stats = new ClientUserDeletionStats(
            sessions,
            messages,
            usageRows,
            quotaRows,
            redisKeys
        );
        return new DeleteClientUserResponse(user.getUserId(), user.getUsername(), stats);
    }

    private ClientUserView view(UserAccountEntity user, long sessions, long messages, TokenTotals totals) {
        return new ClientUserView(
            user.getUserId(),
            user.getUsername(),
            user.getAvatarUri(),
            user.getStatus(),
            user.getStatus() == UserAccountStatus.ACTIVE,
            user.getCreatedAt(),
            user.getUpdatedAt(),
            sessions,
            messages,
            totals == null ? new TokenTotals(0, 0, 0, 0) : totals
        );
    }

    private Map<String, Long> countMap(List<Object[]> rows) {
        Map<String, Long> counts = new HashMap<>();
        if (rows == null) {
            return counts;
        }
        for (Object[] row : rows) {
            if (row != null && row.length >= 2) {
                counts.put(String.valueOf(row[0]), number(row[1]));
            }
        }
        return counts;
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
