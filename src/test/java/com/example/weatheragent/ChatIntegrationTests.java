package com.example.weatheragent;

import com.example.weatheragent.dto.ChatDto;
import com.example.weatheragent.service.CalculatorService;
import com.example.weatheragent.service.WeatherService;
import com.example.weatheragent.service.WebSearchService;
import com.example.weatheragent.service.ShoppingService;
import com.example.weatheragent.service.FileSystemService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

// Exercise the real controller and service, replacing the LLM so tests use no API quota.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "spring.ai.openai.api-key=test-key",
        "chat.stream.timeout-ms=1000"
})
class ChatIntegrationTests {
    @LocalServerPort int port;
    @MockitoBean ChatModel chatModel;
    @MockitoSpyBean WeatherService weatherService;
    @MockitoSpyBean CalculatorService calculatorService;
    @MockitoBean WebSearchService webSearchService;
    @MockitoBean ShoppingService shoppingService;
    @MockitoBean FileSystemService fileSystemService;
    @Autowired ObservationRegistry observations;

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void configureModelOptions() {
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
    }

    @Test
    void passesTheExactPromptAndReturnsTheLlmAnswer() throws Exception {
        // Include spaces, a newline, Unicode and braces to detect accidental prompt changes.
        String prompt = "  Explain this JSON: {\"city\":\"मुंबई\"}\nKeep it simple.  ";
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("This JSON contains a city name.")))));

        HttpResponse<String> response = post(prompt);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("answer").asString())
                .isEqualTo("This JSON contains a city name.");
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captured.capture());
        assertThat(captured.getValue().getUserMessage().getText()).isEqualTo(prompt);
        assertThat(captured.getValue().getSystemMessage().getText()).contains("You are an AI agent");
        verify(weatherService, never()).getCurrentWeather(anyString());
        verify(calculatorService, never()).calculate(anyDouble(), anyDouble(), anyString());
        verify(webSearchService, never()).searchWeb(anyString());
        verifyNoInteractions(shoppingService);
        verifyNoInteractions(fileSystemService);
    }

    @Test
    void rejectsBlankPromptsWithoutCallingTheLlm() throws Exception {
        assertThat(post("   ").statusCode()).isEqualTo(400);
        verifyNoInteractions(chatModel);
    }

    @Test
    void springAiExecutesTheAnnotatedWeatherToolAndSendsItsResultBack() throws Exception {
        // Simulate the model requesting the tool, then returning an answer after seeing its result.
        var weather = json.readTree("""
                {
                  "location": {"name": "Mumbai", "country": "India"},
                  "current": {"temperature_2m": 29, "relative_humidity_2m": 70, "weather_code": 0},
                  "current_units": {"temperature_2m": "°C", "relative_humidity_2m": "%", "weather_code": "wmo code"}
                }
                """);
        doReturn(weather).when(weatherService).getCurrentWeather("Mumbai, India");
        var toolCall = new AssistantMessage.ToolCall("weather-1", "function", "getCurrentWeather",
                "{\"city\":\"Mumbai, India\"}");
        var toolRequest = AssistantMessage.builder().toolCalls(List.of(toolCall)).build();
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(toolRequest))))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Mumbai is 29 C.")))));

        var response = post("What is the weather in Mumbai, India?");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("answer").asString()).isEqualTo("Mumbai is 29 C.");
        verify(weatherService).getCurrentWeather("Mumbai, India");
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(captured.capture());
        assertThat(captured.getAllValues().get(1).getInstructions())
                .anySatisfy(message -> {
                    assertThat(message).isInstanceOf(ToolResponseMessage.class);
                    var result = (ToolResponseMessage) message;
                    var data = json.readTree(result.getResponses().get(0).responseData());
                    assertThat(data.isObject()).isTrue();
                    assertThat(data.path("current").path("temperature_2m").asDouble()).isEqualTo(29);
                    assertThat(data.path("location").path("name").asString()).isEqualTo("Mumbai");
                });
    }

    @Test
    void springAiExecutesTheCalculatorToolAndSendsItsResultBack() throws Exception {
        // The calculator is real; only the model's two responses are simulated.
        var toolCall = new AssistantMessage.ToolCall("calculator-1", "function", "calculate",
                "{\"firstNumber\":24,\"secondNumber\":7,\"operation\":\"multiply\"}");
        var toolRequest = AssistantMessage.builder().toolCalls(List.of(toolCall)).build();
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(toolRequest))))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("24 × 7 = 168.")))));

        var response = post("What is 24 multiplied by 7?");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("answer").asString()).isEqualTo("24 × 7 = 168.");
        verify(calculatorService).calculate(24, 7, "multiply");
        verify(weatherService, never()).getCurrentWeather(anyString());
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(captured.capture());
        assertThat(captured.getAllValues().get(1).getInstructions())
                .anySatisfy(message -> {
                    assertThat(message).isInstanceOf(ToolResponseMessage.class);
                    var toolResponse = (ToolResponseMessage) message;
                    var data = json.readTree(toolResponse.getResponses().get(0).responseData());
                    assertThat(data.path("result").asDouble()).isEqualTo(168);
                });
    }

    @Test
    void springAiExecutesWebSearchAndPassesSourcesBackToTheModel() throws Exception {
        when(webSearchService.searchWeb("Sachin Tendulkar date of birth")).thenReturn(json.readTree("""
                {"results":[{"title":"Player profile","snippet":"Born on 24 April 1973.",
                             "link":"https://example.com/profile"}]}
                """));
        var toolCall = new AssistantMessage.ToolCall("search-1", "function", "searchWeb",
                "{\"query\":\"Sachin Tendulkar date of birth\"}");
        var toolRequest = AssistantMessage.builder().toolCalls(List.of(toolCall)).build();
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(toolRequest))))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Born on 24 April 1973. Source: https://example.com/profile")))));

        var response = post("What is Sachin Tendulkar's current age?");

        assertThat(response.statusCode()).isEqualTo(200);
        verify(webSearchService).searchWeb("Sachin Tendulkar date of birth");
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(captured.capture());
        assertThat(captured.getAllValues().get(0).getSystemMessage().getText())
                .contains("Today's date is " + java.time.LocalDate.now());
        assertThat(captured.getAllValues().get(1).getInstructions())
                .anySatisfy(message -> {
                    assertThat(message).isInstanceOf(ToolResponseMessage.class);
                    var toolResponse = (ToolResponseMessage) message;
                    var data = json.readTree(toolResponse.getResponses().get(0).responseData());
                    assertThat(data.path("results").path(0).path("link").asString())
                            .isEqualTo("https://example.com/profile");
                });
    }

    @Test
    void shoppingToolStreamsItsListingsAndPassesThemBackToTheModel() throws Exception {
        when(shoppingService.searchProducts("laptops with 16GB RAM", 50000.0)).thenReturn(json.readTree("""
                {"currency":"INR","products":[{"title":"Example laptop","price":"₹45,000","priceInr":45000,
                  "source":"Example retailer","link":"https://example.com/laptop"}]}
                """));
        var tool = new AssistantMessage.ToolCall("shopping-1", "function", "searchProducts",
                "{\"query\":\"laptops with 16GB RAM\",\"maxPrice\":50000}");
        observedResponses(reply(AssistantMessage.builder().toolCalls(List.of(tool)).build(), 10, 5, 15),
                reply(new AssistantMessage("Example laptop is listed at ₹45,000. https://example.com/laptop"), 20, 5, 25));

        var response = http.send(streamRequest("Find laptops with 16GB RAM within ₹50,000", null),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).containsSubsequence("searchProducts called:", "searchProducts returned:",
                "priceInr: 45000", "https://example.com/laptop", "Final answer:", "listed at ₹45,000",
                "Tokens: input 30, output 10, total 40. Model calls: 2", "Done.");
        verify(shoppingService).searchProducts("laptops with 16GB RAM", 50000.0);
        verifyNoInteractions(webSearchService);
        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getInstructions()).anySatisfy(message -> {
            assertThat(message).isInstanceOf(ToolResponseMessage.class);
            var toolResponse = (ToolResponseMessage) message;
            var data = json.readTree(toolResponse.getResponses().get(0).responseData());
            assertThat(data.path("products").path(0).path("priceInr").asInt()).isEqualTo(45000);
        });
    }

    @Test
    void remembersMessagesWhenTheReturnedConversationIdIsReused() throws Exception {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Your city is Saharanpur.")))))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("You live in Saharanpur.")))));

        var first = post("My city is Saharanpur.");
        assertThat(first.statusCode()).isEqualTo(200);
        verify(weatherService, never()).getCurrentWeather(anyString());
        verify(calculatorService, never()).calculate(anyDouble(), anyDouble(), anyString());
        verify(webSearchService, never()).searchWeb(anyString());
        String conversationId = json.readTree(first.body()).path("conversationId").asString();
        assertThat(conversationId).isNotBlank();

        var second = post("Which city do I live in?", conversationId);

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(second.body()).path("conversationId").asString()).isEqualTo(conversationId);
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(captured.capture());
        assertThat(captured.getAllValues().get(1).getInstructions())
                .extracting(Message::getText)
                .containsSubsequence("My city is Saharanpur.", "Your city is Saharanpur.", "Which city do I live in?");
    }

    @Test
    void separateConversationIdsDoNotShareMessages() throws Exception {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Your city is Saharanpur.")))))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Please tell me your city.")))))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Please tell me your city.")))));

        String firstId = "chat-" + UUID.randomUUID();
        String secondId = "chat-" + UUID.randomUUID();
        assertThat(post("My city is Saharanpur.", firstId).statusCode()).isEqualTo(200);
        assertThat(post("Which city do I live in?", secondId).statusCode()).isEqualTo(200);
        var fresh = post("Which city do I live in?");
        assertThat(fresh.statusCode()).isEqualTo(200);
        assertThat(json.readTree(fresh.body()).path("conversationId").asString()).isNotIn(firstId, secondId);

        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(3)).call(captured.capture());
        for (Prompt prompt : captured.getAllValues().subList(1, 3)) {
            assertThat(prompt.getInstructions()).extracting(Message::getText)
                    .doesNotContain("My city is Saharanpur.", "Your city is Saharanpur.");
        }
    }

    @Test
    void followUpWeatherRequestIncludesMemoryAndStillExecutesTheTool() throws Exception {
        var weather = json.readTree("""
                {"location":{"name":"Saharanpur"},"current":{"temperature_2m":27}}
                """);
        doReturn(weather).when(weatherService).getCurrentWeather("Saharanpur");
        var toolCall = new AssistantMessage.ToolCall("weather-memory", "function", "getCurrentWeather",
                "{\"city\":\"Saharanpur\"}");
        var toolRequest = AssistantMessage.builder().toolCalls(List.of(toolCall)).build();
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Your city is Saharanpur.")))))
                .thenReturn(new ChatResponse(List.of(new Generation(toolRequest))))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("It is 27 C in Saharanpur.")))));

        var first = post("My city is Saharanpur.");
        verify(weatherService, never()).getCurrentWeather(anyString());
        String conversationId = json.readTree(first.body()).path("conversationId").asString();
        var followUp = post("What is the current weather there?", conversationId);

        assertThat(followUp.statusCode()).isEqualTo(200);
        assertThat(json.readTree(followUp.body()).path("answer").asString()).isEqualTo("It is 27 C in Saharanpur.");
        verify(weatherService).getCurrentWeather("Saharanpur");
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(3)).call(captured.capture());
        assertThat(captured.getAllValues().get(1).getInstructions()).extracting(Message::getText)
                .containsSubsequence("My city is Saharanpur.", "Your city is Saharanpur.", "What is the current weather there?");
        assertThat(captured.getAllValues().get(2).getInstructions())
                .anySatisfy(message -> {
                    assertThat(message).isInstanceOf(ToolResponseMessage.class);
                    var toolResponse = (ToolResponseMessage) message;
                    assertThat(toolResponse.getResponses().get(0).responseData()).contains("temperature_2m");
                });
    }

    @Test
    void streamReturnsReadableTextWithToolResultsFinalAnswerAndTotalUsage() throws Exception {
        doReturn(json.readTree("""
                {"location":{"name":"Mumbai"},"current":{"temperature_2m":29,"relative_humidity_2m":70,"weather_code":0},
                 "current_units":{"temperature_2m":"°C","relative_humidity_2m":"%"}}
                """)).when(weatherService).getCurrentWeather("Mumbai");
        var weather = new AssistantMessage.ToolCall("weather", "function", "getCurrentWeather", "{\"city\":\"Mumbai\"}");
        var calculator = new AssistantMessage.ToolCall("calculator", "function", "calculate",
                "{\"firstNumber\":24,\"secondNumber\":7,\"operation\":\"multiply\"}");
        observedResponses(
                reply(AssistantMessage.builder().toolCalls(List.of(weather, calculator)).build(), 10, 4, 20),
                reply(new AssistantMessage("Mumbai is 29°C. 24 × 7 = 168."), 30, 6, 40));

        var response = http.send(streamRequest("Weather in Mumbai and 24 times 7", null), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("text/plain", "UTF-8");
        String conversationId = response.headers().firstValue("X-Conversation-Id").orElseThrow();
        assertThat(response.headers().firstValue("X-Request-Id").orElseThrow()).isNotBlank();
        assertThat(response.body()).containsSubsequence(
                "Conversation: " + conversationId,
                "Waiting for the model (call 1)...",
                "getCurrentWeather called: city: Mumbai",
                "Weather in Mumbai: temperature 29°C, humidity 70%, weather code 0",
                "calculate called: firstNumber: 24, secondNumber: 7, operation: multiply",
                "calculate returned: result: 168.0",
                "Waiting for the model (call 2)...",
                "Final answer:", "Mumbai is 29°C. 24 × 7 = 168.",
                "Tokens: input 40, output 10, total 60. Model calls: 2", "Done.");
        assertThat(response.body()).doesNotContain("event:", "data:", "{\"type\"");
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    void progressArrivesBeforeTheModelFinishesAndMemoryWorksAcrossEndpoints() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation ->
                observed(invocation.getArgument(0), () -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return reply(new AssistantMessage("Your city is Saharanpur."), 10, 5, 15);
                }));

        String conversationId;
        var response = http.send(streamRequest("My city is Saharanpur.", null), HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            conversationId = response.headers().firstValue("X-Conversation-Id").orElseThrow();
            assertThat(reader.readLine()).isEqualTo("Conversation: " + conversationId);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(release.getCount()).isEqualTo(1);
            release.countDown();
            assertThat(reader.lines().toList()).contains("Final answer:", "Your city is Saharanpur.", "Done.");
        } finally {
            release.countDown();
        }

        observedResponses(reply(new AssistantMessage("You live in Saharanpur."), 20, 5, 25));
        assertThat(post("Where do I live?", conversationId).statusCode()).isEqualTo(200);
        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getInstructions()).extracting(Message::getText)
                .containsSubsequence("My city is Saharanpur.", "Your city is Saharanpur.", "Where do I live?");
    }

    @Test
    void streamShowsAToolFailureAndStillReturnsTheFinalAnswer() throws Exception {
        var tool = new AssistantMessage.ToolCall("divide", "function", "calculate",
                "{\"firstNumber\":5,\"secondNumber\":0,\"operation\":\"divide\"}");
        observedResponses(reply(AssistantMessage.builder().toolCalls(List.of(tool)).build(), 10, 5, 15),
                reply(new AssistantMessage("Cannot divide by zero."), 20, 5, 25));
        var response = http.send(streamRequest("Divide 5 by 0", null), HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).containsSubsequence("calculate called:", "calculate failed:", "Final answer:",
                "Cannot divide by zero.", "Tokens: input 30, output 10, total 40. Model calls: 2", "Done.");
    }

    @Test
    void streamReportsModelFailuresAndUnknownUsageWithoutLeakingExceptionDetails() throws Exception {
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation ->
                observed(invocation.getArgument(0), () -> { throw new IllegalStateException("secret-provider-detail"); }));
        var response = http.send(streamRequest("hello", null), HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).containsSubsequence("Waiting for the model (call 1)", "Error:", "Tokens: unavailable", "Done.")
                .doesNotContain("secret-provider-detail", "Final answer:");
    }

    @Test
    void streamExplainsOpenRouterQuotaErrorsEvenWhenSpringWrapsTheCause() throws Exception {
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation ->
                observed(invocation.getArgument(0), () -> {
                    throw new RuntimeException("Failed to generate content",
                            com.openai.errors.RateLimitException.builder()
                                    .headers(com.openai.core.http.Headers.builder().build())
                                    .error(com.openai.models.ErrorObject.builder().code("rate_limit_exceeded")
                                            .param("test").message("private-provider-detail").type("rate_limit").build())
                                    .build());
                }));
        var response = http.send(streamRequest("hello", null), HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).contains("Error:", "quota", "429", "Tokens: unavailable", "Done.")
                .doesNotContain("private-provider-detail");
    }

    @Test
    void streamRejectsBlankPromptsBeforeStartingTheAgent() throws Exception {
        var response = http.send(streamRequest(" ", null), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        verifyNoInteractions(chatModel);
    }

    @Test
    void streamTimeoutClosesTheConnectionAndInterruptsItsWorker() throws Exception {
        var interrupted = new CountDownLatch(1);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation ->
                observed(invocation.getArgument(0), () -> {
                    try { new CountDownLatch(1).await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException e) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                        throw new java.util.concurrent.CancellationException();
                    }
                    throw new IllegalStateException("The timeout did not interrupt the worker");
                }));
        var response = http.send(streamRequest("hello", null), HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).contains("Conversation:", "Waiting for the model (call 1)").doesNotContain("Done.");
        assertThat(interrupted.await(3, TimeUnit.SECONDS)).isTrue();
        verify(chatModel).call(any(Prompt.class));
    }

    // Mock models do not create observations themselves, so simulate the provider's real hook.
    private void observedResponses(ChatResponse... responses) {
        var index = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation ->
                observed(invocation.getArgument(0), () -> responses[index.getAndIncrement()]));
    }

    private ChatResponse observed(Prompt prompt, Supplier<ChatResponse> call) {
        var context = ChatModelObservationContext.builder().prompt(prompt).provider("test").build();
        return Observation.createNotStarted("test.model", () -> context, observations).observe(() -> {
            ChatResponse response = call.get();
            context.setResponse(response);
            return response;
        });
    }

    private ChatResponse reply(AssistantMessage message, int input, int output, int total) {
        return new ChatResponse(List.of(new Generation(message)), ChatResponseMetadata.builder()
                .usage(new DefaultUsage(input, output, total)).build());
    }

    private HttpRequest streamRequest(String message, String conversationId) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/chat/stream"))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        new ChatDto.Request(message, conversationId)))).build();
    }

    private HttpResponse<String> post(String prompt) throws Exception {
        return post(prompt, null);
    }

    private HttpResponse<String> post(String prompt, String conversationId) throws Exception {
        String body = json.writeValueAsString(new ChatDto.Request(prompt, conversationId));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
