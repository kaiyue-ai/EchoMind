package com.echomind.skill.websearch;

import com.echomind.skill.api.SkillRequest;
import com.echomind.skill.api.SkillResult;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchSkillTest {

    private static final OkHttpClient TEST_HTTP = new OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build();

    @Test
    void readsPublicUrlBeforeKeywordSearch() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("""
                    <!doctype html>
                    <html>
                      <head>
                        <title>测试文章</title>
                        <meta name="description" content="这是一篇用于测试的文章">
                      </head>
                      <body>
                        <nav>导航内容</nav>
                        <article><h1>正文标题</h1><p>这里是真正的网页正文。</p></article>
                      </body>
                    </html>
                    """));
            server.start();

            WebSearchSkill skill = new WebSearchSkill("http://searxng.invalid", TEST_HTTP, true);
            SkillResult result = skill.execute(request("搜一下" + server.url("/article").url())).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("Fetched web page");
            assertThat(result.output()).contains("Title: 测试文章");
            assertThat(result.output()).contains("这里是真正的网页正文");
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void readsGithubRepositoryThroughGithubApi() throws Exception {
        try (MockWebServer githubApi = new MockWebServer()) {
            githubApi.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "full_name": "NousResearch/hermes-agent",
                      "description": "The agent that grows with you",
                      "language": "Python",
                      "stargazers_count": 1234,
                      "forks_count": 56,
                      "open_issues_count": 7,
                      "homepage": "https://hermes-agent.nousresearch.com/",
                      "updated_at": "2026-05-16T00:00:00Z",
                      "license": {"spdx_id": "MIT"},
                      "topics": ["agent", "skills"]
                    }
                    """));
            githubApi.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/markdown; charset=utf-8")
                .setBody("""
                    # Hermes Agent

                    The self-improving AI agent built by Nous Research.
                    """));
            githubApi.start();

            WebSearchSkill skill = new WebSearchSkill(
                "http://searxng.invalid",
                TEST_HTTP,
                false,
                githubApi.url("/").toString()
            );
            SkillResult result = skill.execute(request("访问https://github.com/NousResearch/hermes-agent#")).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("Fetched GitHub repository");
            assertThat(result.output()).contains("NousResearch/hermes-agent");
            assertThat(result.output()).contains("The agent that grows with you");
            assertThat(result.output()).contains("The self-improving AI agent");
            assertThat(githubApi.takeRequest().getPath()).isEqualTo("/repos/NousResearch/hermes-agent");
            assertThat(githubApi.takeRequest().getHeader("Accept")).isEqualTo("application/vnd.github.raw");
        }
    }

    @Test
    void fallsBackToSearchWhenDirectUrlFetchFails() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><title>Access denied</title></html>"));
            server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "answers": [],
                      "infoboxes": [],
                      "results": [
                        {
                          "title": "Fallback Article",
                          "content": "Search still found a useful summary.",
                          "url": "https://example.com/article"
                        }
                      ],
                      "unresponsive_engines": []
                    }
                    """));
            server.start();

            WebSearchSkill skill = new WebSearchSkill(server.url("/").toString(), TEST_HTTP, true);
            SkillResult result = skill.execute(request("搜一下" + server.url("/blocked").url())).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("Direct URL fetch failed");
            assertThat(result.output()).contains("Fallback query");
            assertThat(result.output()).contains("Fallback Article");
            assertThat(server.takeRequest().getPath()).isEqualTo("/blocked");
            assertThat(server.takeRequest().getPath()).startsWith("/search?q=");
        }
    }

    @Test
    void reportsAntiBotInsteadOfPretendingArticleMissing() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><head><meta id=\"zh-zse-ck\"></head><body>知乎</body></html>"));
            server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("{}"));
            server.start();

            WebSearchSkill skill = new WebSearchSkill(server.url("/").toString(), TEST_HTTP, true);
            SkillResult result = skill.execute(request(server.url("/p/18143971299").toString())).join();

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).contains("HTTP 403");
            assertThat(result.error()).contains("反爬");
            assertThat(result.error()).contains("Fallback search failed");
        }
    }

    @Test
    void rejectsPrivateNetworkUrlsInProductionConstructor() {
        WebSearchSkill skill = new WebSearchSkill("http://searxng.invalid");

        SkillResult result = skill.execute(request("读取 http://127.0.0.1/private")).join();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("内网");
    }

    @Test
    void keywordSearchStillUsesSearxng() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {
                      "answers": [],
                      "infoboxes": [],
                      "results": [
                        {
                          "title": "EchoMind",
                          "content": "AI Agent platform",
                          "url": "https://example.com/echomind"
                        }
                      ],
                      "unresponsive_engines": []
                    }
                    """));
            server.start();

            WebSearchSkill skill = new WebSearchSkill(server.url("/").toString(), TEST_HTTP, true);
            SkillResult result = skill.execute(request("EchoMind 最新信息")).join();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).contains("Web search results");
            assertThat(result.output()).contains("EchoMind");
            assertThat(server.takeRequest().getPath()).contains("/search?q=EchoMind");
        }
    }

    private SkillRequest request(String query) {
        return new SkillRequest(Map.of("query", query), null, null);
    }
}
