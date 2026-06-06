package com.echomind.skill.date;

import com.echomind.skill.api.Skill;
import com.echomind.skill.api.SkillMetadata;
import com.echomind.skill.api.SkillRequest;
import com.echomind.skill.api.SkillResult;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 日期查询技能。
 *
 * <p>用于回答“今天几号”“明天星期几”“某天是星期几”“当前时间”等日期时间问题。
 * 技能不依赖外部网络，直接使用 JVM 当前时钟和指定时区计算，避免模型凭记忆猜日期。</p>
 */
public class DateQuerySkill implements Skill {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;
    private static final DateTimeFormatter INPUT_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter INPUT_DATE_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter OUTPUT_DATE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    @Override
    public SkillMetadata metadata() {
        return new SkillMetadata(
            "date-query",
            "1.0.0",
            "查询当前日期、时间、星期，或按指定时区计算昨天、明天、若干天后的日期，避免模型猜错真实时间。",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "date", Map.of(
                        "type", "string",
                        "description", "Optional date or datetime, such as 2026-05-13 or 2026-05-13T15:30:00"
                    ),
                    "zoneId", Map.of(
                        "type", "string",
                        "description", "Timezone ID, such as Asia/Shanghai, UTC, America/New_York"
                    ),
                    "offsetDays", Map.of(
                        "type", "integer",
                        "description", "Days to add to the base date. Use 1 for tomorrow and -1 for yesterday"
                    ),
                    "format", Map.of(
                        "type", "string",
                        "description", "Optional Java DateTimeFormatter pattern for output"
                    ),
                    "locale", Map.of(
                        "type", "string",
                        "description", "Output locale, zh-CN or en-US"
                    )
                )
            ),
            List.of(),
            "EchoMind",
            List.of("date", "time", "calendar", "weekday", "日期", "时间", "星期", "日历"),
            List.of(
                "日期", "时间", "今天", "今日", "当日", "本日", "当天", "明天", "昨天", "前天", "后天",
                "星期", "周几", "几号", "哪一天", "当前日期", "今日日期", "当天日期", "当前时间",
                "今日时间", "北京时间", "现在时间", "date", "time", "today", "tomorrow",
                "yesterday", "weekday", "calendar"
            ),
            Map.of(
                "date", List.of("日期", "几号", "哪天", "今天", "今日", "当日", "本日", "当天",
                    "明天", "昨天", "后天", "前天", "今日日期", "当天日期"),
                "time", List.of("时间", "几点", "当前时间", "今日时间", "北京时间", "现在时间", "time", "clock"),
                "weekday", List.of("星期", "周几", "礼拜几", "weekday", "day of week"),
                "timezone", List.of("时区", "timezone", "zone")
            )
        );
    }

    @Override
    public CompletableFuture<SkillResult> execute(SkillRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                ZoneId zone = parseZone(stringParam(request, "zoneId"));
                Locale locale = parseLocale(stringParam(request, "locale"));
                int offsetDays = intParam(request, "offsetDays", 0);
                ZonedDateTime dateTime = resolveDateTime(stringParam(request, "date"), zone)
                    .plusDays(offsetDays);
                String output = buildOutput(dateTime, zone, locale, stringParam(request, "format"), offsetDays);
                return SkillResult.success(output, System.currentTimeMillis() - start);
            } catch (Exception e) {
                return SkillResult.failure("日期查询失败: " + e.getMessage(), System.currentTimeMillis() - start);
            }
        });
    }

    private String buildOutput(ZonedDateTime dateTime, ZoneId zone, Locale locale,
                               String format, int offsetDays) {
        String weekday = weekdayName(dateTime.getDayOfWeek(), locale);
        String formatted = format == null || format.isBlank()
            ? OUTPUT_DATE_TIME.withLocale(locale).format(dateTime)
            : DateTimeFormatter.ofPattern(format, locale).format(dateTime);

        return """
            日期时间：%s
            日期：%s
            时间：%s
            星期：%s
            时区：%s
            偏移天数：%d
            """.formatted(
                formatted,
                dateTime.toLocalDate(),
                dateTime.toLocalTime().withNano(0),
                weekday,
                zone.getId(),
                offsetDays
            ).strip();
    }

    private ZonedDateTime resolveDateTime(String input, ZoneId zone) {
        if (input == null || input.isBlank()) {
            return ZonedDateTime.now(zone);
        }
        String normalized = input.trim();
        try {
            return ZonedDateTime.parse(normalized).withZoneSameInstant(zone);
        } catch (DateTimeParseException ignored) {
            // 继续尝试其它常见格式。
        }
        try {
            return OffsetDateTime.parse(normalized).atZoneSameInstant(zone);
        } catch (DateTimeParseException ignored) {
            // 继续尝试本地日期时间。
        }
        try {
            return LocalDateTime.parse(normalized, INPUT_DATE_TIME).atZone(zone);
        } catch (DateTimeParseException ignored) {
            // 继续尝试纯日期。
        }
        try {
            return LocalDate.parse(normalized, INPUT_DATE).atStartOfDay(zone);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date 参数格式应为 yyyy-MM-dd 或 ISO 日期时间");
        }
    }

    private ZoneId parseZone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return DEFAULT_ZONE;
        }
        String normalized = switch (zoneId.trim().toLowerCase(Locale.ROOT)) {
            case "china", "cn", "cst", "北京时间", "中国时间", "上海", "北京" -> "Asia/Shanghai";
            case "utc", "gmt" -> "UTC";
            case "new york", "纽约" -> "America/New_York";
            case "tokyo", "东京" -> "Asia/Tokyo";
            case "london", "伦敦" -> "Europe/London";
            default -> zoneId.trim();
        };
        try {
            return ZoneId.of(normalized);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("无效时区: " + zoneId);
        }
    }

    private Locale parseLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String normalized = locale.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "en", "en-us" -> Locale.US;
            case "zh", "zh-cn", "cn" -> Locale.SIMPLIFIED_CHINESE;
            default -> Locale.forLanguageTag(normalized);
        };
    }

    private String weekdayName(DayOfWeek dayOfWeek, Locale locale) {
        if (Locale.SIMPLIFIED_CHINESE.getLanguage().equals(locale.getLanguage())) {
            return switch (dayOfWeek) {
                case MONDAY -> "星期一";
                case TUESDAY -> "星期二";
                case WEDNESDAY -> "星期三";
                case THURSDAY -> "星期四";
                case FRIDAY -> "星期五";
                case SATURDAY -> "星期六";
                case SUNDAY -> "星期日";
            };
        }
        return dayOfWeek.getDisplayName(TextStyle.FULL, locale);
    }

    private String stringParam(SkillRequest request, String key) {
        Object value = request.parameters().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private int intParam(SkillRequest request, String key, int defaultValue) {
        Object value = request.parameters().get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须是整数");
        }
    }
}
