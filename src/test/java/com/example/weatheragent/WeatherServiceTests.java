package com.example.weatheragent;

import com.example.weatheragent.service.WeatherService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Test the real HTTP requests against a local server, without relying on an external API.
class WeatherServiceTests {
    private HttpServer server;
    private WeatherService weatherService;
    private final Queue<String> requests = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        weatherService = new WeatherService(baseUrl + "/search", baseUrl + "/forecast");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void looksUpCoordinatesThenReturnsCurrentWeatherAndUnits() {
        respond("/search", 200, """
                {"results":[{"name":"Saharanpur","country":"India","latitude":29.9679,"longitude":77.54522}]}
                """);
        respond("/forecast", 200, """
                {
                  "timezone":"Asia/Kolkata",
                  "current":{"temperature_2m":28.5,"relative_humidity_2m":60,"weather_code":0},
                  "current_units":{"temperature_2m":"°C","relative_humidity_2m":"%","weather_code":"wmo code"}
                }
                """);

        var result = weatherService.getCurrentWeather("Saharanpur, India");

        assertThat(result.path("location").path("name").asString()).isEqualTo("Saharanpur");
        assertThat(result.path("current").path("temperature_2m").asDouble()).isEqualTo(28.5);
        assertThat(result.path("current_units").path("temperature_2m").asString()).isEqualTo("°C");
        assertThat(result.path("timezone").asString()).isEqualTo("Asia/Kolkata");
        assertThat(requests).containsExactly(
                "/search?name=Saharanpur, India&count=1&language=en&format=json",
                "/forecast?latitude=29.9679&longitude=77.54522"
                        + "&current=temperature_2m,relative_humidity_2m,weather_code&timezone=auto");
    }

    @Test
    void unknownCityDoesNotTriggerAWeatherRequest() {
        respond("/search", 200, "{}");

        var result = weatherService.getCurrentWeather("UnknownCity");

        assertThat(result.path("error").asString()).contains("City not found");
        assertThat(requests).hasSize(1);
    }

    @Test
    void returnsAnErrorWhenTheProviderRejectsTheRequest() {
        respond("/search", 429, "{\"error\":true,\"reason\":\"Too many requests\"}");

        var result = weatherService.getCurrentWeather("Saharanpur");

        assertThat(result.path("error").asString()).contains("HTTP 429");
    }

    @Test
    void returnsAnErrorWhenCurrentWeatherIsMissing() {
        respond("/search", 200, "{\"results\":[{\"latitude\":29.9679,\"longitude\":77.54522}]}");
        respond("/forecast", 200, "{}");

        var result = weatherService.getCurrentWeather("Saharanpur");

        assertThat(result.path("error").asString()).isEqualTo("No current weather data returned.");
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            requests.add(URLDecoder.decode(exchange.getRequestURI().toString(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
    }
}
