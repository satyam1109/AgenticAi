package com.example.weatheragent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

// Keep our request and response shapes together in one file for this small project.
public final class ChatDto {
    private ChatDto() {}

    // A record is a small data holder. Spring maps incoming JSON to this record.
    // Omit conversationId to start a chat; send it again to continue that chat.
    public record Request(@NotBlank @Size(max = 2000) String message,
                          @Size(max = 100) String conversationId) {}

    // Spring converts this record back into JSON for the caller.
    public record Response(String answer, String conversationId) {}

    // Internal progress data. StreamTextService turns this into plain text for the caller.
    public record StreamEvent(String type, String requestId, String conversationId, Object data) {}

    public record ToolExecution(int step, String name, JsonNode arguments,
                                JsonNode result, String status, Long durationMs) {}

    // Null totals mean some model calls did not supply usage. They do not mean zero tokens.
    public record Usage(int modelCalls, Long inputTokens, Long outputTokens,
                        Long totalTokens, boolean complete) {}

    public record StreamError(String message) {}
}
