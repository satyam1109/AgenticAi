package com.example.weatheragent.service;

import com.example.weatheragent.dto.ChatDto;
// Previous Gemini-specific types (inactive).
// import com.google.genai.errors.ApiException;
// import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.openai.errors.OpenAIServiceException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
// import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Listens to actual Spring AI executions. It does not run another LLM or replace the tool loop.
@Service
public class AgentTraceService implements ObservationHandler<Observation.Context> {
    private static final Logger log = LoggerFactory.getLogger(AgentTraceService.class);
    private static final Set<String> FILE_TOOLS = Set.of("listDirectory", "createDirectory", "createFile",
            "getFileInfo", "searchInFile", "readFileLines", "readFile", "updateFile", "replaceText");
    private final JsonMapper json = JsonMapper.builder().build();
    private final ObjectProvider<ObservationRegistry> registries;

    // Resolve the registry lazily: Boot registers this handler while creating that registry.
    public AgentTraceService(ObjectProvider<ObservationRegistry> registries) {
        this.registries = registries;
    }

    public String run(String conversationId, String requestId, Consumer<ChatDto.StreamEvent> events,
                      BooleanSupplier cancelled, Supplier<String> chat) {
        var trace = new Trace(conversationId, requestId, events, cancelled);
        var root = Observation.createNotStarted("agent.request",
                () -> new Observation.Context().put(Trace.class, trace), registries.getObject()).start();
        log.info("requestId={} conversationId={} started", requestId, conversationId);
        try (var scope = root.openScope()) {
            trace.checkActive();
            trace.emit("started", null);
            String answer = chat.get();
            trace.emit("answer", new ChatDto.Response(answer, conversationId));
            return answer;
        } catch (CancellationException e) {
            root.error(e);
            throw e;
        } catch (RuntimeException e) {
            root.error(e);
            String reason = failureMessage(e);
            // Spring often wraps the real failure. Log cause types/status codes without
            // raw exception messages, which can contain request content or credentials.
            log.warn("requestId={} failed causes={} reason={}", requestId, causeChain(e), reason);
            trace.emit("error", new ChatDto.StreamError(reason));
            throw e;
        } finally {
            try {
                trace.emit("usage", trace.usage());
                trace.emit("done", null);
            } finally {
                root.stop();
                log.info("requestId={} durationMs={} usage={}", requestId, elapsed(trace.started), trace.usage());
            }
        }
    }

    private String failureMessage(Throwable error) {
        for (int depth = 0; error != null && depth < 12; depth++, error = error.getCause()) {
            if (error instanceof InterruptedIOException || error instanceof HttpTimeoutException) {
                return "The model request timed out. Please try again.";
            }
            if (error instanceof UnknownHostException) {
                return "Cannot resolve the OpenRouter API address. Check your internet connection or DNS.";
            }
            if (error instanceof ConnectException) {
                return "Cannot connect to OpenRouter. Check your network, proxy or firewall.";
            }
            if (error instanceof SSLException) {
                return "The secure connection to OpenRouter failed. Check your Java certificates and network proxy.";
            }
            // OpenRouter uses the OpenAI-compatible HTTP error format.
            if (error instanceof OpenAIServiceException api) {
                return switch (api.statusCode()) {
                    case 401, 403 -> "OpenRouter rejected API access (HTTP " + api.statusCode() + "). Check your API key and permissions.";
                    case 402 -> "OpenRouter credits are insufficient (HTTP 402). Check your account balance.";
                    case 429 -> "OpenRouter's rate limit or quota was exceeded (HTTP 429). Check your quota and retry later.";
                    case 500, 502, 503 -> "The model provider is temporarily unavailable (HTTP " + api.statusCode() + "). Please retry shortly.";
                    case 504 -> "The model request timed out (HTTP 504). Please try again.";
                    default -> "OpenRouter rejected the request (HTTP " + api.statusCode() + "). Check the configured model and request.";
                };
            }
        }
        return "The agent request failed. Please try again later.";
    }

