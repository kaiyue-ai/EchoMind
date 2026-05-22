package com.echomind.skill.travel;

import com.echomind.skill.api.SkillRequest;
import com.echomind.skill.api.SkillResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TravelPlanningSkillTest {

    private final TravelPlanningSkill skill = new TravelPlanningSkill();

    @Test
    void createsMultiCityPlan() {
        SkillResult result = skill.execute(request(Map.of(
            "destinations", List.of("东京", "京都", "大阪"),
            "days", 7,
            "budget", 12000,
            "travelers", 2,
            "style", "美食文化",
            "startDate", "2026-07-01",
            "international", true
        ))).join();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).contains("行程规划方案");
        assertThat(result.output()).contains("东京 -> 京都 -> 大阪");
        assertThat(result.output()).contains("预算拆分");
        assertThat(result.output()).contains("证件与时间表");
        assertThat(result.output()).contains("护照");
    }

    @Test
    void validatesRequiredDestinations() {
        SkillResult result = skill.execute(request(Map.of("days", 3))).join();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("destinations");
    }

    private SkillRequest request(Map<String, Object> parameters) {
        return new SkillRequest(parameters, null, null);
    }
}
