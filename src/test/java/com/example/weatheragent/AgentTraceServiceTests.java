package com.example.weatheragent;

import com.example.weatheragent.dto.ChatDto;
import com.example.weatheragent.service.AgentTraceService;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.completions.CompletionUsage;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTraceServiceTests {
    private final ObservationRegistry registry = ObservationRegistry.create();
    private AgentTraceService traces;

    @BeforeEach
    void setup() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ObservationRegistry> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(registry);
        traces = new AgentTraceService(provider);
        registry.observationConfig().observationHandler(traces);
    }

    @Test
    void sumsIndividualRoundsAndPreservesProviderTotalsWithoutDoubleCounting() {
        var events = new ArrayList<ChatDto.StreamEvent>();
        traces.run("chat", "request", events::add, () -> false, () -> {
            // The provider total can include thinking tokens in addition to input/output.
            model(new DefaultUsage(10, 4, 20));
            model(new DefaultUsage(30, 6, 40));
            return "answer";
        });
        assertThat(usage(events)).isEqualTo(new ChatDto.Usage(2, 40L, 10L, 60L, true));
        assertThat(events).extracting(ChatDto.StreamEvent::type)
                .containsExactly("started", "model_started", "model_started", "answer", "usage", "done");
    }

    @Test
    void missingOpenRouterMetadataDoesNotBecomeAFalseZeroTotal() {
        var events = new ArrayList<ChatDto.StreamEvent>();
        traces.run("chat", "request", events::add, () -> false, () -> {
            model(new DefaultUsage(10, 5));
            model(new EmptyUsage());
            return "answer";
        });
        assertThat(usage(events)).isEqualTo(new ChatDto.Usage(2, null, null, null, false));
    }

    @Test
    void readsOpenRouterCompletionUsageMetadata() {
        var events = new ArrayList<ChatDto.StreamEvent>();
        traces.run("chat", "request", events::add, () -> false, () -> {
            var nativeUsage = CompletionUsage.builder().promptTokens(100).completionTokens(20).totalTokens(150).build();
            model(new DefaultUsage(100, 20, 150, nativeUsage));
            return "answer";
        });
        assertThat(usage(events)).isEqualTo(new ChatDto.Usage(1, 100L, 20L, 150L, true));
    }

    @Test
    void failedModelCallIsCountedButDoesNotInventUsage() {
        var events = new ArrayList<ChatDto.StreamEvent>();
        assertThatThrownBy(() -> traces.run("chat", "request", events::add, () -> false, () -> {
            var context = modelContext();
            Observation.createNotStarted("test.model", () -> context, registry).observe(() -> {
                throw new IllegalStateException("provider failed");
            });
            return "unreachable";
        })).isInstanceOf(IllegalStateException.class);
        assertThat(events).extracting(ChatDto.StreamEvent::type)
                .containsExactly("started", "model_started", "error", "usage", "done");
        assertThat(usage(events)).isEqualTo(new ChatDto.Usage(1, null, null, null, false));
    }

    @ParameterizedTest
    @MethodSource("providerFailures")
    void explainsWrappedProviderFailuresWithoutReturningRawExceptionMessages(Exception cause, String expected) {
        var events = new ArrayList<ChatDto.StreamEvent>();
        var wrapped = new RuntimeException("Failed to generate content: private-request-detail", cause);
        assertThatThrownBy(() -> traces.run("chat", "request", events::add, () -> false, () -> {
            throw wrapped;
        })).isSameAs(wrapped);
        var error = (ChatDto.StreamError) events.stream().filter(event -> event.type().equals("error"))
                .findFirst().orElseThrow().data();
        assertThat(error.message()).contains(expected).doesNotContain("private-request-detail", "private-key");
        assertThat(events).extracting(ChatDto.StreamEvent::type)
                .containsExactly("started", "error", "usage", "done");
    }

    static Stream<Arguments> providerFailures() {
        return Stream.of(
                Arguments.of(new RuntimeException(new java.net.SocketTimeoutException("private-key")), "timed out"),
                Arguments.of(new java.net.UnknownHostException("private-key"), "DNS"),
                Arguments.of(new java.net.ConnectException("private-key"), "Cannot connect"),
                Arguments.of(new javax.net.ssl.SSLHandshakeException("private-key"), "Java certificates"),
                Arguments.of(providerError(401), "API key and permissions"),
                Arguments.of(providerError(403), "API key and permissions"),
                Arguments.of(providerError(402), "credits"),
                Arguments.of(providerError(429), "quota"),
                Arguments.of(providerError(503), "temporarily unavailable"),
                Arguments.of(providerError(504), "timed out"),
                Arguments.of(providerError(400), "configured model"));
    }

    private static OpenAIServiceException providerError(int status) {
        var error = mock(OpenAIServiceException.class);
        when(error.statusCode()).thenReturn(status);
        when(error.getMessage()).thenReturn("private-key");
        return error;
    }

    @Test
    void emitsRealToolResultsAndMarksErrorObjectsAsFailed() {
        var events = new ArrayList<ChatDto.StreamEvent>();
        traces.run("chat", "request", events::add, () -> false, () -> {
            var context = ToolCallingObservationContext.builder()
                    .toolDefinition(ToolDefinition.builder().name("calculate").description("Arithmetic")
                            .inputSchema("{}").build())
                    .toolCallArguments("{\"firstNumber\":5,\"secondNumber\":0}").build();
            Observation.createNotStarted("test.tool", () -> context, registry).observe(() ->
                    context.setToolCallResult("{\"error\":\"Cannot divide by zero\"}"));
            return "The calculation failed.";
        });
        assertThat(events).extracting(ChatDto.StreamEvent::type)
                .containsExactly("started", "tool_started", "tool_failed", "answer", "usage", "done");
        var failed = (ChatDto.ToolExecution) events.get(2).data();
        assertThat(failed.step()).isEqualTo(1);
        assertThat(failed.arguments().path("secondNumber").asInt()).isZero();
        assertThat(failed.result().path("error").asString()).isEqualTo("Cannot divide by zero");
        assertThat(failed.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void fileMemoryRecordsAreRequestScopedAndNeverIncludeCodeOrInventVerifiedSuccess() {
        assertThat(traces.fileResultsForMemory("chat")).isNull();
        traces.run("chat", "request", event -> {}, () -> false, () -> {
            var context = ToolCallingObservationContext.builder()
                    .toolDefinition(ToolDefinition.builder().name("createFile").description("Create file")
                            .inputSchema("{}").build())
                    .toolCallArguments("{\"path\":\"index.html\",\"content\":\"GENERATED_CODE\"}").build();
            Observation.createNotStarted("test.tool", () -> context, registry).observe(() -> context.setToolCallResult(
                    "{\"success\":false,\"path\":\"/project/index.html\",\"verified\":false,\"writeCompleted\":true,"
                            + "\"errorCode\":\"VERIFICATION_FAILED\",\"error\":\"Cannot verify\"}"));
            String memory = traces.fileResultsForMemory("chat");
            assertThat(memory).contains("/project/index.html", "\"success\":false", "\"verified\":false", "VERIFICATION_FAILED")
                    .doesNotContain("GENERATED_CODE", "\"content\"");
            assertThat(traces.fileResultsForMemory("different-chat")).isNull();
            return "The file write could not be verified.";
        });
        assertThat(traces.fileResultsForMemory("chat")).isNull();
        traces.run("chat", "next-request", event -> {}, () -> false, () -> {
            assertThat(traces.fileResultsForMemory("chat")).isNull();
            return "Ordinary follow-up.";
        });
    }

    @Test
    void stoppingAnObservationTwiceDoesNotCountItsTokensTwice() {
        var events = new ArrayList<ChatDto.StreamEvent>();
        traces.run("chat", "request", events::add, () -> false, () -> {
            var context = modelContext();
            Observation.createNotStarted("test.model", () -> context, registry).observe(() ->
                    context.setResponse(response(new DefaultUsage(3, 2))));
            traces.onStop(context);
            return "answer";
        });
        assertThat(usage(events)).isEqualTo(new ChatDto.Usage(1, 3L, 2L, 5L, true));
    }

    @Test
    void cancellationPreventsTheNextModelCallFromStarting() {
        var cancelled = new AtomicBoolean();
        var events = new ArrayList<ChatDto.StreamEvent>();
        var executed = new AtomicBoolean();
        assertThatThrownBy(() -> traces.run("chat", "request", events::add, cancelled::get, () -> {
            cancelled.set(true);
            Observation.createNotStarted("test.model", this::modelContext, registry)
                    .observe(() -> executed.set(true));
            return "unreachable";
        })).isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(executed).isFalse();
        assertThat(events).extracting(ChatDto.StreamEvent::type).containsExactly("started");
    }

    @Test
    void concurrentRequestsKeepTheirUsageAndIdsSeparate() throws Exception {
        var first = new ArrayList<ChatDto.StreamEvent>();
        var second = new ArrayList<ChatDto.StreamEvent>();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> runConcurrent("first", 10, first, barrier));
            var b = executor.submit(() -> runConcurrent("second", 30, second, barrier));
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
        }
        assertThat(first).allSatisfy(event -> {
            assertThat(event.requestId()).isEqualTo("first");
            assertThat(event.conversationId()).isEqualTo("chat-first");
        });
        assertThat(second).allSatisfy(event -> assertThat(event.requestId()).isEqualTo("second"));
        assertThat(usage(first)).isEqualTo(new ChatDto.Usage(1, 10L, 2L, 12L, true));
        assertThat(usage(second)).isEqualTo(new ChatDto.Usage(1, 30L, 2L, 32L, true));
    }

    private void runConcurrent(String id, int tokens, List<ChatDto.StreamEvent> events, CyclicBarrier barrier) {
        traces.run("chat-" + id, id, events::add, () -> false, () -> {
            try { barrier.await(3, TimeUnit.SECONDS); }
            catch (Exception e) { throw new IllegalStateException(e); }
            model(new DefaultUsage(tokens, 2));
            return "answer";
        });
    }

    private void model(Usage usage) {
        var context = modelContext();
        Observation.createNotStarted("test.model", () -> context, registry).observe(() ->
                context.setResponse(response(usage)));
    }

    private ChatModelObservationContext modelContext() {
        return ChatModelObservationContext.builder().prompt(new Prompt("hello")).provider("test").build();
    }

    private ChatResponse response(Usage usage) {
        return new ChatResponse(List.of(), ChatResponseMetadata.builder().usage(usage).build());
    }

    private ChatDto.Usage usage(List<ChatDto.StreamEvent> events) {
        return (ChatDto.Usage) events.stream().filter(event -> event.type().equals("usage"))
                .findFirst().orElseThrow().data();
    }
}