    private String causeChain(Throwable error) {
        var chain = new StringBuilder();
        for (int depth = 0; error != null && depth < 12; depth++, error = error.getCause()) {
            if (!chain.isEmpty()) chain.append(" -> ");
            chain.append(error.getClass().getSimpleName());
            if (error instanceof OpenAIServiceException api) chain.append("(HTTP ").append(api.statusCode()).append(')');
        }
        return chain.toString();
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context.containsKey(Trace.class) || context instanceof ChatModelObservationContext
                || context instanceof ToolCallingObservationContext;
    }

    @Override
    public void onStart(Observation.Context context) {
        Trace trace = findTrace(context);
        if (trace == null || !(context instanceof ChatModelObservationContext
                || context instanceof ToolCallingObservationContext)) return;
        trace.checkActive();
        boolean model = context instanceof ChatModelObservationContext;
        var span = new Span(trace, model ? trace.nextModel() : trace.nextTool());
        context.put(Span.class, span);
        if (model) {
            log.info("requestId={} modelCall={} started", trace.requestId, span.number);
            trace.emit("model_started", span.number);
        }
        if (context instanceof ToolCallingObservationContext tool) {
            log.info("requestId={} toolStep={} tool={} started", trace.requestId, span.number,
                    tool.getToolDefinition().name());
            trace.emit("tool_started", toolEvent(tool, span, "STARTED", null));
        }
    }

    @Override
    public void onStop(Observation.Context context) {
        Span span = context.get(Span.class);
        // Finish each observation once, including calls that report an error before stopping.
        if (span == null || !span.finished.compareAndSet(false, true)) return;
        if (context instanceof ChatModelObservationContext model) {
            Usage usage = model.getResponse() == null ? null : model.getResponse().getMetadata().getUsage();
            boolean available = usageAvailable(usage) && context.getError() == null;
            span.trace.addUsage(usage, available);
            log.info("requestId={} modelCall={} durationMs={} inputTokens={} outputTokens={} totalTokens={} status={}",
                    span.trace.requestId, span.number, elapsed(span.started),
                    available ? usage.getPromptTokens() : null, available ? usage.getCompletionTokens() : null,
                    available ? usage.getTotalTokens() : null, context.getError() == null ? "SUCCESS" : "ERROR");
        } else if (context instanceof ToolCallingObservationContext tool) {
            JsonNode result = parse(tool.getToolCallResult());
            boolean failed = context.getError() != null || (result != null && result.hasNonNull("error"));
            String status = failed ? "ERROR" : "SUCCESS";
            log.info("requestId={} toolStep={} tool={} durationMs={} status={}", span.trace.requestId,
                    span.number, tool.getToolDefinition().name(), elapsed(span.started), status);
            if (FILE_TOOLS.contains(tool.getToolDefinition().name())) {
                // Keep only factual metadata, never content, snippets or generated tool arguments.
                var receipt = json.createObjectNode().put("operation", tool.getToolDefinition().name())
                        .put("success", !failed && result != null && result.path("success").asBoolean());
                if (result != null && result.isObject()) {
                    for (String field : List.of("path", "verified", "replacements", "writeCompleted", "errorCode")) {
                        if (result.has(field)) receipt.set(field, result.get(field));
                    }
                }
                span.trace.addFileResult(receipt);
            }
            span.trace.emit(failed ? "tool_failed" : "tool_completed", toolEvent(tool, span, status, result));
        }
    }

    // Called by the outer memory advisor only after the model/tool loop has finished.
    public String fileResultsForMemory(String conversationId) {
        var observation = registries.getObject().getCurrentObservation();
        Trace trace = observation == null ? null : findTrace(observation.getContextView());
        if (trace == null || !trace.conversationId.equals(conversationId)) return null;
        List<JsonNode> results = trace.fileResults();
        if (results.isEmpty()) return null;
        var summary = json.createObjectNode();
        var entries = summary.putArray("fileResults");
        // Bound the record count as well. Prefer the latest outcomes when a task has many steps.
        int omitted = Math.max(0, results.size() - 10);
        results.subList(omitted, results.size()).forEach(entries::add);
        summary.put("earlierResultsOmitted", omitted);
        return "The full final reply for this filesystem task was omitted from conversation memory because it was large. "
                + "Recorded filesystem outcomes: " + summary
                + "\nUse filesystem tools to inspect current contents when needed. These are past outcomes, not a fresh check.";
    }

