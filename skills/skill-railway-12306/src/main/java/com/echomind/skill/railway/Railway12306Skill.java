package com.echomind.skill.railway;

import com.echomind.skill.api.Skill;
import com.echomind.skill.api.SkillMetadata;
import com.echomind.skill.api.SkillRequest;
import com.echomind.skill.api.SkillResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 12306 国内铁路查询 Skill。
 */
public class Railway12306Skill implements Skill {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern STATION_PATTERN = Pattern.compile("@[^|]+\\|([^|]+)\\|([^|]+)\\|([^|]+)\\|([^|]+)");
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final SeatType OTHER_SEAT = new SeatType("其他", "qt");
    private static final Map<String, SeatType> SEAT_TYPES = Map.ofEntries(
        Map.entry("9", new SeatType("商务座", "swz")),
        Map.entry("P", new SeatType("特等座", "tz")),
        Map.entry("M", new SeatType("一等座", "zy")),
        Map.entry("D", new SeatType("优选一等座", "zy")),
        Map.entry("O", new SeatType("二等座", "ze")),
        Map.entry("S", new SeatType("二等包座", "ze")),
        Map.entry("6", new SeatType("高级软卧", "gr")),
        Map.entry("A", new SeatType("高级动卧", "gr")),
        Map.entry("4", new SeatType("软卧", "rw")),
        Map.entry("I", new SeatType("一等卧", "rw")),
        Map.entry("F", new SeatType("动卧", "rw")),
        Map.entry("3", new SeatType("硬卧", "yw")),
        Map.entry("J", new SeatType("二等卧", "yw")),
        Map.entry("2", new SeatType("软座", "rz")),
        Map.entry("1", new SeatType("硬座", "yz")),
        Map.entry("W", new SeatType("无座", "wz")),
        Map.entry("WZ", new SeatType("无座", "wz")),
        Map.entry("H", OTHER_SEAT)
    );
    private static final OkHttpClient DEFAULT_HTTP = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .protocols(List.of(Protocol.HTTP_1_1))
        .build();

    private final String baseUrl;
    private final OkHttpClient http;
    private volatile String sessionCookieHeader;

    public Railway12306Skill() {
        this("https://kyfw.12306.cn", DEFAULT_HTTP);
    }

