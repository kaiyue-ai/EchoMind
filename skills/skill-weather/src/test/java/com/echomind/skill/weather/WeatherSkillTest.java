package com.echomind.skill.weather;

import com.echomind.skill.api.SkillRequest;
import com.echomind.skill.api.SkillResult;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherSkillTest {

    private static final OkHttpClient TEST_HTTP = new OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build();

    @Test
    void metadataDoesNotDeclareDirectResultForWeatherAnswers() {
        WeatherSkill skill = new WeatherSkill();

        assertThat(skill.metadata().tags()).doesNotContain("direct-result", "final-answer");
        assertThat(skill.metadata().keywords()).contains("温度");
        assertThat(skill.metadata().aliases().get("temperature")).contains("最高温度", "最低温度");
    }

    @Test
    void returnsMinAndMaxTemperatureFromWttrJson() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "current_condition": [
                        {
                          "temp_C": "18",
                          "FeelsLikeC": "17",
                          "humidity": "63",
                          "windspeedKmph": "12",
                          "lang_zh": [{"value": "多云"}]
                        }
                      ],
                      "weather": [
                        {
                          "mintempC": "11",
                          "maxtempC": "24"
                        }
                      ]
                    }
                    """));
            server.start();

            WeatherSkill skill = new WeatherSkill(server.url("/").toString(), TEST_HTTP);
            SkillResult result = skill.execute(new SkillRequest(Map.of("city", "北京"), null, null)).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("城市：Beijing");
            assertThat(result.output()).contains("当前天气：多云");
            assertThat(result.output()).contains("当前温度：18C");
            assertThat(result.output()).contains("今日最低/最高：11C / 24C");
            assertThat(server.takeRequest().getPath()).startsWith("/Beijing?format=j1");
        }
    }

    @Test
    void asksForCityInsteadOfDefaultingWhenCityIsMissing() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            WeatherSkill skill = new WeatherSkill(server.url("/").toString(), TEST_HTTP);

            SkillResult result = skill.execute(new SkillRequest(Map.of(), null, null)).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("请告诉我要查询哪个城市");
            assertThat(server.getRequestCount()).isZero();
        }
    }
}
