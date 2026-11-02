package com.example.weatheragent.controller;

import com.example.weatheragent.dto.ChatDto;
import com.example.weatheragent.service.LlmService;
import com.example.weatheragent.service.StreamTextService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final LlmService llmService;
    private final AsyncTaskExecutor executor;
    private final long streamTimeoutMs;
    private final StreamTextService streamText;

    // Spring supplies our service through constructor injection.
    public ChatController(LlmService llmService,
                          @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor,
                          @Value("${chat.stream.timeout-ms:120000}") long streamTimeoutMs,
                          StreamTextService streamText) {
        this.llmService = llmService;
        this.executor = executor;
        this.streamTimeoutMs = streamTimeoutMs;
        this.streamText = streamText;
    }

    @PostMapping
    public ChatDto.Response chat(@Valid @RequestBody ChatDto.Request request) {
        // A new ID starts a separate memory. Reusing an ID continues the same chat.
        String conversationId = conversationId(request);
        // Spring reads the JSON body and gives us the user's message.
        String userPrompt = request.message();
        String answer = llmService.chat(userPrompt, conversationId);
        return new ChatDto.Response(answer, conversationId);
    }

    @PostMapping(value = "/stream", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<ResponseBodyEmitter> stream(@Valid @RequestBody ChatDto.Request request) {
        String conversationId = conversationId(request);
        String requestId = UUID.randomUUID().toString();
        var emitter = new ResponseBodyEmitter(streamTimeoutMs);
        var cancelled = new AtomicBoolean();
        var task = new AtomicReference<Future<?>>();

        // A timeout or closed connection stops further model/tool calls when possible.
        Runnable cancel = () -> {
            cancelled.set(true);
            Future<?> running = task.get();
            if (running != null) running.cancel(true);
        };
        emitter.onTimeout(() -> {
            cancel.run();
            // Spring has already timed out this emitter; further writes are unavailable.
            emitter.complete();
        });
        emitter.onError(error -> cancel.run());
        emitter.onCompletion(cancel);

        // Run the blocking ChatClient call on Spring's executor so events can be sent immediately.
        task.set(executor.submit(() -> {
            try {
                llmService.chat(request.message(), conversationId, requestId, event -> {
                    if (cancelled.get()) throw new CancellationException();
                    try {
                        emitter.send(streamText.format(event), MediaType.TEXT_PLAIN);
                    } catch (IOException | IllegalStateException e) {
                        cancel.run();
                        throw new CancellationException("Stream connection closed.");
                    }
                }, cancelled::get);
            } catch (RuntimeException e) {
                // AgentTraceService already sent error + usage + done for agent failures.
                // A disconnected client cannot receive further events.
            } finally {
                if (!cancelled.get()) emitter.complete();
            }
        }));
        if (cancelled.get()) task.get().cancel(true);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .header("X-Conversation-Id", conversationId)
                .header("X-Request-Id", requestId)
                .body(emitter);
    }

    private String conversationId(ChatDto.Request request) {
        return request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString() : request.conversationId();
    }
}
