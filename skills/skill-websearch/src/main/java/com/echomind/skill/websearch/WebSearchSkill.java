package com.echomind.skill.websearch;

import com.echomind.skill.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页搜索技能 —— 支持直接读取公开网页，也支持通过 SearXNG 进行关键词搜索。
 *
 * <p>SearXNG 聚合 Google、Bing、DuckDuckGo、Baidu、Wikipedia 等多个搜索引擎，
 * 返回结构化 JSON 结果，无需 API 密钥，比单一引擎抓取更稳定可靠。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>URL 优先</b>：输入里包含 http/https 链接时，先直接读取网页正文。</li>
 *   <li><b>安全边界</b>：拒绝内网、localhost 和非 HTTP(S) 地址，避免 SSRF。</li>
 *   <li><b>SearXNG JSON API</b>：一次请求聚合 5+ 引擎，格式稳定，无 HTML 抓取风险。</li>
 *   <li><b>超时控制</b>：15 秒连接/读取超时，SearXNG 聚合多引擎需要稍长等待。</li>
 *   <li><b>明确失败</b>：站点反爬、登录限制或网络失败时返回原因，不伪造正文。</li>
 * </ul>
 *
 * <p>输入参数：
 * <ul>
 *   <li>{@code query}（string，必填）—— 搜索查询关键词</li>
 * </ul>
 *
 * <p>技能标签：search, web, find, lookup, internet, 搜索, 查询, 搜索网络, 网络搜索
 */
public class WebSearchSkill implements Skill {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_EXTRACTED_CHARS = 8_000;
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile(
        "https?://[^\\s\\p{Z}<>\"'）)】]+",
        Pattern.CASE_INSENSITIVE
    );
    private static final OkHttpClient DEFAULT_HTTP = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build();

    private final String searxngUrl;
    private final String githubApiUrl;
    private final OkHttpClient http;
    private final boolean allowPrivateNetwork;

    public WebSearchSkill() {
        this(defaultSearxngUrl());
    }

    WebSearchSkill(String searxngUrl) {
        this(searxngUrl, DEFAULT_HTTP, false);
    }

    WebSearchSkill(String searxngUrl, OkHttpClient http, boolean allowPrivateNetwork) {
        this(searxngUrl, http, allowPrivateNetwork, "https://api.github.com");
    }

    WebSearchSkill(String searxngUrl, OkHttpClient http, boolean allowPrivateNetwork, String githubApiUrl) {
        this.searxngUrl = searxngUrl.endsWith("/") ? searxngUrl.substring(0, searxngUrl.length() - 1) : searxngUrl;
        this.githubApiUrl = githubApiUrl.endsWith("/") ? githubApiUrl.substring(0, githubApiUrl.length() - 1) : githubApiUrl;
        this.http = http;
        this.allowPrivateNetwork = allowPrivateNetwork;
    }

    private static String defaultSearxngUrl() {
        String url = System.getenv("SEARXNG_URL");
        if (url != null && !url.isBlank()) return url;
        url = System.getProperty("searxng.url");
        if (url != null && !url.isBlank()) return url;
        return "http://localhost:8082";
    }

