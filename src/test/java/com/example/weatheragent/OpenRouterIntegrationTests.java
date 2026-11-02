package com.example.weatheragent;

import com.example.weatheragent.service.WeatherService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// Use the real OpenAI SDK and Spring AI model, but send requests to localhost.
// This verifies OpenRouter's wire format without a real key, quota or internet access.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.openai.api-key=test-openrouter-key",
        "spring.ai.openai.timeout=1s",
        "chat.stream.timeout-ms=20000"
})
class OpenRouterIntegrationTests {
    private static final JsonMapper json = JsonMapper.builder().build();
    private static final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Captured> requests = new ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer provider = startProvider();

    @LocalServerPort int port;
    @TempDir Path temporaryFiles;
    @MockitoBean WeatherService weatherService;
    @Autowired ChatMemory chatMemory;
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry properties) {
        properties.add("spring.ai.openai.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort() + "/api/v1");
    }

    @BeforeEach
    void reset() {
        replies.clear();
        requests.clear();
    }

    @AfterAll
    static void stop() {
        provider.stop(0);
        workers.shutdownNow();
    }

    @Test
    void sendsTheOpenRouterModelKeyAndCorrectCompletionsPath() throws Exception {
        answer("Hello from Qwen!", 10, 5);
        var response = post("/api/chat", "Hello", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("answer").asString()).isEqualTo("Hello from Qwen!");
        var request = requests.remove();
        assertThat(request.path()).isEqualTo("/api/v1/chat/completions");
        assertThat(request.authorization()).isEqualTo("Bearer test-openrouter-key");
        assertThat(request.body().path("model").asString()).isEqualTo("qwen/qwen3.7-flash");
        // Qwen receives tools without the previous Nano-specific parallel setting.
        assertThat(request.body().has("parallel_tool_calls")).isFalse();
        assertThat(request.body().path("tools")).anySatisfy(tool -> {
            var function = tool.path("function");
            assertThat(function.path("name").asString()).isEqualTo("searchProducts");
            var parameters = function.path("parameters");
            assertThat(parameters.path("properties").has("query")).isTrue();
            assertThat(parameters.path("properties").has("maxPrice")).isTrue();
            assertThat(parameters.path("required")).extracting(item -> item.asString())
                    .contains("query").doesNotContain("maxPrice");
        });
    }

    @Test
    void nativeToolLoopChecksAccessWithoutSendingTheFileContents() throws Exception {
        Path file = Files.writeString(temporaryFiles.resolve("style.css"), "body-content-must-not-be-sent");
        requestTool("getFileInfo", json.createObjectNode().put("path", file.toString()));
        answer("Yes, style.css is present and readable.", 10, 5);

        var response = post("/api/chat/stream", "Are you able to access " + file + "?", null);

        assertThat(response.body()).contains("getFileInfo called:", "getFileInfo returned:", "readable: true", "Final answer:");
        assertThat(response.body()).doesNotContain("body-content-must-not-be-sent");
        assertThat(requests).hasSize(2);
        assertThat(List.copyOf(requests).get(1).body().toString()).doesNotContain("body-content-must-not-be-sent");
    }

    @Test
    void nativeToolLoopEditsCssUsingOnlyASnippetAndASmallReplacement() throws Exception {
        String oldBlock = ".btn-primary {\n    background-color: var(--primary-color);\n}";
        String newBlock = ".btn-primary {\n    background-color: red;\n}";
        String original = "/* unrelated-file-header */\n" + ".layout { margin: 0; }\n".repeat(200)
                + oldBlock + "\n.btn-primary:hover { background-color: blue; }\n"
                + ".footer { padding: 10px; }\n".repeat(200) + "/* unrelated-file-footer */\n";
        Path file = Files.writeString(temporaryFiles.resolve("style.css"), original);
        String version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8)));
        requestTool("searchInFile", json.createObjectNode().put("path", file.toString()).put("searchText", ".btn-primary"));
        requestTool("replaceText", json.createObjectNode().put("path", file.toString())
                .put("oldText", oldBlock).put("newText", newBlock).put("expectedVersion", version));
        answer("Changed the primary button background to red.", 10, 5);

        var response = post("/api/chat/stream", "In " + file + ", change the primary button background to red.", null);

        assertThat(response.body()).containsSubsequence("searchInFile called:", "searchInFile returned:",
                "replaceText called:", "replaceText returned:", "verified: true", "replacements: 1", "Final answer:", "Model calls: 3");
        assertThat(response.body()).doesNotContain("readFile called:", "updateFile called:", "unrelated-file-header", "unrelated-file-footer");
        assertThat(Files.readString(file)).isEqualTo(original.replace(oldBlock, newBlock));
        assertThat(requests).hasSize(3);
        // Inspect what the real SDK sends on subsequent rounds, not only what the UI prints.
        for (var captured : requests) {
            assertThat(captured.body().toString()).doesNotContain("unrelated-file-header", "unrelated-file-footer");
        }
        var finalMessages = List.copyOf(requests).get(2).body().path("messages");
        assertThat(finalMessages).anySatisfy(message -> {
            assertThat(message.path("role").asString()).isEqualTo("tool");
            var result = json.readTree(message.path("content").asString());
            assertThat(result.path("operation").asString()).isEqualTo("replaceText");
            assertThat(result.path("verified").asBoolean()).isTrue();
            assertThat(result.has("content")).isFalse();
        });
    }

    @Test
    void nativeToolLoopListsDirectoryAndReturnsOnlyEntryMetadataToTheModel() throws Exception {
        Path directory = Files.createDirectory(temporaryFiles.resolve("portfolio"));
        Files.createDirectory(directory.resolve("assets"));
        Files.writeString(directory.resolve("index.html"), "file-body-must-stay-on-disk");
        requestTool("listDirectory", json.createObjectNode().put("path", directory.toString()));
        answer("The folder contains assets and index.html.", 10, 5);

        var response = post("/api/chat/stream", "List the files and folders in " + directory, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).containsSubsequence("listDirectory called:", "listDirectory returned:",
                "assets", "index.html", "Final answer:", "Model calls: 2", "Done.");
        assertThat(response.body()).doesNotContain("file-body-must-stay-on-disk");
        assertThat(requests).hasSize(2);
        var captured = List.copyOf(requests);
        assertThat(captured.get(0).body().path("tools")).anySatisfy(tool -> {
            var function = tool.path("function");
            assertThat(function.path("name").asString()).isEqualTo("listDirectory");
            assertThat(function.path("parameters").path("required")).extracting(item -> item.asString())
                    .contains("path").doesNotContain("offset");
        });
        assertThat(captured.get(1).body().path("messages")).anySatisfy(message -> {
            assertThat(message.path("role").asString()).isEqualTo("tool");
            var result = json.readTree(message.path("content").asString());
            assertThat(result.path("operation").asString()).isEqualTo("listDirectory");
            assertThat(result.path("success").asBoolean()).isTrue();
            assertThat(result.path("entries")).extracting(entry -> entry.path("type").asString())
                    .containsExactly("directory", "file");
            assertThat(result.toString()).doesNotContain("file-body-must-stay-on-disk");
        });
    }

    @Test
    void nativeToolLoopCreatesReadsAndUpdatesActualFilesInATemporaryDirectory() throws Exception {
        Path directory = temporaryFiles.resolve("website").toAbsolutePath();
        Path file = directory.resolve("index.html");
        requestTool("createDirectory", json.createObjectNode().put("path", directory.toString()));
        requestTool("createFile", json.createObjectNode().put("path", file.toString()).put("content", "<h1>Hello</h1>"));
        requestTool("readFile", json.createObjectNode().put("path", file.toString()));
        requestTool("updateFile", json.createObjectNode().put("path", file.toString()).put("content", "<h1>Welcome</h1>"));
        requestTool("readFile", json.createObjectNode().put("path", file.toString()));
        answer("Created the page and updated its heading to Welcome.", 10, 5);

        var response = post("/api/chat/stream", "Create a page, read it, update its heading and read it again.", null);

        assertThat(response.body()).containsSubsequence("createDirectory called:", "createDirectory returned:",
                "createFile called:", "createFile returned:", "readFile called:", "content: <h1>Hello</h1>",
                "updateFile called:", "updateFile returned:", "readFile called:", "content: <h1>Welcome</h1>",
                "Final answer:", "updated its heading to Welcome", "Tokens: input 60, output 30, total 90. Model calls: 6", "Done.");
        assertThat(Files.readString(file)).isEqualTo("<h1>Welcome</h1>");
        assertThat(requests).hasSize(6);
        var tools = requests.peek().body().path("tools");
        assertThat(tools).extracting(tool -> tool.path("function").path("name").asString())
                .contains("createDirectory", "createFile", "readFile", "updateFile")
                .doesNotContain("deleteFile", "deleteDirectory");
        var finalMessages = List.copyOf(requests).get(5).body().path("messages");
        assertThat(finalMessages).anySatisfy(message -> {
            assertThat(message.path("role").asString()).isEqualTo("tool");
            assertThat(message.path("content").asString()).contains("<h1>Welcome</h1>");
        });
        // Both verified results reach the model and the text stream without extra model calls.
        for (String operation : List.of("createFile", "updateFile")) {
            assertThat(finalMessages).anySatisfy(message -> {
                assertThat(message.path("role").asString()).isEqualTo("tool");
                var result = json.readTree(message.path("content").asString());
                assertThat(result.path("operation").asString()).isEqualTo(operation);
                assertThat(result.path("success").asBoolean()).isTrue();
                assertThat(result.path("verified").asBoolean()).isTrue();
            });
        }
        assertThat(response.body()).contains("verified: true");
    }

    @Test
    void nativeToolLoopStreamsTheFinalAnswerAndAggregatesUsage() throws Exception {
        when(weatherService.getCurrentWeather("Mumbai")).thenReturn(json.readTree("""
                {"location":{"name":"Mumbai"},"current":{"temperature_2m":24,"relative_humidity_2m":70,"weather_code":0},
                 "current_units":{"temperature_2m":"°C","relative_humidity_2m":"%"}}
                """));
        var toolMessage = json.createObjectNode().put("role", "assistant");
        toolMessage.putArray("tool_calls").addObject().put("id", "weather-1").put("type", "function")
                .putObject("function").put("name", "getCurrentWeather")
                .put("arguments", json.createObjectNode().put("city", "Mumbai").toString());
        completion(toolMessage.toString(), "tool_calls", 10, 5);
        answer("Mumbai is 24°C with clear skies, according to Open-Meteo.", 20, 8);

        var response = post("/api/chat/stream", "Weather in Mumbai?", null);

        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("text/plain");
        assertThat(response.body()).containsSubsequence("Waiting for the model (call 1)",
                "getCurrentWeather called: city: Mumbai", "Weather in Mumbai: temperature 24°C",
                "Waiting for the model (call 2)", "Final answer:", "Mumbai is 24°C",
                "Tokens: input 30, output 13, total 43. Model calls: 2", "Done.");
        assertThat(requests).hasSize(2);
        var secondMessages = List.copyOf(requests).get(1).body().path("messages");
        assertThat(secondMessages).anySatisfy(message -> {
            assertThat(message.path("role").asString()).isEqualTo("tool");
            assertThat(message.path("content").asString()).contains("temperature_2m", "24");
        });
    }

    @Test
    void largeFileCodeRemainsInTheCurrentLoopAndVisibleAnswerButNotTheNextUserTurn() throws Exception {
        Path file = temporaryFiles.resolve("generated.html");
        String code = "<p>GENERATED_FILE_BODY_SENTINEL</p>\n".repeat(200);
        String fullAnswer = "Created the file at " + file + ":\n```html\n" + code + "```\n";
        requestTool("createFile", json.createObjectNode().put("path", file.toString()).put("content", code));
        answer(fullAnswer, 10, 5);

        var first = post("/api/chat/stream", "Create generated.html.", null);

        assertThat(first.body()).contains("Final answer:\n" + fullAnswer, "Done.");
        assertThat(Files.readString(file)).isEqualTo(code);
        String conversationId = first.headers().firstValue("X-Conversation-Id").orElseThrow();
        var saved = chatMemory.get(conversationId);
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0)).isInstanceOf(UserMessage.class);
        assertThat(saved.get(0).getText()).isEqualTo("Create generated.html.");
        assertThat(saved.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(saved.get(1).getText()).contains(file.toString(), "\"verified\":true")
                .doesNotContain("GENERATED_FILE_BODY_SENTINEL");

        answer("The file was created at " + file + ".", 10, 5);
        var second = post("/api/chat", "Where was the file created?", conversationId);

        assertThat(second.statusCode()).isEqualTo(200);
        var captured = List.copyOf(requests);
        assertThat(captured).hasSize(3); // No summarization call was added.
        // The existing tool loop still receives the ORIGINAL full generated arguments.
        assertThat(captured.get(1).body().path("messages").toString()).contains("GENERATED_FILE_BODY_SENTINEL");
        // Only the later user request receives the compact assistant memory entry.
        assertThat(captured.get(2).body().path("messages").toString())
                .contains(file.toString(), "Create generated.html.", "Where was the file created?")
                .doesNotContain("GENERATED_FILE_BODY_SENTINEL", "```html");
    }

    @Test
    void conversationMemoryIsSentToTheNewProvider() throws Exception {
        answer("Got it, your city is Saharanpur.", 10, 5);
        var first = post("/api/chat", "My city is Saharanpur.", null);
        String conversationId = json.readTree(first.body()).path("conversationId").asString();
        answer("You live in Saharanpur.", 20, 5);
        post("/api/chat/stream", "Where do I live?", conversationId);
        var messages = List.copyOf(requests).get(1).body().path("messages");
        assertThat(messages).anySatisfy(message -> assertThat(message.path("content").asString())
                .isEqualTo("My city is Saharanpur."));
        assertThat(messages).anySatisfy(message -> assertThat(message.path("content").asString())
                .isEqualTo("Got it, your city is Saharanpur."));
    }

    @Test
    void missingProviderUsageDoesNotBecomeAFalseZero() throws Exception {
        replies.add(new Reply(200, """
                {"id":"test","object":"chat.completion","created":1,"model":"qwen/qwen3.7-flash",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}]}
                """, 0));
        var response = post("/api/chat/stream", "Hello", null);
        assertThat(response.body()).contains("Final answer:", "Hello!", "Tokens: unavailable", "Model calls: 1", "Done.");
    }

    @Test
    void anInvalidKeyProducesAClearErrorWithoutExposingProviderDetails() throws Exception {
        replies.add(error(401));
        var response = post("/api/chat/stream", "Hello", null);
        assertThat(response.body()).contains("OpenRouter rejected API access (HTTP 401)", "API key and permissions", "Done.")
                .doesNotContain("private-provider-detail");
        assertThat(requests).hasSize(1);
    }

    @Test
    void aTransient503RetryDoesNotDoubleCountTheLogicalModelCall() throws Exception {
        replies.add(error(503));
        answer("Recovered!", 10, 5);
        var response = post("/api/chat/stream", "Hello", null);
        assertThat(response.body()).contains("Recovered!", "Tokens: input 10, output 5, total 15. Model calls: 1", "Done.");
        assertThat(requests).hasSize(2);
    }

    @Test
    void slowProviderRequestsTimeOutAndFinishTheTextStream() throws Exception {
        replies.add(new Reply(200, "{}", 2500));
        replies.add(new Reply(200, "{}", 2500));
        var response = post("/api/chat/stream", "Hello", null);
        assertThat(response.body()).contains("Error: The model request timed out", "Tokens: unavailable", "Done.");
        assertThat(requests).hasSize(2); // Initial attempt plus the configured one retry.
    }

    private HttpResponse<String> post(String path, String message, String conversationId) throws Exception {
        var body = json.createObjectNode().put("message", message);
        if (conversationId != null) body.put("conversationId", conversationId);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void answer(String answer, int input, int output) {
        completion(json.createObjectNode().put("role", "assistant").put("content", answer).toString(), "stop", input, output);
    }

    private void requestTool(String name, JsonNode arguments) {
        var message = json.createObjectNode().put("role", "assistant");
        message.putArray("tool_calls").addObject().put("id", "tool-" + replies.size()).put("type", "function")
                .putObject("function").put("name", name).put("arguments", arguments.toString());
        completion(message.toString(), "tool_calls", 10, 5);
    }

    private void completion(String message, String finish, int input, int output) {
        replies.add(new Reply(200, """
                {"id":"test","object":"chat.completion","created":1,"model":"qwen/qwen3.7-flash",
                 "choices":[{"index":0,"message":%s,"finish_reason":"%s"}],
                 "usage":{"prompt_tokens":%d,"completion_tokens":%d,"total_tokens":%d}}
                """.formatted(message, finish, input, output, input + output), 0));
    }

    private Reply error(int status) {
        return new Reply(status, "{\"error\":{\"message\":\"private-provider-detail\",\"code\":" + status + "}}", 0);
    }

    private static HttpServer startProvider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                requests.add(new Captured(exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        json.readTree(exchange.getRequestBody().readAllBytes())));
                Reply reply = replies.poll();
                if (reply == null) reply = new Reply(500, "{}", 0);
                try {
                    if (reply.delayMs() > 0) Thread.sleep(reply.delayMs());
                    byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(reply.status(), body.length);
                    exchange.getResponseBody().write(body);
                } catch (Exception ignored) {
                    // Timeout tests intentionally close the socket before the server replies.
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Reply(int status, String body, long delayMs) {}
    private record Captured(String path, String authorization, JsonNode body) {}
}
