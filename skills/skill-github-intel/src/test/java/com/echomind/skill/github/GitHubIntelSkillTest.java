package com.echomind.skill.github;

import com.echomind.skill.api.SkillRequest;
import com.echomind.skill.api.SkillResult;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubIntelSkillTest {

    private static final OkHttpClient TEST_HTTP = new OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build();

    @Test
    void fetchesRepositoryOverviewFromGithubUrl() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("""
                {
                  "full_name": "openai/codex",
                  "description": "Code agent",
                  "language": "Rust",
                  "stargazers_count": 12000,
                  "forks_count": 600,
                  "open_issues_count": 42,
                  "default_branch": "main",
                  "license": {"spdx_id": "Apache-2.0"},
                  "html_url": "https://github.com/openai/codex",
                  "updated_at": "2026-05-18T00:00:00Z"
                }
                """));
            server.start();

            GitHubIntelSkill skill = new GitHubIntelSkill(server.url("/").toString(), TEST_HTTP, () -> "");
            SkillResult result = skill.execute(request(Map.of(
                "query", "https://github.com/openai/codex"
            ))).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("GitHub repository intelligence");
            assertThat(result.output()).contains("openai/codex");
            assertThat(result.output()).contains("Apache-2.0");
            assertThat(server.takeRequest().getPath()).isEqualTo("/repos/openai/codex");
        }
    }

    @Test
    void sendsOptionalGithubTokenWhenProvided() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("""
                {"full_name":"openai/codex","stargazers_count":1}
                """));
            server.start();

            GitHubIntelSkill skill = new GitHubIntelSkill(server.url("/").toString(), TEST_HTTP, () -> "secret-token");
            SkillResult result = skill.execute(request(Map.of(
                "owner", "openai",
                "repo", "codex",
                "mode", "repo"
            ))).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer secret-token");
        }
    }

    @Test
    void searchesRepositories() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("""
                {
                  "total_count": 1,
                  "items": [
                    {
                      "full_name": "spring-projects/spring-boot",
                      "description": "Spring Boot",
                      "language": "Java",
                      "stargazers_count": 77000,
                      "html_url": "https://github.com/spring-projects/spring-boot",
                      "updated_at": "2026-05-01T00:00:00Z"
                    }
                  ]
                }
                """));
            server.start();

            GitHubIntelSkill skill = new GitHubIntelSkill(server.url("/").toString(), TEST_HTTP, () -> "");
            SkillResult result = skill.execute(request(Map.of(
                "mode", "search",
                "query", "spring boot",
                "limit", 3
            ))).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("GitHub repository search");
            assertThat(result.output()).contains("spring-projects/spring-boot");
            assertThat(server.takeRequest().getPath()).contains("/search/repositories?");
        }
    }

    @Test
    void listsReleases() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("""
                [
                  {
                    "name": "v1.2.3",
                    "tag_name": "v1.2.3",
                    "published_at": "2026-05-10T00:00:00Z",
                    "html_url": "https://github.com/acme/tool/releases/tag/v1.2.3",
                    "body": "Bug fixes and performance improvements."
                  }
                ]
                """));
            server.start();

            GitHubIntelSkill skill = new GitHubIntelSkill(server.url("/").toString(), TEST_HTTP, () -> "");
            SkillResult result = skill.execute(request(Map.of(
                "mode", "releases",
                "owner", "acme",
                "repo", "tool"
            ))).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("GitHub releases");
            assertThat(result.output()).contains("Bug fixes");
            assertThat(server.takeRequest().getPath()).contains("/repos/acme/tool/releases?");
        }
    }

    @Test
    void searchesIssuesAndPullRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("""
                {
                  "total_count": 1,
                  "items": [
                    {
                      "number": 7,
                      "title": "Fix flaky build",
                      "state": "open",
                      "updated_at": "2026-05-11T00:00:00Z",
                      "html_url": "https://github.com/acme/tool/issues/7"
                    }
                  ]
                }
                """));
            server.start();

            GitHubIntelSkill skill = new GitHubIntelSkill(server.url("/").toString(), TEST_HTTP, () -> "");
            SkillResult result = skill.execute(request(Map.of(
                "mode", "issues",
                "owner", "acme",
                "repo", "tool",
                "query", "flaky"
            ))).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("Issue #7");
            assertThat(server.takeRequest().getPath()).contains("/search/issues?");
        }
    }

    @Test
    void rejectsNonGithubUrls() {
        GitHubIntelSkill skill = new GitHubIntelSkill("https://api.github.com", TEST_HTTP, () -> "");

        SkillResult result = skill.execute(request(Map.of(
            "query", "http://127.0.0.1/private"
        ))).join();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("arbitrary URLs are rejected");
    }

    @Test
    void reportsHttpAndJsonFailures() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\":\"Not Found\"}"));
            server.start();

            GitHubIntelSkill skill = new GitHubIntelSkill(server.url("/").toString(), TEST_HTTP, () -> "");
            SkillResult result = skill.execute(request(Map.of(
                "owner", "missing",
                "repo", "repo",
                "mode", "repo"
            ))).join();

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).contains("HTTP 404");
        }
    }

    private static MockResponse json(String body) {
        return new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(body);
    }

    private SkillRequest request(Map<String, Object> parameters) {
        return new SkillRequest(parameters, null, null);
    }
}
