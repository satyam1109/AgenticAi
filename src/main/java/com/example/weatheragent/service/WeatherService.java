package com.example.weatheragent.service;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@Service
public class WeatherService {
    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final RestClient http;
    private final String geocodingUrl;
    private final String forecastUrl;

    public WeatherService(@Value("${weather.geocoding-url}") String geocodingUrl,
                          @Value("${weather.forecast-url}") String forecastUrl) {
        this.geocodingUrl = geocodingUrl;
        this.forecastUrl = forecastUrl;
        // A slow weather provider should not leave the request waiting indefinitely.
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    // @Tool describes the operation that the model can request.
    @Tool(description = "Get current temperature, humidity and weather code when the user requests current weather. Merely stating a city is not a weather request. Not a future forecast.")
    public JsonNode getCurrentWeather(
            @ToolParam(description = "City name, optionally followed by country, e.g. Mumbai, India") String city) {
        if (city == null || city.isBlank() || city.length() > 100) {
            return error("City name must contain 1 to 100 characters.");
        }
        log.info("Weather tool called for city: {}", city);
        try {
            // 1. Look up the city and use its first matching location.
            JsonNode search = http.get()
                    .uri(geocodingUrl + "?name={city}&count=1&language=en&format=json", city)
                    .retrieve().body(JsonNode.class);
            if (search == null || !search.path("results").isArray() || search.path("results").isEmpty()) {
                return error("City not found. Try a city name followed by its country.");
            }
            JsonNode location = search.path("results").get(0);
            if (!location.path("latitude").isNumber() || !location.path("longitude").isNumber()) {
                return error("No coordinates returned for the city.");
            }

            // 2. Use those coordinates to fetch current weather. No API key is needed.
            JsonNode weather = http.get()
                    .uri(forecastUrl + "?latitude={latitude}&longitude={longitude}"
                                    + "&current=temperature_2m,relative_humidity_2m,weather_code&timezone=auto",
                            location.path("latitude").asDouble(), location.path("longitude").asDouble())
                    .retrieve().body(JsonNode.class);
            // This runs only when the HTTP request succeeds. Check this log to inspect the JSON.
            log.info("Open-Meteo JSON received: {}", weather);
            if (weather == null || !weather.path("current").isObject()) {
                return error("No current weather data returned.");
            }

            // 3. Return data and units, so the model can write the human-readable answer.
            var result = JsonNodeFactory.instance.objectNode();
            result.set("location", location);
            result.set("current", weather.path("current"));
            result.set("current_units", weather.path("current_units"));
            result.set("timezone", weather.path("timezone"));
            return result;
        } catch (RestClientResponseException e) {
            // For HTTP errors, .retrieve() throws before the weather variable is assigned.
            log.warn("Open-Meteo HTTP {}. Response: {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            return error("Open-Meteo returned HTTP " + e.getStatusCode().value() + ". Please try again later.");
        } catch (RestClientException e) {
            log.warn("Open-Meteo request failed: {}", e.getClass().getSimpleName());
            return error("Open-Meteo connection failed or timed out.");
        }
    }

    private JsonNode error(String message) {
        return JsonNodeFactory.instance.objectNode().put("error", message);
    }
}