    Railway12306Skill(String baseUrl, OkHttpClient http) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.http = http;
    }

    @Override
    public SkillMetadata metadata() {
        return new SkillMetadata(
            "12306",
            "1.0.0",
            "查询 12306 国内列车时刻、余票与站点信息。支持中文站名转站点电报码、出发到达余票查询，以及按车次查看经停站时间；数据来自 12306 公开接口，结果以实时接口返回为准。",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "operation", Map.of("type", "string", "enum", List.of("tickets", "stations", "stops", "transfers"), "description", "tickets 查直达余票和票价，transfers 查中转换乘方案，stations 查站点，stops 查经停站"),
                    "date", Map.of("type", "string", "description", "乘车日期，yyyy-MM-dd，默认今天"),
                    "query", Map.of("type", "string", "description", "站点查询关键词，等同于 stations 操作的 from"),
                    "from", Map.of("type", "string", "description", "出发站中文名或电报码，如 北京/BJP"),
                    "to", Map.of("type", "string", "description", "到达站中文名或电报码，如 上海/SHH"),
                    "trainCode", Map.of("type", "string", "description", "车次，如 G101；用于经停站查询或筛选余票"),
                    "trainNo", Map.of("type", "string", "description", "12306 内部 train_no；经停站精确查询时可传"),
                    "limit", Map.of("type", "integer", "description", "余票或中转结果最多返回条数，默认 8"),
                    "includeTransfers", Map.of("type", "boolean", "description", "tickets 操作是否同时追加中转换乘方案")
                )
            ),
            List.of(),
            "EchoMind",
            List.of("12306", "train", "railway", "ticket", "station", "price", "transfer", "高铁", "火车", "余票", "票价", "中转", "换乘"),
            List.of("12306", "火车票", "高铁票", "动车", "列车", "余票", "车次", "站点", "经停站", "火车时刻", "铁路", "票价", "价格", "中转", "换乘", "转车"),
            Map.of(
                "ticket", List.of("余票", "车票", "火车票", "高铁票", "动车票", "票价", "价格"),
                "train", List.of("列车", "车次", "高铁", "动车", "火车"),
                "station", List.of("站点", "火车站", "车站", "电报码"),
                "schedule", List.of("时刻", "经停", "到站", "发车"),
                "transfer", List.of("中转", "换乘", "转车", "联程")
            )
        );
    }

    @Override
    public CompletableFuture<SkillResult> execute(SkillRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                String operation = stringParam(request, "operation");
                if (operation.isBlank()) {
                    operation = "tickets";
                }
                return switch (operation) {
                    case "stations" -> SkillResult.success(queryStations(firstNonBlank(stringParam(request, "from"), stringParam(request, "query"))),
                        System.currentTimeMillis() - start);
                    case "stops" -> queryStops(request, start);
                    case "transfers" -> queryTransfers(request, start);
                    case "tickets" -> queryTickets(request, start);
                    default -> SkillResult.failure("operation 必须是 tickets、stations、stops 或 transfers",
                        System.currentTimeMillis() - start);
                };
            } catch (Exception e) {
                return SkillResult.failure("12306 查询失败: " + e.getMessage(), System.currentTimeMillis() - start);
            }
        });
    }

    private SkillResult queryTickets(SkillRequest request, long start) throws Exception {
        String date = resolveDate(request);
        Map<String, Station> stations = loadStations();
        String fromCode = resolveStationCode(stations, stringParam(request, "from"), "from");
        String toCode = resolveStationCode(stations, stringParam(request, "to"), "to");
        int limit = intParam(request, "limit", 8, 1, 30);
        String trainCodeFilter = stringParam(request, "trainCode").trim().toUpperCase(Locale.ROOT);
        boolean includeTransfers = boolParam(request, "includeTransfers")
            || hasTransferIntent(rawUserMessage(request));

        JsonNode root = queryLeftTickets(date, fromCode, toCode);
        JsonNode data = root.path("data");
        Map<String, String> map = MAPPER.convertValue(data.path("map"), Map.class);
        JsonNode result = data.path("result");
        if (!result.isArray()) {
            return SkillResult.failure("12306 余票接口没有返回 result 数组", System.currentTimeMillis() - start);
        }
        List<TicketRow> rows = new ArrayList<>();
        for (JsonNode item : result) {
            TicketRow row = parseTicket(item.asText(), map);
            if (row == null) continue;
            if (!trainCodeFilter.isBlank() && !row.trainCode().equalsIgnoreCase(trainCodeFilter)) {
                continue;
            }
            rows.add(row);
            if (rows.size() >= limit) break;
        }
        if (rows.isEmpty()) {
            String transferSection = safeTransferSection(date, stations, fromCode, toCode, Math.min(limit, 5));
            return SkillResult.success("""
                ## 12306 余票查询

                - 日期：%s
                - 路线：%s -> %s

                未查询到符合条件的列车。

                %s
                """.formatted(date, stationDisplay(stations, fromCode), stationDisplay(stations, toCode), transferSection).strip(),
                System.currentTimeMillis() - start);
        }
        String transferSection = includeTransfers ? "\n\n" + safeTransferSection(date, stations, fromCode, toCode, Math.min(limit, 5)) : "";
        String output = """
            ## 12306 余票查询

            - 日期：%s
            - 路线：%s -> %s

            %s
            %s
            """.formatted(date, stationDisplay(stations, fromCode), stationDisplay(stations, toCode), formatTicketTable(rows), transferSection).strip();
        return SkillResult.success(output, System.currentTimeMillis() - start);
    }

    private SkillResult queryTransfers(SkillRequest request, long start) throws Exception {
        String date = resolveDate(request);
        Map<String, Station> stations = loadStations();
        String fromCode = resolveStationCode(stations, stringParam(request, "from"), "from");
        String toCode = resolveStationCode(stations, stringParam(request, "to"), "to");
        int limit = intParam(request, "limit", 5, 1, 10);
        String output = formatTransfers(date, stations, fromCode, toCode, limit);
        return SkillResult.success(output, System.currentTimeMillis() - start);
    }

    private SkillResult queryStops(SkillRequest request, long start) throws Exception {
        String date = resolveDate(request);
        Map<String, Station> stations = loadStations();
        String trainNo = stringParam(request, "trainNo");
        String trainCode = stringParam(request, "trainCode").trim().toUpperCase(Locale.ROOT);
        String fromCode = resolveStationCode(stations, stringParam(request, "from"), "from");
        String toCode = resolveStationCode(stations, stringParam(request, "to"), "to");
        if (trainNo.isBlank()) {
            trainNo = findTrainNo(date, fromCode, toCode, trainCode);
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("train_no", trainNo);
        query.put("from_station_telecode", fromCode);
        query.put("to_station_telecode", toCode);
        query.put("depart_date", date);
        JsonNode root = getJson(urlWithQuery("/otn/czxx/queryByTrainNo", query));
        JsonNode stops = root.path("data").path("data");
        if (!stops.isArray()) {
            return SkillResult.failure("12306 经停站接口没有返回站点列表", System.currentTimeMillis() - start);
        }
        List<String> rows = new ArrayList<>();
        for (JsonNode stop : stops) {
            rows.add("%s. %s 到达:%s 发车:%s 停留:%s".formatted(
                stop.path("station_no").asText(""),
                stop.path("station_name").asText(""),
                stop.path("arrive_time").asText("--"),
                stop.path("start_time").asText("--"),
                stop.path("stopover_time").asText("--")
            ));
        }
        return SkillResult.success("""
            12306 经停站查询
            日期：%s
            车次：%s
            %s
            """.formatted(date, trainCode.isBlank() ? trainNo : trainCode, String.join("\n", rows)).strip(),
            System.currentTimeMillis() - start);
    }

    private String queryStations(String keyword) throws Exception {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("stations 操作需要 from 参数作为站点关键词");
        }
        Map<String, Station> stations = loadStations();
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        List<String> rows = stations.values().stream()
            .filter(station -> station.name().contains(keyword.trim())
                || station.code().equalsIgnoreCase(keyword.trim())
                || station.pinyin().contains(normalized)
                || station.shortPinyin().contains(normalized))
            .limit(20)
            .map(station -> "%s | 电报码:%s | 拼音:%s".formatted(station.name(), station.code(), station.pinyin()))
            .toList();
        if (rows.isEmpty()) {
            return "没有匹配到站点：" + keyword;
        }
        return "12306 站点查询\n" + String.join("\n", rows);
    }

    private String findTrainNo(String date, String fromCode, String toCode, String trainCode) throws Exception {
        if (trainCode.isBlank()) {
            throw new IllegalArgumentException("stops 操作需要 trainNo，或提供 trainCode + from + to 以便自动查找 train_no");
        }
        JsonNode root = queryLeftTickets(date, fromCode, toCode);
        Map<String, String> map = MAPPER.convertValue(root.path("data").path("map"), Map.class);
        for (JsonNode item : root.path("data").path("result")) {
            TicketRow row = parseTicket(item.asText(), map);
            if (row != null && row.trainCode().equalsIgnoreCase(trainCode)) {
                return row.trainNo();
            }
        }
        throw new IllegalArgumentException("没有在该路线中找到车次：" + trainCode);
    }

    private TicketRow parseTicket(String raw, Map<String, String> stationMap) {
        String[] parts = raw.split("\\|", -1);
        if (parts.length < 33) return null;
        return new TicketRow(
            parts[2],
            parts[3],
            stationName(stationMap, parts[6]),
            stationName(stationMap, parts[7]),
            parts[8],
            parts[9],
            parts[10],
            seat(parts, 32),
            seat(parts, 31),
            seat(parts, 30),
            seat(parts, 28),
            seat(parts, 29),
            seat(parts, 26),
            parts.length > 18 ? parts[18] : "",
            parts.length > 16 ? parts[16] : "",
            parts.length > 17 ? parts[17] : "",
            extractPrices(
                firstNonBlankValue(parts.length > 39 ? parts[39] : "", parts.length > 12 ? parts[12] : ""),
                parts.length > 54 ? parts[54] : "",
                ticketSeatCounts(parts)
            )
        );
    }

    private String formatTicketTable(List<TicketRow> rows) {
        List<String> lines = new ArrayList<>();
        lines.add("| 车次 | 区间 | 发车时间 | 到达时间 | 历时 | 余票 | 票价 |");
        lines.add("|---|---|---:|---:|---:|---|---|");
        for (TicketRow row : rows) {
            lines.add(formatTicketRow(row));
        }
        return String.join("\n", lines);
    }

    private String formatTicketRow(TicketRow row) {
        String prices = formatPrices(row.prices(), "；");
        return "| %s | %s -> %s | %s | %s | %s | %s | %s |".formatted(
            markdownCell(row.trainCode()),
            markdownCell(row.fromStation()),
            markdownCell(row.toStation()),
            markdownCell(row.startTime()),
            markdownCell(row.arriveTime()),
            markdownCell(row.duration()),
            markdownCell(formatTicketSeats(row, "；")),
            markdownCell(prices.isBlank() ? "接口未返回" : prices)
        );
    }

    private String formatTicketSeats(TicketRow row, String delimiter) {
        List<String> seats = new ArrayList<>();
        addSeat(seats, "商务", row.businessSeat());
        addSeat(seats, "一等", row.firstSeat());
        addSeat(seats, "二等", row.secondSeat());
        addSeat(seats, "软卧", row.softSleeper());
        addSeat(seats, "硬卧", row.hardSleeper());
        addSeat(seats, "硬座", row.hardSeat());
        addSeat(seats, "无座", row.noSeat());
        return String.join(delimiter, seats);
    }

    private Map<String, String> ticketSeatCounts(String[] parts) {
        Map<String, String> seats = new LinkedHashMap<>();
        seats.put("swz", seat(parts, 32));
        seats.put("tz", seat(parts, 25));
        seats.put("zy", seat(parts, 31));
        seats.put("ze", seat(parts, 30));
        seats.put("gr", seat(parts, 21));
        seats.put("srrb", seat(parts, 33));
        seats.put("rw", seat(parts, 23));
        seats.put("yw", seat(parts, 28));
        seats.put("rz", seat(parts, 24));
        seats.put("yz", seat(parts, 29));
        seats.put("wz", seat(parts, 26));
        seats.put("qt", seat(parts, 22));
        return seats;
    }

    private String safeTransferSection(String date, Map<String, Station> stations, String fromCode, String toCode, int limit) {
        try {
            return formatTransfers(date, stations, fromCode, toCode, limit);
        } catch (Exception e) {
            return """
                12306 中转换乘方案
                暂时没有拿到中转方案：%s
                """.formatted(e.getMessage()).strip();
        }
    }

    private String formatTransfers(String date, Map<String, Station> stations, String fromCode, String toCode, int limit) throws Exception {
        JsonNode root = queryInterlineTickets(date, fromCode, toCode);
        JsonNode data = root.path("data");
        if (data.isTextual()) {
            return """
                ## 12306 中转换乘方案

                - 日期：%s
                - 路线：%s -> %s

                %s
                """.formatted(date, stationDisplay(stations, fromCode), stationDisplay(stations, toCode),
                firstNonBlankValue(root.path("errorMsg").asText(""), data.asText())).strip();
        }
        JsonNode list = firstArray(data.path("middleList"), data.path("data"), data.path("result"));
        List<String> rows = new ArrayList<>();
        int index = 1;
        for (JsonNode item : list) {
            TransferOption option = parseTransferOption(item);
            if (option == null) {
                continue;
            }
            rows.add(formatTransferOption(index++, option));
            if (rows.size() >= limit) {
                break;
            }
        }
        if (rows.isEmpty()) {
            return """
                ## 12306 中转换乘方案

                - 日期：%s
                - 路线：%s -> %s

                未查询到可用中转换乘方案。
                """.formatted(date, stationDisplay(stations, fromCode), stationDisplay(stations, toCode)).strip();
        }
        return """
            ## 12306 中转换乘方案

            - 日期：%s
            - 路线：%s -> %s

            %s
            """.formatted(date, stationDisplay(stations, fromCode), stationDisplay(stations, toCode), String.join("\n", rows)).strip();
    }

    private JsonNode firstArray(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return MAPPER.createArrayNode();
    }

    private TransferOption parseTransferOption(JsonNode item) {
        JsonNode fullList = item.path("fullList");
        if (!fullList.isArray() || fullList.isEmpty()) {
            return null;
        }
        List<TransferSegment> segments = new ArrayList<>();
        for (JsonNode segment : fullList) {
            TransferSegment parsed = parseTransferSegment(segment);
            if (parsed != null) {
                segments.add(parsed);
            }
        }
        if (segments.isEmpty()) {
            return null;
        }
        TransferSegment first = segments.get(0);
        TransferSegment last = segments.get(segments.size() - 1);
        String fromName = firstNonBlankValue(field(item, "from_station_name"), first.fromStation());
        String middleName = firstNonBlankValue(field(item, "middle_station_name"), segments.size() > 1 ? first.toStation() : "");
        String toName = firstNonBlankValue(field(item, "end_station_name"), last.toStation());
        boolean sameTrain = "Y".equalsIgnoreCase(field(item, "same_train"));
        String sameStationValue = field(item, "same_station");
        boolean sameStation = "0".equals(sameStationValue) || "Y".equalsIgnoreCase(sameStationValue) || "true".equalsIgnoreCase(sameStationValue);
        return new TransferOption(
            firstNonBlankValue(field(item, "all_lishi"), field(item, "use_time")),
            firstNonBlankValue(field(item, "wait_time"), field(item, "lCWaitTime")),
            firstNonBlankValue(field(item, "train_date"), first.date()),
            firstNonBlankValue(field(item, "start_time"), first.startTime()),
            field(item, "arrive_date"),
            firstNonBlankValue(field(item, "arrive_time"), last.arriveTime()),
            fromName,
            middleName,
            toName,
            sameTrain,
            sameStation,
            segments
        );
    }

    private TransferSegment parseTransferSegment(JsonNode segment) {
        String trainCode = field(segment, "station_train_code");
        if (trainCode.isBlank()) {
            return null;
        }
        Map<String, String> seats = segmentSeatCounts(segment);
        List<SeatPrice> prices = extractPrices(
            firstNonBlankValue(field(segment, "yp_info_new"), field(segment, "yp_info")),
            field(segment, "seat_discount_info"),
            seats
        );
        return new TransferSegment(
            firstNonBlankValue(field(segment, "train_date"), field(segment, "start_train_date")),
            field(segment, "train_no"),
            trainCode,
            field(segment, "from_station_name"),
            field(segment, "to_station_name"),
            field(segment, "start_time"),
            field(segment, "arrive_time"),
            field(segment, "lishi"),
            seats,
            prices
        );
    }

    private String formatTransferOption(int index, TransferOption option) {
        String transferType = option.sameTrain() ? "同车换乘" : option.sameStation() ? "同站换乘" : "换站换乘";
        String wait = option.waitTime().isBlank() ? "--" : option.waitTime();
        String total = option.duration().isBlank() ? "--" : option.duration();
        List<String> segmentLines = new ArrayList<>();
        segmentLines.add("| 段 | 车次 | 区间 | 发车时间 | 到达时间 | 历时 | 余票 | 票价 |");
        segmentLines.add("|---:|---|---|---:|---:|---:|---|---|");
        int segmentIndex = 1;
        for (TransferSegment segment : option.segments()) {
            segmentLines.add("| %d | %s | %s -> %s | %s | %s | %s | %s | %s |".formatted(
                segmentIndex++,
                markdownCell(segment.trainCode()),
                markdownCell(emptyAs(segment.fromStation(), "--")),
                markdownCell(emptyAs(segment.toStation(), "--")),
                markdownCell(emptyAs(segment.startTime(), "--")),
                markdownCell(emptyAs(segment.arriveTime(), "--")),
                markdownCell(emptyAs(segment.duration(), "--")),
                markdownCell(compactSeats(segment.seats(), "；")),
                markdownCell(emptyAs(formatPrices(segment.prices(), "；"), "接口未返回"))
            ));
        }
        return """
            ### 方案%d：%s -> %s -> %s

            - 换乘方式：%s
            - 总发车时间：%s
            - 总到达时间：%s
            - 换乘等待：%s
            - 总历时：%s

            %s
            """.formatted(
            index,
            emptyAs(option.fromStation(), "--"),
            emptyAs(option.middleStation(), "--"),
            emptyAs(option.toStation(), "--"),
            transferType,
            emptyAs(option.startTime(), "--"),
            emptyAs(option.arriveTime(), "--"),
            wait,
            total,
            String.join("\n", segmentLines)
        ).strip();
    }

    private String compactSeats(Map<String, String> seats) {
        return compactSeats(seats, " ");
    }

    private String compactSeats(Map<String, String> seats, String delimiter) {
        List<String> items = new ArrayList<>();
        addSeat(items, "商务", seats.get("swz"));
        addSeat(items, "一等", seats.get("zy"));
        addSeat(items, "二等", seats.get("ze"));
        addSeat(items, "软卧", seats.get("rw"));
        addSeat(items, "硬卧", seats.get("yw"));
        addSeat(items, "硬座", seats.get("yz"));
        addSeat(items, "无座", seats.get("wz"));
        return items.isEmpty() ? "余票未返回" : String.join(delimiter, items);
    }

    private void addSeat(List<String> items, String name, String value) {
        if (value != null && !value.isBlank() && !"--".equals(value)) {
            items.add(name + ":" + formatSeatCount(value));
        }
    }

    private Map<String, String> segmentSeatCounts(JsonNode segment) {
        Map<String, String> seats = new LinkedHashMap<>();
        seats.put("swz", emptyAs(field(segment, "swz_num"), "--"));
        seats.put("tz", emptyAs(field(segment, "tz_num"), "--"));
        seats.put("zy", emptyAs(field(segment, "zy_num"), "--"));
        seats.put("ze", emptyAs(field(segment, "ze_num"), "--"));
        seats.put("gr", emptyAs(field(segment, "gr_num"), "--"));
        seats.put("srrb", emptyAs(field(segment, "srrb_num"), "--"));
        seats.put("rw", emptyAs(field(segment, "rw_num"), "--"));
        seats.put("yw", emptyAs(field(segment, "yw_num"), "--"));
        seats.put("rz", emptyAs(field(segment, "rz_num"), "--"));
        seats.put("yz", emptyAs(field(segment, "yz_num"), "--"));
        seats.put("wz", emptyAs(field(segment, "wz_num"), "--"));
        seats.put("qt", emptyAs(field(segment, "qt_num"), "--"));
        return seats;
    }

    private List<SeatPrice> extractPrices(String priceInfo, String discountInfo, Map<String, String> seatCounts) {
        if (priceInfo == null || priceInfo.isBlank()) {
            return List.of();
        }
        List<SeatPrice> prices = new ArrayList<>();
        Map<String, Integer> discounts = parseDiscounts(discountInfo);
        int segmentLength = 10;
        for (int i = 0; i + segmentLength <= priceInfo.length(); i += segmentLength) {
            String priceText = priceInfo.substring(i, i + segmentLength);
            if (priceText.isBlank()) {
                continue;
            }
            String seatCode = priceSeatCode(priceText);
            SeatType seatType = SEAT_TYPES.getOrDefault(seatCode, OTHER_SEAT);
            String amountText = priceText.substring(1, 6);
            if (!amountText.chars().allMatch(Character::isDigit)) {
                continue;
            }
            double amount = Integer.parseInt(amountText) / 10.0;
            Integer discount = discounts.get(seatCode);
            prices.add(new SeatPrice(
                seatType.name(),
                seatType.shortCode(),
                emptyAs(seatCounts.get(seatType.shortCode()), "--"),
                amount,
                discount
            ));
        }
        return prices;
    }

    private Map<String, Integer> parseDiscounts(String discountInfo) {
        Map<String, Integer> discounts = new LinkedHashMap<>();
        if (discountInfo == null || discountInfo.isBlank()) {
            return discounts;
        }
        int segmentLength = 5;
        for (int i = 0; i + segmentLength <= discountInfo.length(); i += segmentLength) {
            String segment = discountInfo.substring(i, i + segmentLength);
            String value = segment.substring(1);
            if (value.chars().allMatch(Character::isDigit)) {
                discounts.put(segment.substring(0, 1), Integer.parseInt(value));
            }
        }
        return discounts;
    }

    private String priceSeatCode(String priceText) {
        String last = priceText.substring(6, 10);
        if (last.chars().allMatch(Character::isDigit) && Integer.parseInt(last) >= 3000) {
            return "W";
        }
        String code = priceText.substring(0, 1);
        return SEAT_TYPES.containsKey(code) ? code : "H";
    }

    private String formatPrices(List<SeatPrice> prices) {
        return formatPrices(prices, " ");
    }

    private String formatPrices(List<SeatPrice> prices, String delimiter) {
        if (prices == null || prices.isEmpty()) {
            return "";
        }
        List<SeatPrice> visible = prices.stream()
            .filter(price -> price.count() != null && !price.count().isBlank() && !"--".equals(price.count()))
            .toList();
        if (visible.isEmpty()) {
            visible = prices;
        }
        return visible.stream()
            .limit(8)
            .map(price -> "%s:%s/%s%s".formatted(
                price.seatName(),
                formatSeatCount(price.count()),
                formatMoney(price.amount()),
                formatDiscount(price.discount())
            ))
            .toList()
            .stream()
            .reduce((left, right) -> left + delimiter + right)
            .orElse("");
    }

    private String markdownCell(String value) {
        String text = value == null || value.isBlank() ? "--" : value;
        return text.replace("|", "\\|").replace("\r", " ").replace("\n", "<br>");
    }

    private String formatSeatCount(String count) {
        String value = count == null || count.isBlank() ? "--" : count;
        return value.matches("\\d+") ? value + "张" : value;
    }

    private String formatMoney(double amount) {
        return String.format(Locale.ROOT, "¥%.1f", amount);
    }

    private String formatDiscount(Integer discount) {
        if (discount == null || discount <= 0 || discount == 100) {
            return "";
        }
        double rate = discount >= 1000 ? discount / 1000.0 : discount / 10.0;
        String text = Math.abs(rate - Math.rint(rate)) < 0.0001
            ? String.format(Locale.ROOT, "%.0f", rate)
            : String.format(Locale.ROOT, "%.1f", rate);
        return " " + text + "折";
    }

    private Map<String, Station> loadStations() throws Exception {
        try (Response response = http.newCall(new Request.Builder()
            .url(baseUrl + "/otn/resources/js/framework/station_name.js")
            .header("User-Agent", "EchoMindSkill/12306")
            .build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful() || body.isBlank()) {
                throw new IllegalArgumentException("无法读取 12306 站点表: HTTP " + response.code());
            }
            return parseStations(body);
        }
    }

    private Map<String, Station> parseStations(String body) {
        Map<String, Station> stations = new LinkedHashMap<>();
        Matcher matcher = STATION_PATTERN.matcher(body);
        while (matcher.find()) {
            Station station = new Station(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
            stations.put(station.code(), station);
        }
        if (stations.isEmpty()) {
            throw new IllegalArgumentException("12306 站点表解析失败");
        }
        return stations;
    }

    private JsonNode getJson(HttpUrl url) throws Exception {
        String body = "";
        Exception directError = null;
        if (requires12306Session(url)) {
            body = executeJsonWithCurl(url);
            if (looksLikeJson(body)) {
                return MAPPER.readTree(body);
            }
        }
        try {
            body = executeJsonCandidate(url, sessionCookieHeader(false));
            if (!looksLikeJson(body) && requires12306Session(url)) {
                body = executeJsonCandidate(url, sessionCookieHeader(true));
            }
        } catch (Exception e) {
            directError = e;
        }
        if (!looksLikeJson(body) && requires12306Session(url)) {
            body = executeJsonWithCurl(url);
        }
        if (!looksLikeJson(body) && directError != null) {
            throw directError;
        }
        if (!looksLikeJson(body)) {
            throw new IllegalArgumentException("12306 接口返回了非 JSON 内容，可能是站点临时拦截或路径不可用");
        }
        return MAPPER.readTree(body);
    }

    private String executeJsonCandidate(HttpUrl url, String cookies) throws Exception {
        Request.Builder builder = new Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .header("Referer", baseUrl + "/otn/leftTicket/init");
        if (cookies != null && !cookies.isBlank()) {
            builder.header("Cookie", cookies);
        }
        try (Response response = http.newCall(builder.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalArgumentException("12306 接口 HTTP " + response.code());
            }
            return body;
        }
    }

    private boolean looksLikeJson(String body) {
        if (body == null) {
            return false;
        }
        String trimmed = body.stripLeading();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private boolean requires12306Session(HttpUrl url) {
        String path = url == null ? "" : url.encodedPath();
        return path.contains("/otn/leftTicket/") || path.contains("/otn/czxx/") || path.contains("/lcquery/");
    }

    private synchronized String sessionCookieHeader(boolean forceRefresh) throws Exception {
        if (!forceRefresh && sessionCookieHeader != null && !sessionCookieHeader.isBlank()) {
            return sessionCookieHeader;
        }
        Request request = new Request.Builder()
            .url(baseUrl + "/otn/leftTicket/init")
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                sessionCookieHeader = "";
                return sessionCookieHeader;
            }
            List<String> cookies = response.headers("Set-Cookie").stream()
                .map(cookie -> cookie.split(";", 2)[0].trim())
                .filter(cookie -> !cookie.isBlank())
                .toList();
            sessionCookieHeader = String.join("; ", cookies);
            return sessionCookieHeader;
        }
    }

    private String executeJsonWithCurl(HttpUrl url) {
        Path cookieJar = null;
        try {
            cookieJar = Files.createTempFile("echomind-12306-", ".cookies");
            runCurl(List.of(
                "-s",
                "-4",
                "--max-time", "20",
                "-o", discardDevice(),
                "-c", cookieJar.toString(),
                "-b", cookieJar.toString(),
                "-A", BROWSER_UA,
                baseUrl + "/otn/leftTicket/init"
            ));
            return runCurl(List.of(
                "-s",
                "-4",
                "--max-time", "20",
                "-b", cookieJar.toString(),
                "-c", cookieJar.toString(),
                "-A", BROWSER_UA,
                "-H", "Accept: application/json",
                "-H", "Referer: " + baseUrl + "/otn/leftTicket/init",
                url.toString()
            ));
        } catch (Exception ignored) {
            return "";
        } finally {
            if (cookieJar != null) {
                try {
                    Files.deleteIfExists(cookieJar);
                } catch (Exception ignored) {
                    // Best-effort cleanup for a temporary cookie jar.
                }
            }
        }
    }

    private String runCurl(List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(curlCommand());
        command.addAll(args);
        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        boolean finished = process.waitFor(25, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes());
        if (!finished) {
            process.destroyForcibly();
            return "";
        }
        return process.exitValue() == 0 ? output : "";
    }

    private String curlCommand() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "curl.exe" : "curl";
    }

    private String discardDevice() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null";
    }

    private JsonNode queryLeftTickets(String date, String fromCode, String toCode) throws Exception {
        Exception last = null;
        for (String path : List.of("/otn/leftTicket/queryG", "/otn/leftTicket/queryZ", "/otn/leftTicket/query")) {
            try {
                Map<String, String> query = new LinkedHashMap<>();
                query.put("leftTicketDTO.train_date", date);
                query.put("leftTicketDTO.from_station", fromCode);
                query.put("leftTicketDTO.to_station", toCode);
                query.put("purpose_codes", "ADULT");
                return getJson(urlWithQuery(path, query));
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new IllegalArgumentException("12306 余票接口不可用") : last;
    }

    private JsonNode queryInterlineTickets(String date, String fromCode, String toCode) throws Exception {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("train_date", date);
        query.put("from_station_telecode", fromCode);
        query.put("to_station_telecode", toCode);
        query.put("middle_station", "");
        query.put("result_index", "0");
        query.put("can_query", "Y");
        query.put("isShowWZ", "N");
        query.put("purpose_codes", "00");
        query.put("channel", "E");
        return getJson(urlWithQuery("/lcquery/queryG", query));
    }

    private HttpUrl urlWithQuery(String path, Map<String, String> query) {
        HttpUrl base = Optional.ofNullable(HttpUrl.parse(baseUrl + path))
            .orElseThrow(() -> new IllegalArgumentException("无效 12306 地址"));
        HttpUrl.Builder builder = base.newBuilder();
        query.forEach(builder::addQueryParameter);
        return builder.build();
    }

    private String resolveStationCode(Map<String, Station> stations, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 站点不能为空");
        }
        String trimmed = value.trim();
        if (stations.containsKey(trimmed.toUpperCase(Locale.ROOT))) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        Optional<Station> exact = stations.values().stream()
            .filter(station -> station.name().equals(trimmed)
                || station.pinyin().equalsIgnoreCase(trimmed)
                || station.shortPinyin().equalsIgnoreCase(trimmed))
            .findFirst();
        if (exact.isPresent()) {
            return exact.get().code();
        }
        return stations.values().stream()
            .filter(station -> station.name().contains(trimmed))
            .findFirst()
            .map(Station::code)
            .orElseThrow(() -> new IllegalArgumentException("无法识别站点: " + value));
    }

    private String stationName(Map<String, String> stationMap, String code) {
        return stationMap == null ? code : stationMap.getOrDefault(code, code);
    }

    private String stationDisplay(Map<String, Station> stations, String code) {
        Station station = stations == null ? null : stations.get(code);
        return station == null ? code : station.name() + "(" + station.code() + ")";
    }

    private String seat(String[] parts, int index) {
        if (index >= parts.length || parts[index] == null || parts[index].isBlank()) {
            return "--";
        }
        return parts[index];
    }

    private String resolveDate(SkillRequest request) {
        String raw = rawUserMessage(request);
        if (raw.contains("后天")) {
            return LocalDate.now().plusDays(2).toString();
        }
        if (raw.contains("明天")) {
            return LocalDate.now().plusDays(1).toString();
        }
        if (raw.contains("今天")) {
            return LocalDate.now().toString();
        }
        return resolveDateValue(stringParam(request, "date"));
    }

    private String resolveDateValue(String value) {
        if (value == null || value.isBlank()) {
            return LocalDate.now().toString();
        }
        try {
            LocalDate parsed = LocalDate.parse(value.trim());
            LocalDate today = LocalDate.now();
            return parsed.isBefore(today) ? today.toString() : parsed.toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date 格式必须是 yyyy-MM-dd");
        }
    }

    private String rawUserMessage(SkillRequest request) {
        if (request == null || request.context() == null || request.context().sessionAttributes() == null) {
            return "";
        }
        Object raw = request.context().sessionAttributes().get("rawUserMessage");
        return raw == null ? "" : String.valueOf(raw);
    }

    private String stringParam(SkillRequest request, String key) {
        Object value = request.parameters().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private String firstNonBlankValue(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String field(JsonNode node, String fieldName) {
        if (node == null || node.path(fieldName).isMissingNode() || node.path(fieldName).isNull()) {
            return "";
        }
        return node.path(fieldName).asText("");
    }

    private String emptyAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean hasTransferIntent(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return raw.contains("中转") || raw.contains("换乘") || raw.contains("转车") || raw.contains("联程");
    }

    private boolean boolParam(SkillRequest request, String key) {
        Object value = request.parameters().get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text) || "Y".equalsIgnoreCase(text);
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

    private String trimTrailingSlash(String value) {
        String trimmed = value == null || value.isBlank() ? "https://kyfw.12306.cn" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private record Station(String name, String code, String pinyin, String shortPinyin) {}

    private record SeatType(String name, String shortCode) {}

    private record SeatPrice(String seatName, String shortCode, String count, double amount, Integer discount) {}

    private record TransferSegment(
        String date,
        String trainNo,
        String trainCode,
        String fromStation,
        String toStation,
        String startTime,
        String arriveTime,
        String duration,
        Map<String, String> seats,
        List<SeatPrice> prices
    ) {}

    private record TransferOption(
        String duration,
        String waitTime,
        String date,
        String startTime,
        String arriveDate,
        String arriveTime,
        String fromStation,
        String middleStation,
        String toStation,
        boolean sameTrain,
        boolean sameStation,
        List<TransferSegment> segments
    ) {}

    private record TicketRow(
        String trainNo,
        String trainCode,
        String fromStation,
        String toStation,
        String startTime,
        String arriveTime,
        String duration,
        String businessSeat,
        String firstSeat,
        String secondSeat,
        String softSleeper,
        String hardSleeper,
        String hardSeat,
        String noSeat,
        String fromStationNo,
        String toStationNo,
        List<SeatPrice> prices
    ) {}
}
