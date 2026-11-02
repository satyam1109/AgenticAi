package com.example.weatheragent.service;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@Service
public class WebSearchService {
    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    private final RestClient http;
    private final String apiKey;
    private final String apiUrl;

    public WebSearchService(@Value("${serper.api.key}") String apiKey,
                            @Value("${serper.api.url}") String apiUrl) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Tool(description = "Search Google for current or uncertain factual information, including people's ages. Returns up to three titles, snippets and source links.")
    public JsonNode searchWeb(@ToolParam(description = "A specific web search query") String query) {
        if (query == null || query.isBlank() || query.length() > 500) {
            return error("Search query must contain 1 to 500 characters.");
        }
        log.info("Web search tool called: {}", query);
        if (apiKey.isBlank()) {
            return error("Serper API key is not configured. Set serper.api.key in application.properties.");
        }

        try {
            // 1. Make a normal HTTP search request. No LLM call happens in this service.
            var request = JsonNodeFactory.instance.objectNode().put("q", query).put("num", 3);
            JsonNode response = http.post()
                    .uri(apiUrl)
                    .header("X-API-KEY", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve().body(JsonNode.class);

            if (response == null || !response.path("organic").isArray() || response.path("organic").isEmpty()) {
                return error("No search results found. Try a more specific query.");
            }

            // 2. Return only the useful fields from the first three results to keep token usage small.
            var results = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : response.path("organic")) {
                if (results.size() == 3) break;
                var result = JsonNodeFactory.instance.objectNode();
                result.set("title", item.path("title"));
                result.set("snippet", item.path("snippet"));
                result.set("link", item.path("link"));
                results.add(result);
            }
            log.info("Serper returned {} search results", results.size());
            return JsonNodeFactory.instance.objectNode().set("results", results);
        } catch (RestClientResponseException e) {
            // Log the status only, never the API key or request headers.
            int status = e.getStatusCode().value();
            log.warn("Serper returned HTTP {}", status);
            if (status == 429) return error("Serper returned HTTP 429. Check your search credits and rate limits.");
            return error("Serper returned HTTP " + status + ". Check your Serper API key and credits.");
        } catch (RestClientException e) {
            log.warn("Serper connection failed or timed out.");
            return error("Serper connection failed or timed out. Please try again later.");
        }
    }

    private JsonNode error(String message) {
        return JsonNodeFactory.instance.objectNode().put("error", message);
    }
}
