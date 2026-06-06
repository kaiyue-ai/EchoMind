package com.echomind.skill.date;

import com.echomind.skill.api.SkillRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DateQuerySkillTest {

    private final DateQuerySkill skill = new DateQuerySkill();

    @Test
    void returnsWeekdayForSpecifiedDate() {
        var result = skill.execute(new SkillRequest(Map.of(
            "date", "2026-05-13",
            "zoneId", "Asia/Shanghai"
        ), null, null)).join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).contains("日期：2026-05-13");
        assertThat(result.output()).contains("星期：星期三");
        assertThat(result.output()).contains("时区：Asia/Shanghai");
    }

    @Test
    void appliesOffsetDays() {
        var result = skill.execute(new SkillRequest(Map.of(
            "date", "2026-05-13",
            "offsetDays", 1
        ), null, null)).join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).contains("日期：2026-05-14");
        assertThat(result.output()).contains("偏移天数：1");
    }

    @Test
    void supportsEnglishLocaleAndCustomFormat() {
        var result = skill.execute(new SkillRequest(Map.of(
            "date", "2026-05-13T09:10:11",
            "locale", "en-US",
            "format", "yyyy/MM/dd HH:mm"
        ), null, null)).join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).contains("日期时间：2026/05/13 09:10");
        assertThat(result.output()).contains("星期：Wednesday");
    }

    @Test
    void rejectsInvalidDate() {
        var result = skill.execute(new SkillRequest(Map.of("date", "not-a-date"), null, null)).join();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("date 参数格式");
    }

    @Test
    void metadataIncludesCommonChineseCurrentDayAliases() {
        var metadata = skill.metadata();

        assertThat(metadata.keywords())
            .contains("今日", "当日", "本日", "当天", "今日日期", "当天日期",
                "今日时间", "北京时间", "现在时间");
        assertThat(metadata.aliases().get("date"))
            .contains("今日", "当日", "本日", "当天", "今日日期", "当天日期");
        assertThat(metadata.aliases().get("time"))
            .contains("今日时间", "北京时间", "现在时间");
    }
}
