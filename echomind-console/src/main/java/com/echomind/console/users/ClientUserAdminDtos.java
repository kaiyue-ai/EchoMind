package com.echomind.console.users;

import com.echomind.console.auth.UserAccountStatus;
import com.echomind.console.usage.UsageDtos.TokenTotals;

import java.time.Instant;
import java.util.List;

public final class ClientUserAdminDtos {

    private ClientUserAdminDtos() {
    }

    public record ClientUserListResponse(ClientUserSummary totals, List<ClientUserView> users) {
    }

    public record ClientUserSummary(
        long totalUsers,
        long activeUsers,
        long disabledUsers,
        long totalSessions,
        long totalMessages,
        TokenTotals tokenTotals
    ) {
    }

    public record ClientUserView(
        String userId,
        String username,
        String avatarUri,
        UserAccountStatus status,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        long sessionCount,
        long messageCount,
        TokenTotals tokenTotals
    ) {
    }

    public record UpdateClientUserStatusRequest(UserAccountStatus status) {
    }

    public record DeleteClientUserResponse(String userId, String username, ClientUserDeletionStats deleted) {
    }

    public record ClientUserDeletionStats(
        long sessions,
        long messages,
        long usageRows,
        long quotas,
        long redisKeys,
        long memoryEmbeddings
    ) {
    }
}
