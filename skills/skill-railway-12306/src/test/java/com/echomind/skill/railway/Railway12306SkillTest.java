package com.echomind.skill.railway;

import com.echomind.skill.api.SkillRequest;
import com.echomind.skill.api.SkillResult;
import com.echomind.skill.api.SkillContext;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class Railway12306SkillTest {

    private static final OkHttpClient TEST_HTTP = new OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build();

    @Test
    void queriesTicketsWithChineseStationNames() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(text("var station_names='@bjb|北京北|VAP|beijingbei|bjb|0@bjp|北京|BJP|beijing|bj|1@shh|上海|SHH|shanghai|sh|2';"));
            server.enqueue(text("<html>init</html>").setHeader("Set-Cookie", "RAIL_DEVICEID=test-device; Path=/"));
            server.enqueue(json("""
                {
                  "data": {
                    "map": {"BJP":"北京","SHH":"上海"},
                    "result": ["%s"]
                  }
                }
                """.formatted(ticketRow())));
            server.start();

            Railway12306Skill skill = new Railway12306Skill(server.url("/").toString(), TEST_HTTP);
            SkillResult result = skill.execute(request(Map.of(
                "operation", "tickets",
                "date", "2026-06-01",
                "from", "北京",
                "to", "上海",
                "limit", 1
            ))).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("## 12306 余票查询");
            assertThat(result.output()).contains("| 车次 | 区间 | 发车时间 | 到达时间 | 历时 | 余票 | 票价 |");
            assertThat(result.output()).contains("G101");
            assertThat(result.output()).contains("北京 -> 上海");
            assertThat(result.output()).contains("| G101 | 北京 -> 上海 | 06:10 | 12:30 | 06:20 |");
            assertThat(result.output()).contains("商务座:2张/¥1000.0；一等座:8张/¥500.0；二等座:有/¥300.0");
            assertThat(result.output()).doesNotContain("<br>");
            assertThat(server.takeRequest().getPath()).isEqualTo("/otn/resources/js/framework/station_name.js");
            assertThat(server.takeRequest().getPath()).isEqualTo("/otn/leftTicket/init");
            assertThat(server.takeRequest().getPath()).contains("/otn/leftTicket/queryG?");
        }
    }

    @Test
    void searchesStations() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(text("var station_names='@bjp|北京|BJP|beijing|bj|1@shh|上海|SHH|shanghai|sh|2';"));
            server.start();

            Railway12306Skill skill = new Railway12306Skill(server.url("/").toString(), TEST_HTTP);
            SkillResult result = skill.execute(request(Map.of(
                "operation", "stations",
                "from", "北京"
            ))).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("北京 | 电报码:BJP");
        }
    }

    @Test
    void searchesStationsWithQueryAlias() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(text("var station_names='@lyh|临沂|LYH|linyi|ly|1@shh|上海|SHH|shanghai|sh|2';"));
            server.start();

            Railway12306Skill skill = new Railway12306Skill(server.url("/").toString(), TEST_HTTP);
            SkillResult result = skill.execute(request(Map.of(
                "operation", "stations",
                "query", "临沂"
            ))).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("临沂 | 电报码:LYH");
        }
    }

    @Test
    void exactStationNameWinsBeforeContainsMatch() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(text("var station_names='@cuw|重庆北|CUW|chongqingbei|cqb|0@cqw|重庆|CQW|chongqing|cq|1@umk|临沂北|UMK|linyibei|lyb|2@lvk|临沂|LVK|linyi|ly|3';"));
            server.enqueue(text("<html>init</html>").setHeader("Set-Cookie", "RAIL_DEVICEID=test-device; Path=/"));
            server.enqueue(json("""
                {
                  "data": {
                    "map": {},
                    "result": []
                  }
                }
                """));
            server.enqueue(text("<html>init</html>").setHeader("Set-Cookie", "RAIL_DEVICEID=test-device; Path=/"));
            server.enqueue(interlineEmpty());
            server.start();

            Railway12306Skill skill = new Railway12306Skill(server.url("/").toString(), TEST_HTTP);
            SkillResult result = skill.execute(request(Map.of(
                "operation", "tickets",
                "date", "2026-06-01",
                "from", "重庆",
                "to", "临沂"
            ))).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("路线：重庆(CQW) -> 临沂(LVK)");
        }
    }

    @Test
    void rawRelativeDateOverridesBadModelDate() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(text("var station_names='@cqw|重庆|CQW|chongqing|cq|1@cdw|成都|CDW|chengdu|cd|2';"));
            server.enqueue(text("<html>init</html>").setHeader("Set-Cookie", "RAIL_DEVICEID=test-device; Path=/"));
            server.enqueue(json("""
                {
                  "data": {
                    "map": {"CQW":"重庆","CDW":"成都"},
                    "result": []
                  }
                }
                """));
            server.enqueue(text("<html>init</html>").setHeader("Set-Cookie", "RAIL_DEVICEID=test-device; Path=/"));
            server.enqueue(interlineEmpty());
            server.start();

            Railway12306Skill skill = new Railway12306Skill(server.url("/").toString(), TEST_HTTP);
            SkillResult result = skill.execute(request(Map.of(
                "operation", "tickets",
                "date", "2025-03-28",
                "from", "重庆",
                "to", "成都"
            ), "查询明天重庆到成都的火车余票")).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("日期：" + LocalDate.now().plusDays(1));
            server.takeRequest();
            server.takeRequest();
            assertThat(server.takeRequest().getRequestUrl().queryParameter("leftTicketDTO.train_date"))
                .isEqualTo(LocalDate.now().plusDays(1).toString());
        }
    }

    @Test
    void queriesInterlineTransfersWithSegmentPrices() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(text("var station_names='@cqw|重庆|CQW|chongqing|cq|1@lvk|临沂|LVK|linyi|ly|2';"));
            server.enqueue(text("<html>init</html>").setHeader("Set-Cookie", "RAIL_DEVICEID=test-device; Path=/"));
            server.enqueue(interlineOneOption());
            server.start();

            Railway12306Skill skill = new Railway12306Skill(server.url("/").toString(), TEST_HTTP);
            SkillResult result = skill.execute(request(Map.of(
                "operation", "transfers",
                "date", "2026-06-01",
                "from", "重庆",
                "to", "临沂",
                "limit", 1
            ))).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("## 12306 中转换乘方案");
            assertThat(result.output()).contains("### 方案1：重庆 -> 郑州东 -> 临沂");
            assertThat(result.output()).contains("| 段 | 车次 | 区间 | 发车时间 | 到达时间 | 历时 | 余票 | 票价 |");
            assertThat(result.output()).contains("| 1 | G1888 | 重庆 -> 郑州东 | 08:00 | 12:00 | 04:00 |");
            assertThat(result.output()).contains("| 2 | G555 | 郑州东 -> 临沂 | 13:10 | 16:20 | 03:10 |");
            assertThat(result.output()).contains("总发车时间：08:00");
            assertThat(result.output()).contains("总到达时间：16:20");
            assertThat(result.output()).contains("换乘等待：01:10");
            assertThat(result.output()).contains("二等座:有/¥300.0；一等座:3张/¥500.0");
            assertThat(result.output()).doesNotContain("<br>");
            assertThat(server.takeRequest().getPath()).isEqualTo("/otn/resources/js/framework/station_name.js");
            assertThat(server.takeRequest().getPath()).isEqualTo("/otn/leftTicket/init");
            assertThat(server.takeRequest().getPath()).contains("/lcquery/queryG?");
        }
    }

    @Test
    void ticketsCanAppendTransfersWhenUserAsksForTransfer() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(text("var station_names='@cqw|重庆|CQW|chongqing|cq|1@lvk|临沂|LVK|linyi|ly|2';"));
            server.enqueue(text("<html>init</html>").setHeader("Set-Cookie", "RAIL_DEVICEID=test-device; Path=/"));
            server.enqueue(json("""
                {
                  "data": {
                    "map": {"CQW":"重庆","LVK":"临沂"},
                    "result": ["%s"]
                  }
                }
                """.formatted(ticketRow("CQW", "LVK"))));
            server.enqueue(text("<html>init</html>").setHeader("Set-Cookie", "RAIL_DEVICEID=test-device; Path=/"));
            server.enqueue(interlineOneOption());
            server.start();

            Railway12306Skill skill = new Railway12306Skill(server.url("/").toString(), TEST_HTTP);
            SkillResult result = skill.execute(request(Map.of(
                "operation", "tickets",
                "date", "2026-06-01",
                "from", "重庆",
                "to", "临沂",
                "limit", 1
            ), "票价，还有中转都没有啊")).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("## 12306 余票查询");
            assertThat(result.output()).contains("商务座:2张/¥1000.0；一等座:8张/¥500.0；二等座:有/¥300.0");
            assertThat(result.output()).doesNotContain("<br>");
            assertThat(result.output()).contains("## 12306 中转换乘方案");
        }
    }

    @Test
    void rejectsBadDate() {
        Railway12306Skill skill = new Railway12306Skill("https://example.com", TEST_HTTP);

        SkillResult result = skill.execute(request(Map.of(
            "operation", "tickets",
            "date", "tomorrow",
            "from", "北京",
            "to", "上海"
        ))).join();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("date 格式");
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static MockResponse text(String body) {
        return new MockResponse().setHeader("Content-Type", "text/plain").setBody(body);
    }

    private static String ticketRow() {
        return ticketRow("BJP", "SHH");
    }

    private static String ticketRow(String fromCode, String toCode) {
        String[] parts = new String[45];
        Arrays.fill(parts, "");
        parts[2] = "240000G1010A";
        parts[3] = "G101";
        parts[6] = fromCode;
        parts[7] = toCode;
        parts[8] = "06:10";
        parts[9] = "12:30";
        parts[10] = "06:20";
        parts[12] = "9100000000M050000000O030000000";
        parts[26] = "有";
        parts[28] = "--";
        parts[29] = "--";
        parts[30] = "有";
        parts[31] = "8";
        parts[32] = "2";
        return String.join("|", parts);
    }

    private static MockResponse interlineEmpty() {
        return json("""
            {
              "data": {
                "middleList": []
              }
            }
            """);
    }

    private static MockResponse interlineOneOption() {
        return json("""
            {
              "data": {
                "middleList": [
                  {
                    "all_lishi": "08:20",
                    "wait_time": "01:10",
                    "train_date": "2026-06-01",
                    "start_time": "08:00",
                    "arrive_date": "2026-06-01",
                    "arrive_time": "16:20",
                    "from_station_name": "重庆",
                    "middle_station_name": "郑州东",
                    "end_station_name": "临沂",
                    "same_station": "0",
                    "same_train": "N",
                    "fullList": [
                      {
                        "train_date": "2026-06-01",
                        "train_no": "6c0000G18880",
                        "station_train_code": "G1888",
                        "from_station_name": "重庆",
                        "to_station_name": "郑州东",
                        "start_time": "08:00",
                        "arrive_time": "12:00",
                        "lishi": "04:00",
                        "zy_num": "3",
                        "ze_num": "有",
                        "yp_info": "O030000000M050000000",
                        "seat_discount_info": ""
                      },
                      {
                        "train_date": "2026-06-01",
                        "train_no": "6c0000G05550",
                        "station_train_code": "G555",
                        "from_station_name": "郑州东",
                        "to_station_name": "临沂",
                        "start_time": "13:10",
                        "arrive_time": "16:20",
                        "lishi": "03:10",
                        "ze_num": "2",
                        "wz_num": "有",
                        "yp_info": "O020000000W0205004000",
                        "seat_discount_info": ""
                      }
                    ]
                  }
                ]
              }
            }
            """);
    }

    private SkillRequest request(Map<String, Object> parameters) {
        return new SkillRequest(parameters, null, null);
    }

    private SkillRequest request(Map<String, Object> parameters, String rawUserMessage) {
        return new SkillRequest(parameters, new SkillContext("session-a", "agent-a",
            Map.of("rawUserMessage", rawUserMessage)), null);
    }
}
