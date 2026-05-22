package com.echomind.skill.github;

import com.echomind.skill.api.Skill;
import com.echomind.skill.api.SkillMetadata;
import com.echomind.skill.api.SkillRequest;
import com.echomind.skill.api.SkillResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub developer intelligence skill.
 *
 * <p>Uses the public GitHub REST API and optional {@code GITHUB_TOKEN} / {@code GH_TOKEN}
 * for higher rate limits. It never fetches user-supplied arbitrary URLs; URLs are only
 * parsed when they point at github.com.</p>
 */
public class GitHubIntelSkill implements Skill {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile(
        "https?://[^\\s\\p{Z}<>\"'）)】]+",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern OWNER_REPO_PATTERN = Pattern.compile(
        "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\\.git)?$"
    );
    private static final int MAX_BODY_CHARS = 1_000;
    private static final OkHttpClient DEFAULT_HTTP = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build();

    private final String apiBaseUrl;
    private final OkHttpClient http;
    private final Supplier<String> tokenSupplier;

    public GitHubIntelSkill() {
        this("https://api.github.com", DEFAULT_HTTP, GitHubIntelSkill::defaultToken);
    }

    GitHubIntelSkill(String apiBaseUrl, OkHttpClient http, Supplier<String> tokenSupplier) {
        this.apiBaseUrl = trimTrailingSlash(apiBaseUrl);
        this.http = http;
        this.tokenSupplier = tokenSupplier;
    }

    @Override
    public SkillMetadata metadata() {
        return new SkillMetadata(
            "github-intel",
            "1.0.0",
            "查询 GitHub 开发者情报，包括仓库概览、仓库搜索、Release 发布记录、Issue 和 PR 线索。无需密钥即可使用；配置 GITHUB_TOKEN 或 GH_TOKEN 仅用于提高限流额度。",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "mode", Map.of(
                        "type", "string",
                        "enum", List.of("repo", "search", "releases", "issues"),
                        "description", "repo for repository overview, search for repository search, releases for releases, issues for issue/PR search"
                    ),
                    "query", Map.of(
                        "type", "string",
                        "description", "Search text, GitHub URL, owner/repo coordinate, or issue search keywords"
                    ),
                    "owner", Map.of("type", "string", "description", "GitHub repository owner"),
                    "repo", Map.of("type", "string", "description", "GitHub repository name"),
                    "limit", Map.of("type", "integer", "description", "Maximum results, 1-20")
                )
            ),
            List.of(),
            "EchoMind",
            List.of("github", "repository", "release", "issue", "developer", "code", "开源", "仓库"),
            List.of(
                "github", "GitHub", "仓库", "开源项目", "repo", "repository", "release", "版本发布",
                "issue", "issues", "PR", "pull request", "star", "stars", "项目情报", "查仓库", "源码仓库"
            ),
            Map.of(
                "github", List.of("GitHub", "github", "gh", "开源仓库", "源码仓库"),
                "repository", List.of("仓库", "项目", "repo", "repository"),
                "release", List.of("release", "版本发布", "发布记录", "changelog"),
                "issue", List.of("issue", "issues", "问题", "缺陷", "PR", "pull request")
            )
        );
    }

    @Override
    public CompletableFuture<SkillResult> execute(SkillRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                String query = stringParam(request, "query");
                Optional<URI> userUrl = firstHttpUrl(query);
                if (userUrl.isPresent() && !isGitHubUrl(userUrl.get())) {
                    return SkillResult.failure("github-intel only accepts github.com URLs; arbitrary URLs are rejected.",
                        System.currentTimeMillis() - start);
                }

                RepositoryRef ref = resolveRepositoryRef(request, query).orElse(null);
                String mode = resolveMode(stringParam(request, "mode"), ref);
                int limit = intParam(request, "limit", 5, 1, 20);

                return switch (mode) {
                    case "repo" -> fetchRepository(ref, start);
                    case "releases" -> fetchReleases(ref, limit, start);
                    case "issues" -> fetchIssues(ref, query, limit, start);
                    case "search" -> searchRepositories(query, limit, start);
                    default -> SkillResult.failure("mode must be one of repo, search, releases, issues",
                        System.currentTimeMillis() - start);
                };
            } catch (Exception e) {
                return SkillResult.failure("GitHub intelligence failed: " + e.getMessage(),
                    System.currentTimeMillis() - start);
            }
        });
    }

    private SkillResult fetchRepository(RepositoryRef ref, long start) throws Exception {
        if (ref == null) {
            return SkillResult.failure("owner/repo is required for repo mode. Provide owner + repo, owner/repo, or a GitHub repository URL.",
                System.currentTimeMillis() - start);
        }
        JsonNode repo = getJson(url("repos", ref.owner(), ref.repo()));
        StringBuilder sb = new StringBuilder();
        sb.append("GitHub repository intelligence\n");
        appendField(sb, "Repository", text(repo, "full_name"));
        appendField(sb, "Description", text(repo, "description"));
        appendField(sb, "Language", text(repo, "language"));
        appendField(sb, "Stars", repo.path("stargazers_count").asText(""));
        appendField(sb, "Forks", repo.path("forks_count").asText(""));
        appendField(sb, "Open issues", repo.path("open_issues_count").asText(""));
        appendField(sb, "Default branch", text(repo, "default_branch"));
        appendField(sb, "License", repo.path("license").path("spdx_id").asText(""));
        appendField(sb, "Homepage", text(repo, "homepage"));
        appendField(sb, "Updated at", text(repo, "updated_at"));
        appendField(sb, "URL", text(repo, "html_url"));
        return SkillResult.success(sb.toString().trim(), System.currentTimeMillis() - start);
    }

    private SkillResult searchRepositories(String query, int limit, long start) throws Exception {
        if (query == null || query.isBlank()) {
            return SkillResult.failure("query is required for search mode", System.currentTimeMillis() - start);
        }
        JsonNode results = getJson(urlWithQuery(
            new String[]{"search", "repositories"},
            Map.of("q", query, "per_page", String.valueOf(limit))
        ));
        StringBuilder sb = new StringBuilder();
        sb.append("GitHub repository search\n");
        appendField(sb, "Query", query);
        appendField(sb, "Total count", results.path("total_count").asText(""));
        JsonNode items = results.path("items");
        appendItems(sb, items, limit, item -> {
            StringBuilder line = new StringBuilder();
            line.append(item.path("full_name").asText(""));
            appendInline(line, "stars", item.path("stargazers_count").asText(""));
            appendInline(line, "language", item.path("language").asText(""));
            appendInline(line, "updated", item.path("updated_at").asText(""));
            appendInline(line, "url", item.path("html_url").asText(""));
            String description = item.path("description").asText("");
            if (!description.isBlank()) {
                line.append("\n   ").append(description);
            }
            return line.toString();
        });
        return SkillResult.success(sb.toString().trim(), System.currentTimeMillis() - start);
    }

    private SkillResult fetchReleases(RepositoryRef ref, int limit, long start) throws Exception {
        if (ref == null) {
            return SkillResult.failure("owner/repo is required for releases mode",
                System.currentTimeMillis() - start);
        }
        JsonNode releases = getJson(urlWithQuery(
            new String[]{"repos", ref.owner(), ref.repo(), "releases"},
            Map.of("per_page", String.valueOf(limit))
        ));
        StringBuilder sb = new StringBuilder();
        sb.append("GitHub releases\n");
        appendField(sb, "Repository", ref.owner() + "/" + ref.repo());
        appendItems(sb, releases, limit, release -> {
            StringBuilder line = new StringBuilder();
            String name = release.path("name").asText("");
            String tag = release.path("tag_name").asText("");
            line.append(name.isBlank() ? tag : name + " (" + tag + ")");
            appendInline(line, "published", release.path("published_at").asText(""));
            appendInline(line, "url", release.path("html_url").asText(""));
            String body = cleanText(release.path("body").asText(""));
            if (!body.isBlank()) {
                line.append("\n   ").append(limitText(body, 500));
            }
            return line.toString();
        });
        return SkillResult.success(sb.toString().trim(), System.currentTimeMillis() - start);
    }

    private SkillResult fetchIssues(RepositoryRef ref, String query, int limit, long start) throws Exception {
        if (ref == null) {
            return SkillResult.failure("owner/repo is required for issues mode",
                System.currentTimeMillis() - start);
        }
        String issueQuery = "repo:" + ref.owner() + "/" + ref.repo();
        if (query != null && !query.isBlank() && resolveRepositoryRef(null, query).isEmpty()) {
            issueQuery += " " + query.trim();
        }
        JsonNode results = getJson(urlWithQuery(
            new String[]{"search", "issues"},
            Map.of("q", issueQuery, "per_page", String.valueOf(limit))
        ));
        StringBuilder sb = new StringBuilder();
        sb.append("GitHub issue and PR intelligence\n");
        appendField(sb, "Query", issueQuery);
        appendField(sb, "Total count", results.path("total_count").asText(""));
        appendItems(sb, results.path("items"), limit, item -> {
            String type = item.has("pull_request") ? "PR" : "Issue";
            StringBuilder line = new StringBuilder(type)
                .append(" #").append(item.path("number").asText(""))
                .append(": ").append(item.path("title").asText(""));
            appendInline(line, "state", item.path("state").asText(""));
            appendInline(line, "updated", item.path("updated_at").asText(""));
            appendInline(line, "url", item.path("html_url").asText(""));
            return line.toString();
        });
        return SkillResult.success(sb.toString().trim(), System.currentTimeMillis() - start);
    }

    private JsonNode getJson(HttpUrl url) throws Exception {
        Request.Builder builder = new Request.Builder()
            .url(url)
            .header("User-Agent", "EchoMindSkill/github-intel")
            .header("Accept", "application/vnd.github+json");
        String token = tokenSupplier.get();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token.trim());
        }
        try (Response response = http.newCall(builder.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalArgumentException("GitHub API request failed: HTTP " + response.code()
                    + bodySuffix(body));
            }
            return MAPPER.readTree(body);
        }
    }

    private HttpUrl url(String... pathSegments) {
        HttpUrl base = Optional.ofNullable(HttpUrl.parse(apiBaseUrl))
            .orElseThrow(() -> new IllegalArgumentException("Invalid GitHub API base URL: " + apiBaseUrl));
        HttpUrl.Builder builder = base.newBuilder();
        for (String segment : pathSegments) {
            builder.addPathSegment(segment);
        }
        return builder.build();
    }

    private HttpUrl urlWithQuery(String[] pathSegments, Map<String, String> query) {
        HttpUrl.Builder builder = url(pathSegments).newBuilder();
        for (var entry : query.entrySet()) {
            builder.addQueryParameter(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private Optional<RepositoryRef> resolveRepositoryRef(SkillRequest request, String query) {
        String owner = request == null ? "" : stringParam(request, "owner");
        String repo = request == null ? "" : stringParam(request, "repo");
        if (!owner.isBlank() && !repo.isBlank()) {
            return Optional.of(new RepositoryRef(owner.trim(), trimGitSuffix(repo.trim())));
        }
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        String trimmed = query.trim();
        Optional<URI> url = firstHttpUrl(trimmed);
        if (url.isPresent() && isGitHubUrl(url.get())) {
            String[] parts = url.get().getPath().split("/");
            if (parts.length >= 3 && !parts[1].isBlank() && !parts[2].isBlank()) {
                return Optional.of(new RepositoryRef(parts[1], trimGitSuffix(parts[2])));
            }
        }
        Matcher matcher = OWNER_REPO_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            String[] parts = trimmed.split("/", 2);
            return Optional.of(new RepositoryRef(parts[0], trimGitSuffix(parts[1])));
        }
        return Optional.empty();
    }

    private String resolveMode(String rawMode, RepositoryRef ref) {
        if (rawMode != null && !rawMode.isBlank()) {
            return rawMode.trim().toLowerCase(Locale.ROOT);
        }
        return ref == null ? "search" : "repo";
    }

    private Optional<URI> firstHttpUrl(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = HTTP_URL_PATTERN.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(URI.create(matcher.group()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private boolean isGitHubUrl(URI uri) {
        String host = uri.getHost();
        return host != null && ("github.com".equalsIgnoreCase(host) || "www.github.com".equalsIgnoreCase(host));
    }

    private String stringParam(SkillRequest request, String key) {
        if (request == null || request.parameters() == null) {
            return "";
        }
        Object value = request.parameters().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private int intParam(SkillRequest request, String key, int defaultValue, int min, int max) {
        String value = stringParam(request, key);
        if (value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String defaultToken() {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            token = System.getenv("GH_TOKEN");
        }
        return token == null ? "" : token;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.github.com";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String trimGitSuffix(String value) {
        return value != null && value.endsWith(".git")
            ? value.substring(0, value.length() - 4)
            : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value)) {
            sb.append(label).append(": ").append(value).append("\n");
        }
    }

    private static void appendInline(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value)) {
            sb.append(" | ").append(label).append(": ").append(value);
        }
    }

    private static void appendItems(StringBuilder sb, JsonNode items, int limit, ItemFormatter formatter) {
        if (items == null || !items.isArray() || items.isEmpty()) {
            sb.append("No results.\n");
            return;
        }
        List<String> lines = new ArrayList<>();
        int count = 0;
        for (JsonNode item : items) {
            if (count++ >= limit) {
                break;
            }
            lines.add(count + ". " + formatter.format(item));
        }
        sb.append(String.join("\n", lines)).append("\n");
    }

    private static String cleanText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String limitText(String text, int maxChars) {
        String cleaned = cleanText(text);
        if (cleaned.length() <= maxChars) {
            return cleaned;
        }
        return cleaned.substring(0, maxChars) + "...";
    }

    private static String bodySuffix(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String limited = limitText(body, MAX_BODY_CHARS);
        return ": " + limited;
    }

    private record RepositoryRef(String owner, String repo) {}

    @FunctionalInterface
    private interface ItemFormatter {
        String format(JsonNode item);
    }
}