    @Override
    public SkillMetadata metadata() {
        return new SkillMetadata(
            "web-search",
            "1.1.0",
            "Search the internet or read a public web page by URL. When the input contains an http/https URL, fetch and extract that page first. Use keyword search for current information, news, and facts beyond the model knowledge cutoff. Private network URLs are rejected for safety.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of("type", "string", "description", "Search keywords or a public http/https URL to read")
                ),
                "required", List.of("query")
            ),
            List.of(),
            "EchoMind",
            List.of("search", "web", "find", "lookup", "internet", "url", "网页", "链接"),
            List.of("搜索", "搜一下", "查询", "查一下", "联网搜索", "网络搜索", "读取网页", "打开链接", "链接内容", "网址", "URL", "http", "https", "最新", "实时信息", "search web"),
            Map.of(
                "search", List.of("搜索", "搜一下", "搜搜", "查找", "查一下", "查询"),
                "web", List.of("联网", "网络", "网页", "互联网", "网址", "链接", "打开链接"),
                "read", List.of("读取网页", "读网页", "看看链接", "看一下链接"),
                "current", List.of("最新", "实时", "现在")
            )
        );
    }

    @Override
    public CompletableFuture<SkillResult> execute(SkillRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            String query = String.valueOf(request.parameters().getOrDefault("query", ""));
            if (query.isBlank()) {
                return SkillResult.failure("query is required", System.currentTimeMillis() - start);
            }
            try {
                List<URI> urls = extractHttpUrls(query);
                if (!urls.isEmpty()) {
                    return fetchUrl(urls.get(0), query, start);
                }
                return search(query, start);
            } catch (java.net.UnknownHostException e) {
                return SkillResult.failure(
                    "SearXNG service not available at " + searxngUrl + ". Start it with: docker compose up -d searxng",
                    System.currentTimeMillis() - start);
            } catch (Exception e) {
                return SkillResult.failure(
                    "Search failed: " + e.getMessage(),
                    System.currentTimeMillis() - start);
            }
        });
    }

    private SkillResult fetchUrl(URI uri, String originalQuery, long start) throws Exception {
        Optional<GitHubRepository> githubRepository = parseGithubRepository(uri);
        if (githubRepository.isPresent()) {
            return fetchGithubRepository(githubRepository.get(), originalQuery, start);
        }

        URI currentUri = uri;
        for (int redirects = 0; redirects <= 5; redirects++) {
            String validationError = validatePublicHttpUrl(currentUri);
            if (validationError != null) {
                return SkillResult.failure(validationError, System.currentTimeMillis() - start);
            }
            Request httpReq = webPageRequest(currentUri);
            try (Response resp = http.newCall(httpReq).execute()) {
                if (isRedirect(resp.code())) {
                    String location = resp.header("Location");
                    if (location == null || location.isBlank()) {
                        return fallbackSearchForUrl(originalQuery, currentUri,
                            "目标网页返回重定向，但没有提供 Location。URL: " + currentUri, start);
                    }
                    currentUri = currentUri.resolve(location);
                    continue;
                }
                String contentType = resp.header("Content-Type", "");
                String body = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    return fallbackSearchForUrl(originalQuery, currentUri,
                        fetchFailureMessage(currentUri, resp.code(), body), start);
                }
                if (!isReadableContent(contentType, body)) {
                    return fallbackSearchForUrl(originalQuery, currentUri,
                        "无法读取该链接正文：返回内容类型为 "
                            + (contentType.isBlank() ? "unknown" : contentType) + "，不是可解析的文本或 HTML。", start);
                }
                String result = formatFetchedPage(currentUri, contentType, body);
                if (result.isBlank()) {
                    return fallbackSearchForUrl(originalQuery, currentUri,
                        "已打开链接，但页面没有提取到可读正文。可能需要登录、正文由脚本动态渲染，或目标站点做了反爬限制。", start);
                }
                return SkillResult.success(result + fallbackSearchHint(originalQuery, uri), System.currentTimeMillis() - start);
            } catch (Exception e) {
                return fallbackSearchForUrl(originalQuery, currentUri,
                    "直接读取 URL 失败：" + e.getMessage(), start);
            }
        }
        return fallbackSearchForUrl(originalQuery, currentUri,
            "目标网页重定向次数过多，已停止读取。URL: " + uri, start);
    }

    private SkillResult fetchGithubRepository(GitHubRepository repository, String originalQuery, long start) throws Exception {
        String repoUrl = githubApiUrl + "/repos/" + repository.owner() + "/" + repository.repo();
        try (Response repoResp = http.newCall(githubApiRequest(repoUrl, "application/vnd.github+json")).execute()) {
            String body = repoResp.body() != null ? repoResp.body().string() : "";
            if (!repoResp.isSuccessful()) {
                return fallbackSearchForUrl(originalQuery, repository.webUri(),
                    "GitHub API 读取仓库失败：HTTP " + repoResp.code(), start);
            }

            JsonNode repo = MAPPER.readTree(body);
            String readme = fetchGithubReadme(repository);
            return SkillResult.success(formatGithubRepository(repository, repo, readme),
                System.currentTimeMillis() - start);
        } catch (Exception e) {
            return fallbackSearchForUrl(originalQuery, repository.webUri(),
                "GitHub API 读取仓库失败：" + e.getMessage(), start);
        }
    }

    private String fetchGithubReadme(GitHubRepository repository) {
        String readmeUrl = githubApiUrl + "/repos/" + repository.owner() + "/" + repository.repo() + "/readme";
        Request request = githubApiRequest(readmeUrl, "application/vnd.github.raw");
        try (Response resp = http.newCall(request).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                return "";
            }
            return cleanMarkdown(resp.body().string());
        } catch (Exception ignored) {
            return "";
        }
    }

    private Request githubApiRequest(String url, String accept) {
        return new Request.Builder()
            .url(url)
            .header("User-Agent", "EchoMindWebSearch/1.1")
            .header("Accept", accept)
            .build();
    }

    private String formatGithubRepository(GitHubRepository repository, JsonNode repo, String readme) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fetched GitHub repository:\n");
        sb.append("URL: ").append(repository.webUri()).append("\n");
        appendField(sb, "Name", repo.path("full_name").asText(""));
        appendField(sb, "Description", repo.path("description").asText(""));
        appendField(sb, "Language", repo.path("language").asText(""));
        appendField(sb, "Stars", repo.path("stargazers_count").asText(""));
        appendField(sb, "Forks", repo.path("forks_count").asText(""));
        appendField(sb, "Open issues", repo.path("open_issues_count").asText(""));
        appendField(sb, "License", repo.path("license").path("spdx_id").asText(""));
        appendField(sb, "Homepage", repo.path("homepage").asText(""));
        appendField(sb, "Updated at", repo.path("updated_at").asText(""));
        JsonNode topics = repo.path("topics");
        if (topics.isArray() && !topics.isEmpty()) {
            List<String> topicTexts = new ArrayList<>();
            topics.forEach(topic -> topicTexts.add(topic.asText()));
            appendField(sb, "Topics", String.join(", ", topicTexts));
        }
        if (!readme.isBlank()) {
            sb.append("\nREADME:\n").append(limitText(readme, MAX_EXTRACTED_CHARS));
        }
        return sb.toString().trim();
    }

    private void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank() && !"null".equals(value)) {
            sb.append(label).append(": ").append(value).append("\n");
        }
    }

    private Optional<GitHubRepository> parseGithubRepository(URI uri) {
        String host = uri == null ? null : uri.getHost();
        if (host == null) {
            return Optional.empty();
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (!"github.com".equals(lowerHost) && !"www.github.com".equals(lowerHost)) {
            return Optional.empty();
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String[] parts = path.split("/");
        List<String> segments = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                segments.add(part);
            }
        }
        if (segments.size() < 2 || isGithubNonRepoPath(segments.get(0))) {
            return Optional.empty();
        }
        return Optional.of(new GitHubRepository(segments.get(0), segments.get(1)));
    }

    private boolean isGithubNonRepoPath(String firstSegment) {
        String lower = firstSegment.toLowerCase(Locale.ROOT);
        return List.of("topics", "marketplace", "explore", "features", "enterprise", "settings", "login")
            .contains(lower);
    }

    private SkillResult fallbackSearchForUrl(String originalQuery, URI uri, String directFailure, long start) {
        String fallbackQuery = fallbackQueryForUrl(originalQuery, uri);
        try {
            SkillResult searchResult = search(fallbackQuery, start);
            if (searchResult.isSuccess()) {
                String output = "Direct URL fetch failed, so searched the web instead.\n"
                    + "Direct fetch error: " + directFailure + "\n"
                    + "Fallback query: " + fallbackQuery + "\n\n"
                    + searchResult.output();
                return SkillResult.success(output, System.currentTimeMillis() - start);
            }
            return SkillResult.failure(directFailure + "\nFallback search failed: " + searchResult.error(),
                System.currentTimeMillis() - start);
        } catch (Exception e) {
            return SkillResult.failure(directFailure + "\nFallback search failed: " + e.getMessage(),
                System.currentTimeMillis() - start);
        }
    }

    private String fallbackQueryForUrl(String originalQuery, URI uri) {
        Optional<GitHubRepository> githubRepository = parseGithubRepository(uri);
        if (githubRepository.isPresent()) {
            GitHubRepository repo = githubRepository.get();
            return repo.owner() + " " + repo.repo() + " GitHub";
        }
        StringBuilder sb = new StringBuilder();
        if (uri != null && uri.getHost() != null) {
            sb.append(uri.getHost().replace("www.", ""));
        }
        if (uri != null && uri.getPath() != null) {
            String pathTerms = uri.getPath().replaceAll("[/_\\-.]+", " ").trim();
            if (!pathTerms.isBlank()) {
                sb.append(" ").append(pathTerms);
            }
        }
        String extra = removeUrls(originalQuery)
            .replaceAll("(?i)\\b(url|http|https)\\b", " ")
            .replaceAll("[搜索搜一下查询查一下访问打开看看读取网页链接网址]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (!extra.isBlank()) {
            sb.append(" ").append(extra);
        }
        String query = sb.toString().replaceAll("\\s+", " ").trim();
        return query.isBlank() ? String.valueOf(uri) : query;
    }

    private Request webPageRequest(URI uri) {
        return new Request.Builder()
            .url(uri.toString())
            .header("User-Agent", "Mozilla/5.0 (compatible; EchoMindWebSearch/1.1; +https://echomind.local)")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build();
    }

    private boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private SkillResult search(String query, long start) throws Exception {
        String encoded = java.net.URLEncoder.encode(query, "UTF-8");
        String url = searxngUrl + "/search?q=" + encoded + "&format=json&categories=general";
        Request httpReq = new Request.Builder()
            .url(url)
            .header("User-Agent", "EchoMind/1.0")
            .build();

        try (Response resp = http.newCall(httpReq).execute()) {
            if (!resp.isSuccessful()) {
                return SkillResult.failure(
                    "SearXNG returned HTTP " + resp.code(),
                    System.currentTimeMillis() - start);
            }
            String body = resp.body() != null ? resp.body().string() : "";
            String result = formatResults(MAPPER.readTree(body), query);
            return SkillResult.success(result, System.currentTimeMillis() - start);
        }
    }

    private String validatePublicHttpUrl(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return "URL 格式不合法，只支持公开的 http/https 链接。";
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "URL 协议不支持，只允许 http/https。";
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("localhost".equals(host) || host.endsWith(".localhost")) {
            return "出于安全考虑，web-search 不读取 localhost 链接。";
        }
        if (allowPrivateNetwork) {
            return null;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isPrivateAddress(address)) {
                    return "出于安全考虑，web-search 不读取内网、回环或链路本地地址：" + host;
                }
            }
        } catch (Exception e) {
            return "无法解析 URL 主机：" + host;
        }
        return null;
    }

    private boolean isPrivateAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            return first == 10
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 169 && second == 254)
                || (first == 100 && second >= 64 && second <= 127);
        }
        return raw.length == 16 && (raw[0] & 0xfe) == 0xfc;
    }

    private boolean isReadableContent(String contentType, String body) {
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return lowerType.contains("text/")
            || lowerType.contains("html")
            || lowerType.contains("xml")
            || lowerType.contains("json")
            || looksLikeHtml(body);
    }

    private String formatFetchedPage(URI uri, String contentType, String body) {
        if (looksLikeHtml(body) || contentType.toLowerCase(Locale.ROOT).contains("html")) {
            Document doc = Jsoup.parse(body, uri.toString());
            doc.select("script, style, noscript, svg, canvas, iframe, nav, footer, header, form").remove();
            String title = cleanText(doc.title());
            String description = cleanText(metaContent(doc, "description"));
            String text = extractMainText(doc);
            if (text.isBlank() && isLikelyAntiBotPage(body)) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Fetched web page:\n");
            sb.append("URL: ").append(uri).append("\n");
            if (!title.isBlank()) {
                sb.append("Title: ").append(title).append("\n");
            }
            if (!description.isBlank()) {
                sb.append("Description: ").append(description).append("\n");
            }
            sb.append("\nContent:\n").append(limitText(text, MAX_EXTRACTED_CHARS));
            return sb.toString().trim();
        }

        String text = cleanText(body);
        if (text.isBlank()) {
            return "";
        }
        return ("Fetched text content:\nURL: " + uri + "\n\nContent:\n" + limitText(text, MAX_EXTRACTED_CHARS)).trim();
    }

    private String extractMainText(Document doc) {
        StringBuilder text = new StringBuilder();
        for (String selector : List.of("article", "main", "[role=main]", ".Post-RichText", ".article_content", ".blog-content-box")) {
            for (Element element : doc.select(selector)) {
                String candidate = cleanText(element.text());
                if (candidate.length() > text.length()) {
                    text.setLength(0);
                    text.append(candidate);
                }
            }
        }
        if (text.isEmpty()) {
            text.append(cleanText(doc.body() == null ? "" : doc.body().text()));
        }
        return text.toString();
    }

    private String metaContent(Document doc, String name) {
        Element element = doc.selectFirst("meta[name=" + name + "], meta[property=og:" + name + "]");
        return element == null ? "" : element.attr("content");
    }

    private String fetchFailureMessage(URI uri, int statusCode, String body) {
        if (statusCode == 401 || statusCode == 403) {
            return "目标网页拒绝访问（HTTP " + statusCode + "）：可能需要登录、验证码或站点反爬保护。URL: " + uri;
        }
        if (statusCode == 404) {
            return "目标网页不存在或已删除（HTTP 404）。URL: " + uri;
        }
        if (statusCode == 429) {
            return "目标网页请求过于频繁被限流（HTTP 429），稍后再试。URL: " + uri;
        }
        String title = "";
        if (looksLikeHtml(body)) {
            title = cleanText(Jsoup.parse(body).title());
        }
        return "读取网页失败：HTTP " + statusCode + (title.isBlank() ? "" : "，页面标题：" + title) + "。URL: " + uri;
    }

    private String fallbackSearchHint(String originalQuery, URI uri) {
        if (originalQuery == null || originalQuery.equals(uri.toString())) {
            return "";
        }
        String queryWithoutUrl = originalQuery.replace(uri.toString(), "").trim();
        return queryWithoutUrl.isBlank() ? "" : "\n\nNote: 这次优先读取了消息中的 URL，未额外执行关键词搜索。";
    }

    private String removeUrls(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return HTTP_URL_PATTERN.matcher(text).replaceAll(" ");
    }

    private List<URI> extractHttpUrls(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<URI> urls = new ArrayList<>();
        Matcher matcher = HTTP_URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = trimUrlSuffix(matcher.group());
            try {
                URI uri = URI.create(raw);
                if (uri.getScheme() != null && uri.getHost() != null) {
                    urls.add(uri);
                }
            } catch (IllegalArgumentException ignored) {
                // URL 写坏时忽略，后续会走普通搜索。
            }
        }
        return urls;
    }

    private String trimUrlSuffix(String url) {
        String trimmed = url == null ? "" : url.trim();
        while (!trimmed.isEmpty() && "，。！？；;,.!?".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private boolean looksLikeHtml(String body) {
        if (body == null) {
            return false;
        }
        String prefix = body.length() > 500 ? body.substring(0, 500) : body;
        String lower = prefix.toLowerCase(Locale.ROOT);
        return lower.contains("<html") || lower.contains("<!doctype html") || lower.contains("<body");
    }

    private boolean isLikelyAntiBotPage(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("zse-ck")
            || lower.contains("captcha")
            || lower.contains("验证")
            || lower.contains("反爬")
            || lower.contains("access denied");
    }

    private String limitText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars) + "\n\n[内容过长，已截断到 " + maxChars + " 字符]";
    }

    private String cleanMarkdown(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replace("\r\n", "\n")
            .replaceAll("(?is)<script.*?</script>", "")
            .replaceAll("(?is)<style.*?</style>", "")
            .replaceAll("\n{3,}", "\n\n")
            .trim();
    }

    private String formatResults(JsonNode root, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("Web search results for \"").append(query).append("\":\n\n");

        // 1. Direct answers take priority
        JsonNode answers = root.path("answers");
        if (answers.isArray() && !answers.isEmpty()) {
            sb.append("Answer: ");
            sb.append(stripHtml(answers.get(0).path("answer").asText("")));
            sb.append("\n\n");
        }

        // 2. Infoboxes (Wikipedia structured data)
        JsonNode infoboxes = root.path("infoboxes");
        if (infoboxes.isArray() && !infoboxes.isEmpty()) {
            JsonNode infobox = infoboxes.get(0);
            String infoboxContent = infobox.path("content").asText("");
            if (!infoboxContent.isBlank()) {
                sb.append("Info: ").append(stripHtml(infoboxContent)).append("\n\n");
            }
        }

        // 3. Search result snippets
        JsonNode results = root.path("results");
        if (results.isArray()) {
            int count = 0;
            for (JsonNode r : results) {
                if (count >= 5) break;
                String title = stripHtml(r.path("title").asText(""));
                String content = stripHtml(r.path("content").asText(""));
                String url = r.path("url").asText("");
                if (content.isBlank() && title.isBlank()) continue;
                count++;
                sb.append(count).append(". ");
                if (!title.isBlank()) {
                    sb.append("**").append(title).append("**\n");
                }
                if (!content.isBlank()) {
                    sb.append("   ").append(content).append("\n");
                }
                if (!url.isBlank()) {
                    sb.append("   ").append(url).append("\n");
                }
                sb.append("\n");
            }
            if (count == 0) {
                sb.append("(No relevant results found)\n");
            }
        } else {
            sb.append("(No results returned)\n");
        }

        // Append warnings about unresponsive engines
        JsonNode unresponsive = root.path("unresponsive_engines");
        if (unresponsive.isArray() && !unresponsive.isEmpty()) {
            sb.append("Note: some search engines were unavailable.\n");
        }

        return sb.toString().trim();
    }

    private static String stripHtml(String text) {
        return cleanText(text == null ? "" : text.replaceAll("<[^>]+>", ""));
    }

    private static String cleanText(String text) {
        if (text == null || text.isBlank()) return "";
        return text.replaceAll("&amp;", "&")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .replaceAll("&quot;", "\"")
            .replaceAll("&#39;", "'")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private record GitHubRepository(String owner, String repo) {
        URI webUri() {
            return URI.create("https://github.com/" + owner + "/" + repo);
        }
    }
}
