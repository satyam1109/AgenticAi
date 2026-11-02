package com.example.weatheragent.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.regex.Pattern;
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
public class ShoppingService {
    private static final Logger log = LoggerFactory.getLogger(ShoppingService.class);
    // Accept one clearly labelled rupee amount, including Indian and international comma grouping.
    // Reject ranges, foreign currencies and instalment text instead of guessing a purchase price.
    private static final Pattern RUPEE_PRICE = Pattern.compile(
            "(?i)^(?:₹|INR|Rs\\.?)\\s*((?:\\d+|\\d{1,3}(?:,\\d{3})+|\\d{1,2}(?:,\\d{2})*,\\d{3})(?:\\.\\d{1,2})?)$");

    private final RestClient http;
    private final String apiKey;
    private final String apiUrl;

    public ShoppingService(@Value("${serper.api.key}") String apiKey,
                           @Value("${serper.shopping.url}") String apiUrl) {
        this.apiKey = apiKey;
        this.apiUrl = apiUrl;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Tool(description = "Search Google Shopping for products in India, optionally within a maximum INR price. Returns up to five listings with prices, sellers and links. Prices and stock are not checkout-verified.")
    public JsonNode searchProducts(
            @ToolParam(description = "Product search, including any requested specifications, e.g. laptops with 16GB RAM") String query,
            @ToolParam(description = "Maximum listed price in Indian rupees, inclusive. Omit when no budget is given.", required = false) Double maxPrice) {
        if (query == null || query.isBlank() || query.length() > 500) {
            return error("Shopping query must contain 1 to 500 characters.");
        }
        if (maxPrice != null && (!Double.isFinite(maxPrice) || maxPrice <= 0)) {
            return error("Maximum price must be a positive, finite amount in INR, or omitted.");
        }
        if (apiKey.isBlank()) {
            return error("Serper API key is not configured. Set serper.api.key in application.properties.");
        }
        log.info("Shopping tool called: query={} maxPriceInr={}", query, maxPrice);

        try {
            // 1. Search Indian shopping listings using our existing Serper key. No LLM call here.
            var request = JsonNodeFactory.instance.objectNode()
                    .put("q", query).put("gl", "in").put("hl", "en").put("num", 10);
            JsonNode response = http.post().uri(apiUrl)
                    .header("X-API-KEY", apiKey).contentType(MediaType.APPLICATION_JSON)
                    .body(request).retrieve().body(JsonNode.class);
            if (response == null || !response.path("shopping").isArray()) {
                return error("No shopping results returned. Try a more specific product query.");
            }

            // 2. Compare actual returned prices in Java, rather than asking the LLM to filter them.
            BigDecimal budget = maxPrice == null ? null : BigDecimal.valueOf(maxPrice);
            var products = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : response.path("shopping")) {
                BigDecimal price = priceInRupees(item.path("price").asString(""));
                if (price == null || price.signum() <= 0 || (budget != null && price.compareTo(budget) > 0)) continue;
                if (item.path("title").asString("").isBlank() || item.path("link").asString("").isBlank()) continue;
                var product = JsonNodeFactory.instance.objectNode();
                product.set("title", item.path("title"));
                product.set("price", item.path("price"));
                product.put("priceInr", price);
                product.set("source", item.path("source"));
                product.set("link", item.path("link"));
                products.add(product);
                if (products.size() == 5) break; // Keep tool output and token use small.
            }

            // 3. Return data; the model explains the options. An empty list is not a provider failure.
            var result = JsonNodeFactory.instance.objectNode().put("currency", "INR");
            result.set("products", products);
            result.put("note", "Only returned listings with clear INR prices were checked. Listed prices may differ at checkout; stock and specifications are not verified.");
            log.info("Serper Shopping returned {} matching listings", products.size());
            return result;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("Serper Shopping returned HTTP {}", status);
            if (status == 429) return error("Serper Shopping returned HTTP 429. Check your search credits and rate limits.");
            return error("Serper Shopping returned HTTP " + status + ". Check your Serper API key and credits.");
        } catch (RestClientException e) {
            log.warn("Serper Shopping connection failed or timed out.");
            return error("Serper Shopping connection failed or timed out. Please try again later.");
        }
    }

    private BigDecimal priceInRupees(String price) {
        var match = RUPEE_PRICE.matcher(price.replace('\u00a0', ' ').strip());
        return match.matches() ? new BigDecimal(match.group(1).replace(",", "")) : null;
    }

    private JsonNode error(String message) {
        return JsonNodeFactory.instance.objectNode().put("error", message);
    }
}
