package com.example.weatheragent.service;

import com.example.weatheragent.dto.ChatDto;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

// Formats progress locally. No additional LLM call is needed to turn results into text.
@Service
public class StreamTextService {
    public String format(ChatDto.StreamEvent event) {
        return switch (event.type()) {
            case "started" -> "Conversation: " + event.conversationId() + "\n\n";
            case "model_started" -> "Waiting for the model (call " + event.data() + ")...\n";
            case "tool_started" -> {
                var tool = (ChatDto.ToolExecution) event.data();
                yield tool.name() + " called: " + describe(tool.arguments()) + "\n";
            }
            case "tool_completed" -> toolResult((ChatDto.ToolExecution) event.data());
            case "tool_failed" -> {
                var tool = (ChatDto.ToolExecution) event.data();
                yield tool.name() + " failed: " + describe(tool.result()) + "\n";
            }
            case "answer" -> "\nFinal answer:\n" + ((ChatDto.Response) event.data()).answer() + "\n\n";
            case "usage" -> {
                var usage = (ChatDto.Usage) event.data();
                String tokens = usage.complete()
                        ? "input " + usage.inputTokens() + ", output " + usage.outputTokens() + ", total " + usage.totalTokens()
                        : "unavailable (usage was missing for one or more model calls)";
                yield "Tokens: " + tokens + ". Model calls: " + usage.modelCalls() + "\n";
            }
            case "error" -> "\nError: " + ((ChatDto.StreamError) event.data()).message() + "\n";
            case "done" -> "Done.\n";
            default -> "";
        };
    }

    private String toolResult(ChatDto.ToolExecution tool) {
        JsonNode result = tool.result();
        if (tool.name().equals("getCurrentWeather") && result != null && result.hasNonNull("current")) {
            JsonNode current = result.path("current");
            JsonNode units = result.path("current_units");
            return "Weather in " + result.path("location").path("name").asString("requested city")
                    + ": temperature " + current.path("temperature_2m").asString("unavailable")
                    + units.path("temperature_2m").asString("")
                    + ", humidity " + current.path("relative_humidity_2m").asString("unavailable")
                    + units.path("relative_humidity_2m").asString("")
                    + ", weather code " + current.path("weather_code").asString("unavailable") + "\n";
        }
        return tool.name() + " returned: " + describe(result) + "\n";
    }

    // Flatten JSON data into readable labels, keeping search snippets and source links.
    private String describe(JsonNode node) {
        if (node == null || node.isNull()) return "no data returned";
        if (node.isValueNode()) return node.asString();
        if (node.isArray()) {
            return StreamSupport.stream(node.spliterator(), false).map(this::describe).collect(Collectors.joining("; "));
        }
        return node.properties().stream().map(entry -> entry.getKey() + ": " + describe(entry.getValue()))
                .collect(Collectors.joining(", "));
    }
}
