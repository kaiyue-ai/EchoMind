package com.echomind.skill.travel;

import com.echomind.skill.api.Skill;
import com.echomind.skill.api.SkillMetadata;
import com.echomind.skill.api.SkillRequest;
import com.echomind.skill.api.SkillResult;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 行程规划 Skill：多城市路线、预算、打包清单和证件时间表。
 */
public class TravelPlanningSkill implements Skill {

    @Override
    public SkillMetadata metadata() {
        return new SkillMetadata(
            "travel-planning",
            "1.0.0",
            "行程规划助手：根据目的地、天数、预算、人数和旅行偏好生成多城市路线、每日节奏、预算拆分、打包清单和签证/证件时间表，适合从零搭一版可执行旅行方案。",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "destinations", Map.of("type", "array", "items", Map.of("type", "string"), "description", "目的地列表，例如 [东京, 京都, 大阪]"),
                    "days", Map.of("type", "integer", "description", "总天数"),
                    "budget", Map.of("type", "number", "description", "总预算，人民币或用户指定币种"),
                    "travelers", Map.of("type", "integer", "description", "出行人数，默认 1"),
                    "startDate", Map.of("type", "string", "description", "出发日期 yyyy-MM-dd，可选"),
                    "style", Map.of("type", "string", "description", "旅行风格：省钱、舒适、亲子、美食、自然、文化、商务等"),
                    "transportPreference", Map.of("type", "string", "description", "交通偏好：高铁、飞机、自驾、公共交通、步行等"),
                    "international", Map.of("type", "boolean", "description", "是否跨境旅行，决定签证和证件提醒")
                ),
                "required", List.of("destinations", "days")
            ),
            List.of(),
            "EchoMind",
            List.of("travel", "planning", "route", "budget", "packing", "visa", "旅行", "行程", "预算"),
            List.of("旅行规划", "行程规划", "旅游路线", "多城市路线", "预算优化", "打包清单", "签证时间表", "自由行", "travel planning"),
            Map.of(
                "travel", List.of("旅行", "旅游", "自由行", "出游", "trip"),
                "route", List.of("路线", "行程", "多城市", "动线"),
                "budget", List.of("预算", "省钱", "费用", "预算优化"),
                "packing", List.of("打包", "行李", "清单", "packing"),
                "visa", List.of("签证", "护照", "证件", "visa")
            )
        );
    }

    @Override
    public CompletableFuture<SkillResult> execute(SkillRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                List<String> destinations = destinations(request);
                int days = intParam(request, "days", 0, 1, 90);
                if (destinations.isEmpty()) {
                    return SkillResult.failure("destinations 至少需要一个目的地", System.currentTimeMillis() - start);
                }
                if (days <= 0) {
                    return SkillResult.failure("days 必须大于 0", System.currentTimeMillis() - start);
                }
                int travelers = intParam(request, "travelers", 1, 1, 30);
                double budget = doubleParam(request, "budget", 0);
                String style = defaultText(stringParam(request, "style"), "舒适均衡");
                String transport = defaultText(stringParam(request, "transportPreference"), "公共交通优先");
                boolean international = booleanParam(request, "international", inferInternational(destinations));
                LocalDate startDate = parseDate(stringParam(request, "startDate"));

                String output = buildPlan(destinations, days, travelers, budget, style, transport, international, startDate);
                return SkillResult.success(output, System.currentTimeMillis() - start);
            } catch (Exception e) {
                return SkillResult.failure("行程规划失败: " + e.getMessage(), System.currentTimeMillis() - start);
            }
        });
    }

    private String buildPlan(List<String> destinations, int days, int travelers, double budget,
                             String style, String transport, boolean international, LocalDate startDate) {
        Map<String, Integer> allocation = allocateDays(destinations, days);
        StringBuilder sb = new StringBuilder();
        sb.append("行程规划方案\n");
        sb.append("目的地：").append(String.join(" -> ", destinations)).append("\n");
        sb.append("天数：").append(days).append(" 天 | 人数：").append(travelers).append(" | 风格：").append(style)
            .append(" | 交通：").append(transport).append("\n");
        if (startDate != null) {
            sb.append("日期：").append(startDate).append(" 至 ").append(startDate.plusDays(days - 1)).append("\n");
        }
        sb.append("\n路线节奏：\n");
        int dayCursor = 1;
        for (Map.Entry<String, Integer> entry : allocation.entrySet()) {
            int start = dayCursor;
            int end = dayCursor + entry.getValue() - 1;
            sb.append("- D").append(start == end ? start : start + "-D" + end)
                .append("：").append(entry.getKey())
                .append("（").append(entry.getValue()).append("天）")
                .append(routeAdvice(entry.getKey(), style)).append("\n");
            dayCursor = end + 1;
        }
        sb.append("\n每日模板：\n");
        sb.append("- 上午：核心景点/城市地标，优先预约热门项目。\n");
        sb.append("- 下午：片区深逛、博物馆、街区或自然路线，避免跨城折返。\n");
        sb.append("- 晚上：餐厅、夜景、轻量购物或休息，给第二天交通留余量。\n");
        sb.append("\n预算拆分：\n").append(budgetPlan(budget, travelers, days, international));
        sb.append("\n打包清单：\n").append(packingList(style, international));
        sb.append("\n证件与时间表：\n").append(timeline(international, startDate));
        sb.append("\n优化建议：\n");
        sb.append("- 城市顺序按“少走回头路”排列；跨城移动尽量放在上午或午后低峰。\n");
        sb.append("- 热门票务、酒店和长途交通先锁可退票，再根据天气微调。\n");
        sb.append("- 每 3 天至少留半天缓冲，避免行程过满导致体验崩盘。");
        return sb.toString();
    }

    private Map<String, Integer> allocateDays(List<String> destinations, int days) {
        Map<String, Integer> result = new LinkedHashMap<>();
        int base = Math.max(1, days / destinations.size());
        int remaining = days;
        for (int i = 0; i < destinations.size(); i++) {
            int share = i == destinations.size() - 1 ? remaining : Math.min(base, remaining - (destinations.size() - i - 1));
            result.put(destinations.get(i), Math.max(1, share));
            remaining -= share;
        }
        return result;
    }

    private String budgetPlan(double budget, int travelers, int days, boolean international) {
        if (budget <= 0) {
            return "- 未提供总预算：建议先按“交通 35%、住宿 30%、餐饮 20%、门票活动 10%、备用金 5%”估算。\n";
        }
        double perPersonPerDay = budget / travelers / days;
        return """
            - 总预算：%.0f，人均每天约 %.0f。
            - 交通：%.0f
            - 住宿：%.0f
            - 餐饮：%.0f
            - 门票/活动：%.0f
            - 备用金：%.0f%s
            """.formatted(
            budget, perPersonPerDay,
            budget * 0.35, budget * 0.30, budget * 0.20, budget * 0.10, budget * 0.05,
            international ? "\n- 跨境行程额外预留通信、保险、签证和外币手续费。" : ""
        );
    }

    private String packingList(String style, boolean international) {
        List<String> items = new ArrayList<>(List.of(
            "证件/身份证件、银行卡、少量现金",
            "手机、充电器、充电宝、转换插头",
            "常用药、创可贴、肠胃药、过敏药",
            "按天气准备外套、雨具、防晒用品",
            "可折叠购物袋、洗漱用品、备用衣物"
        ));
        if (style.contains("亲子")) items.add("儿童证件、零食、水杯、备用衣物、常用儿童药");
        if (style.contains("自然") || style.contains("徒步")) items.add("徒步鞋、速干衣、驱蚊用品、离线地图");
        if (international) items.add("护照、签证/入境材料、保险单、酒店和返程凭证打印件");
        return "- " + String.join("\n- ", items) + "\n";
    }

    private String timeline(boolean international, LocalDate startDate) {
        if (!international) {
            return "- 国内行程：出发前 7 天确认车票/机票/酒店，前 2 天看天气并调整装备。\n";
        }
        if (startDate == null) {
            return "- 跨境行程：建议提前 45-60 天确认签证，提前 30 天订交通住宿，提前 7 天检查入境材料。\n";
        }
        return """
            - %s 前：确认护照有效期、签证/入境许可和保险。
            - %s 前：锁定机票、酒店和核心预约。
            - %s 前：检查天气、通信、外币、打印件和应急联系人。
            """.formatted(startDate.minusDays(60), startDate.minusDays(30), startDate.minusDays(7));
    }

    private String routeAdvice(String city, String style) {
        if (style.contains("美食")) return "：围绕餐厅片区安排，午晚餐之间放轻量景点。";
        if (style.contains("文化")) return "：优先博物馆、历史街区和城市导览。";
        if (style.contains("省钱")) return "：多用公共交通和免费景点，住宿选交通节点。";
        if (style.contains("商务")) return "：住宿靠近会场/客户，保留机动时间。";
        return "：核心景点 + 街区体验 + 休息缓冲。";
    }

    @SuppressWarnings("unchecked")
    private List<String> destinations(SkillRequest request) {
        Object raw = request.parameters().get("destinations");
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isBlank()).toList();
        }
        if (raw == null) {
            raw = request.parameters().get("destination");
        }
        if (raw == null) {
            return List.of();
        }
        return Arrays.stream(String.valueOf(raw).split("[,，、>→-]+"))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList();
    }

    private boolean inferInternational(List<String> destinations) {
        String joined = String.join(",", destinations);
        return joined.matches(".*[A-Za-z].*")
            || joined.contains("东京") || joined.contains("大阪") || joined.contains("京都")
            || joined.contains("首尔") || joined.contains("曼谷") || joined.contains("巴黎")
            || joined.contains("伦敦") || joined.contains("新加坡");
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("startDate 格式必须是 yyyy-MM-dd");
        }
    }

    private String stringParam(SkillRequest request, String key) {
        Object value = request.parameters().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private int intParam(SkillRequest request, String key, int defaultValue, int min, int max) {
        Object value = request.parameters().get(key);
        if (value == null || String.valueOf(value).isBlank()) return defaultValue;
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private double doubleParam(SkillRequest request, String key, double defaultValue) {
        Object value = request.parameters().get(key);
        if (value == null || String.valueOf(value).isBlank()) return defaultValue;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean booleanParam(SkillRequest request, String key, boolean defaultValue) {
        Object value = request.parameters().get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value));
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
