package com.example.weatheragent;

import com.example.weatheragent.service.WebSearchService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

// Exercise the real Serper HTTP request against a local server; no live credits are used.
class WebSearchServiceTests {
    private HttpServer server;
    private WebSearchService service;
    private String apiUrl;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestKey = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/search";
        service = new WebSearchService("test-key", apiUrl);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void postsTheQueryWithAuthenticationAndReturnsOnlyThreeCompactResults() {
        respond(200, """
                {"organic":[
                  {"title":"A source","snippet":"A factual answer.","link":"https://example.com/fact","position":1},
                  {"title":"Second","snippet":"More facts.","link":"https://example.com/2"},
                  {"title":"Third","snippet":"More facts.","link":"https://example.com/3"},
                  {"title":"Fourth","snippet":"More facts.","link":"https://example.com/4"}
                ], "relatedSearches":[{"query":"unneeded extra data"}]}
                """);

        String query = "Sachin Tendulkar \"date of birth\" मुंबई";
        var result = service.searchWeb(query);

        assertThat(result.path("results").size()).isEqualTo(3);
        assertThat(result.path("results").path(0).path("link").asString())
                .isEqualTo("https://example.com/fact");
        assertThat(result.path("results").path(0).path("snippet").asString()).isEqualTo("A factual answer.");
        assertThat(result.path("results").path(0).size()).isEqualTo(3);
        assertThat(result.has("relatedSearches")).isFalse();
        assertThat(requestBody.get().path("q").asString()).isEqualTo(query);
        assertThat(requestBody.get().path("num").asInt()).isEqualTo(3);
        assertThat(requestMethod.get()).isEqualTo("POST");
        assertThat(requestKey.get()).isEqualTo("test-key");
        assertThat(contentType.get()).startsWith("application/json");
    }

    @Test
    void reportsEmptySearchResults() {
        respond(200, "{\"organic\":[]}");

        assertThat(service.searchWeb("a question").path("error").asString()).contains("No search results");
    }

    @Test
    void returnsAClearQuotaErrorWithoutRetrying() {
        respond(429, "{\"message\":\"Rate limit exceeded\"}");

        assertThat(service.searchWeb("a question").path("error").asString()).contains("HTTP 429", "credits");
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void rejectsBlankQueriesWithoutCallingSerper() {
        assertThat(service.searchWeb("   ").has("error")).isTrue();
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void reportsAMissingKeyWithoutCallingSerper() {
        var unconfigured = new WebSearchService("", apiUrl);
        assertThat(unconfigured.searchWeb("a question").path("error").asString()).contains("key is not configured");
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void reportsAuthenticationErrorsWithoutExposingTheKey() {
        respond(403, "{\"message\":\"Invalid API key\"}");
        var result = service.searchWeb("a question");
        assertThat(result.path("error").asString()).contains("HTTP 403", "API key").doesNotContain("test-key");
    }

    private void respond(int status, String body) {
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            requestMethod.set(exchange.getRequestMethod());
            requestKey.set(exchange.getRequestHeaders().getFirst("X-API-KEY"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
    }
}