    private ChatDto.ToolExecution toolEvent(ToolCallingObservationContext tool, Span span,
                                            String status, JsonNode result) {
        return new ChatDto.ToolExecution(span.number, tool.getToolDefinition().name(),
                parse(tool.getToolCallArguments()), result, status,
                status.equals("STARTED") ? null : elapsed(span.started));
    }

    private JsonNode parse(String value) {
        if (value == null) return null;
        try {
            return json.readTree(value);
        } catch (RuntimeException e) {
            return json.getNodeFactory().textNode(value);
        }
    }

    private boolean usageAvailable(Usage usage) {
        if (usage == null || usage instanceof EmptyUsage || usage.getPromptTokens() == null || usage.getCompletionTokens() == null
                || usage.getTotalTokens() == null) return false;
        // OpenAI's adapter returns EmptyUsage when OpenRouter omits usage metadata.
        // Previous Gemini-specific metadata check (inactive):
        /*
        // Spring's Gemini adapter substitutes zero when metadata is absent. Detect that case.
        if (usage instanceof GoogleGenAiUsage) {
            if (!(usage.getNativeUsage() instanceof GenerateContentResponseUsageMetadata nativeUsage)) return false;
            return nativeUsage.promptTokenCount().isPresent() && nativeUsage.candidatesTokenCount().isPresent()
                    && nativeUsage.totalTokenCount().isPresent();
        }
        */
        return true;
    }

    private Trace findTrace(Observation.ContextView context) {
        while (context != null) {
            Trace trace = context.get(Trace.class);
            if (trace != null) return trace;
            var parent = context.getParentObservation();
            context = parent == null ? null : parent.getContextView();
        }
        return null;
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static final class Span {
        final Trace trace;
        final int number;
        final long started = System.nanoTime();
        final AtomicBoolean finished = new AtomicBoolean();

        Span(Trace trace, int number) {
            this.trace = trace;
            this.number = number;
        }
    }

    // One collector per request, carried by its parent observation rather than shared globally.
    private static final class Trace {
        final String conversationId;
        final String requestId;
        final Consumer<ChatDto.StreamEvent> events;
        final BooleanSupplier cancelled;
        final long started = System.nanoTime();
        int models;
        int finishedModels;
        int tools;
        long input;
        long output;
        long total;
        boolean complete = true;
        final List<JsonNode> fileResults = new ArrayList<>();

        Trace(String conversationId, String requestId, Consumer<ChatDto.StreamEvent> events, BooleanSupplier cancelled) {
            this.conversationId = conversationId;
            this.requestId = requestId;
            this.events = events;
            this.cancelled = cancelled;
        }

        synchronized int nextModel() { return ++models; }
        synchronized int nextTool() { return ++tools; }

        synchronized void addFileResult(JsonNode result) { fileResults.add(result); }
        synchronized List<JsonNode> fileResults() { return List.copyOf(fileResults); }

        synchronized void addUsage(Usage usage, boolean available) {
            finishedModels++;
            complete &= available;
            if (available) {
                input += usage.getPromptTokens();
                output += usage.getCompletionTokens();
                // Preserve the provider's reported total rather than estimating it.
                total += usage.getTotalTokens();
            }
        }

        synchronized ChatDto.Usage usage() {
            boolean known = models > 0 && models == finishedModels && complete;
            return new ChatDto.Usage(models, known ? input : null, known ? output : null, known ? total : null, known);
        }

        void checkActive() {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        }

        void emit(String type, Object data) {
            if (!cancelled.getAsBoolean()) events.accept(new ChatDto.StreamEvent(type, requestId, conversationId, data));
        }
    }
}
