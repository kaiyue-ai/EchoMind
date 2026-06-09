package com.echomind.console.dashboard;

import com.echomind.console.usage.UsageDtos.CallUsage;
import com.echomind.console.usage.UsageDtos.TokenTotals;

import java.time.LocalDate;
import java.util.List;

/**
 * 管理员仪表盘数据传输对象（DTO）定义类
 *
 * <p>封装后端返回给前端的仪表盘统计数据，用于展示系统运行状态、资源使用情况等。
 */
public final class AdminDashboardDtos {

    private AdminDashboardDtos() {
    }

    /**
     * 仪表盘主响应结构
     *
     * <p>包含仪表盘展示所需的所有数据：摘要统计、模型分布、Token趋势、最近调用记录。
     */
    public record DashboardResponse(
        /** 仪表盘摘要统计（用户数、请求数、Token使用等） */
        DashboardSummary summary,
        /** 各模型的使用分布列表 */
        List<ModelDistribution> modelDistribution,
        /** Token使用趋势（按日期统计） */
        List<TokenTrendPoint> tokenTrend,
        /** 最近调用记录列表 */
        List<CallUsage> recentCalls
    ) {
    }

    /**
     * 仪表盘摘要统计
     *
     * <p>包含系统核心指标的汇总数据，用于展示在仪表盘顶部的统计卡片区域。
     */
    public record DashboardSummary(
        /** 累计 Token 使用量（prompt + completion） */
        TokenTotals totalTokens,
        /** 指定时间范围内的 Token 使用量 */
        TokenTotals rangeTokens,
        /** 今日 Token 使用量 */
        TokenTotals todayTokens,
        /** 总用户数 */
        long totalUsers,
        /** 活跃用户数（通常指最近30天有活跃的用户） */
        long activeUsers,
        /** 禁用用户数 */
        long disabledUsers,
        /** 指定时间范围内的请求总数 */
        long rangeRequests,
        /** 今日请求数 */
        long todayRequests,
        /** 累计请求总数 */
        long totalRequests,
        /** 累计平均请求耗时（毫秒） */
        double averageDurationMs,
        /** 指定时间范围内的平均请求耗时（毫秒） */
        double rangeAverageDurationMs,
        /** 指定时间范围内的错误率（百分比） */
        double rangeErrorRatePercent,
        /** 指定时间范围内的敏感数据事件数 */
        long rangeSensitiveEvents,
        /** 指定时间范围内的告警事件数 */
        long rangeAlertEvents
    ) {
    }

    /**
     * 模型使用分布
     *
     * <p>每个模型的调用统计数据，用于展示各模型的使用情况对比。
     */
    public record ModelDistribution(
        /** 模型 ID（格式：provider:modelName） */
        String modelId,
        /** 该模型的调用次数 */
        long callCount,
        /** 提示词 Token 消耗总量 */
        long promptTokens,
        /** 完成 Token 生成总量 */
        long completionTokens,
        /** Token 消耗总量（prompt + completion） */
        long totalTokens,
        /** 平均请求耗时（毫秒） */
        double averageDurationMs
    ) {
    }

    /**
     * Token 使用趋势点
     *
     * <p>按日期统计的 Token 使用数据点，用于绘制趋势折线图或柱状图。
     */
    public record TokenTrendPoint(
        /** 统计日期 */
        LocalDate date,
        /** 当日提示词 Token 消耗 */
        long promptTokens,
        /** 当日完成 Token 生成 */
        long completionTokens,
        /** 当日 Token 消耗总量 */
        long totalTokens,
        /** 当日调用次数 */
        long callCount,
        /** 当日平均请求耗时（毫秒） */
        double averageDurationMs
    ) {
    }
}
