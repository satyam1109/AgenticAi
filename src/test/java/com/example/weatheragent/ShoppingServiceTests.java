package com.example.weatheragent;

import com.example.weatheragent.service.ShoppingService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

// Exercise actual HTTP and price filtering against a local server, without Serper credits.
class ShoppingServiceTests {
    private HttpServer server;
    private ShoppingService service;
    private String apiUrl;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestKey = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/shopping";
        service = new ShoppingService("test-key", apiUrl);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void postsAnIndianShoppingSearchAndReturnsAtMostFiveMatchingProducts() {
        var response = json.createObjectNode();
        var shopping = response.putArray("shopping");
        shopping.addObject().put("title", "Too expensive").put("price", "₹60,000").put("link", "https://example.com/expensive");
        for (int i = 1; i <= 7; i++) {
            shopping.addObject().put("title", "Laptop " + i).put("price", "₹49,999")
                    .put("source", "Example retailer").put("link", "https://example.com/" + i)
                    .put("imageUrl", "unneeded image data");
        }
        respond(200, response.toString());

        String query = "laptops with 16GB RAM \"मुंबई\"";
        var result = service.searchProducts(query, 50000.0);

        assertThat(requestMethod.get()).isEqualTo("POST");
        assertThat(requestKey.get()).isEqualTo("test-key");
        assertThat(contentType.get()).startsWith("application/json");
        assertThat(requestBody.get().path("q").asString()).isEqualTo(query);
        assertThat(requestBody.get().path("gl").asString()).isEqualTo("in");
        assertThat(requestBody.get().path("hl").asString()).isEqualTo("en");
        assertThat(requestBody.get().path("num").asInt()).isEqualTo(10);
        assertThat(result.path("currency").asString()).isEqualTo("INR");
        assertThat(result.path("products").size()).isEqualTo(5);
        assertThat(result.path("products").path(0).path("title").asString()).isEqualTo("Laptop 1");
        assertThat(result.path("products").path(0).path("source").asString()).isEqualTo("Example retailer");
        assertThat(result.path("products").path(0).path("link").asString()).isEqualTo("https://example.com/1");
        assertThat(result.path("products").path(0).has("imageUrl")).isFalse();
        assertThat(result.path("note").asString()).contains("checkout", "not verified");
    }

    @Test
    void comparesRupeeAmountsIncludingDecimalsAndRejectsAmbiguousPrices() {
        respond(200, """
                {"shopping":[
                  {"title":"At budget","price":"₹50,000.00","link":"https://example.com/1"},
                  {"title":"Above budget","price":"₹50,000.01","link":"https://example.com/2"},
                  {"title":"INR label","price":"INR 49,999.50","link":"https://example.com/3"},
                  {"title":"Rs label","price":"Rs. 40,000","link":"https://example.com/4"},
                  {"title":"Indian grouping","price":"₹1,25,000","link":"https://example.com/5"},
                  {"title":"US grouping","price":"₹125,000","link":"https://example.com/6"},
                  {"title":"EMI","price":"₹2,499/month","link":"https://example.com/7"},
                  {"title":"Foreign","price":"$499","link":"https://example.com/8"},
                  {"title":"Range","price":"₹20,000 - ₹40,000","link":"https://example.com/9"},
                  {"title":"Unknown currency","price":"20000","link":"https://example.com/10"},
                  {"title":"Missing price","link":"https://example.com/11"},
                  {"title":"Invalid grouping","price":"₹4,,999","link":"https://example.com/12"},
                  {"title":"No link","price":"₹10,000"},
                  {"title":"Zero price","price":"₹0","link":"https://example.com/13"}
                ]}
                """);
        var products = service.searchProducts("laptops", 50000.0).path("products");

        assertThat(products).extracting(item -> item.path("title").asString())
                .containsExactly("At budget", "INR label", "Rs label");
        assertThat(products.path(0).path("priceInr").asDouble()).isEqualTo(50000.0);
        assertThat(products.path(1).path("priceInr").asDouble()).isEqualTo(49999.5);
    }

    @Test
    void allowsAnOmittedBudgetAndParsesIndianCommaGrouping() {
        respond(200, """
                {"shopping":[{"title":"Workstation","price":"₹1,25,000","link":"https://example.com/laptop"}]}
                """);
        var result = service.searchProducts("workstation laptops", null);
        assertThat(result.path("products").path(0).path("priceInr").asDouble()).isEqualTo(125000);
    }

    @Test
    void returnsAnEmptyListWhenNoReturnedPricesMeetTheBudget() {
        respond(200, """
                {"shopping":[{"title":"Laptop","price":"₹60,000","link":"https://example.com/laptop"}]}
                """);
        var result = service.searchProducts("laptops", 50000.0);
        assertThat(result.path("products").isEmpty()).isTrue();
        assertThat(result.has("error")).isFalse();
        assertThat(result.path("note").asString()).contains("Only returned listings");
    }

    @Test
    void handlesAnEmptyShoppingArrayWithoutClaimingTheProviderFailed() {
        respond(200, "{\"shopping\":[]}");
        var result = service.searchProducts("laptops", null);
        assertThat(result.path("products").isArray()).isTrue();
        assertThat(result.path("products").isEmpty()).isTrue();
        assertThat(result.has("error")).isFalse();
    }

    @Test
    void reportsAnUnexpectedProviderResponse() {
        respond(200, "{\"organic\":[]}");
        assertThat(service.searchProducts("laptops", null).path("error").asString()).contains("No shopping results");
    }

    @Test
    void rejectsInvalidInputBeforeMakingAnHttpRequest() {
        assertThat(service.searchProducts(null, null).has("error")).isTrue();
        assertThat(service.searchProducts(" ", null).has("error")).isTrue();
        assertThat(service.searchProducts("x".repeat(501), null).has("error")).isTrue();
        for (double price : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThat(service.searchProducts("laptops", price).has("error")).isTrue();
        }
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void reportsAMissingKeyBeforeMakingAnHttpRequest() {
        var unconfigured = new ShoppingService("", apiUrl);
        assertThat(unconfigured.searchProducts("laptops", null).path("error").asString()).contains("key is not configured");
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void reportsQuotaErrorsWithoutRetryingOrLeakingProviderDetails() {
        respond(429, "{\"message\":\"private-provider-detail\"}");
        assertThat(service.searchProducts("laptops", 50000.0).path("error").asString())
                .contains("HTTP 429", "credits").doesNotContain("test-key", "private-provider-detail");
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void reportsAuthenticationErrorsWithoutExposingTheKey() {
        respond(401, "{\"message\":\"private-provider-detail\"}");
        assertThat(service.searchProducts("laptops", null).path("error").asString())
                .contains("HTTP 401", "API key").doesNotContain("test-key", "private-provider-detail");
    }

    private void respond(int status, String body) {
        server.createContext("/shopping", exchange -> {
            requestCount.incrementAndGet();
            requestMethod.set(exchange.getRequestMethod());
            requestKey.set(exchange.getRequestHeaders().getFirst("X-API-KEY"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
    }
}
