// Previous Gemini SDK tests, inactive while its dependency is commented out.
// package com.example.weatheragent;
//
// import com.google.genai.errors.ApiException;
// import com.google.genai.types.GenerateContentConfig;
// import com.google.genai.types.HttpOptions;
// import com.sun.net.httpserver.HttpServer;
// import java.io.InterruptedIOException;
// import java.net.InetSocketAddress;
// import java.util.concurrent.atomic.AtomicInteger;
// import org.junit.jupiter.api.Test;
//
// import static org.assertj.core.api.Assertions.*;
//
// // Exercise the actual SDK against localhost, using no real API key or quota.
// class GeminiClientTests {
//     @Test
//     void aSlowGeminiRequestHitsTheConfiguredDeadline() throws Exception {
//         var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
//         server.createContext("/", exchange -> {
//             try {
//                 Thread.sleep(1200);
//                 exchange.sendResponseHeaders(200, -1);
//             } catch (Exception ignored) {
//                 // Expected: the client times out and closes the connection first.
//             } finally {
//                 exchange.close();
//             }
//         });
//         server.start();
//         try (var client = new WeatherAgentApplication().googleGenAiClient("test-key", 200)) {
//             long start = System.nanoTime();
//             assertThatThrownBy(() -> client.models.generateContent("test-model", "hello", localOptions(server)))
//                     .hasCauseInstanceOf(InterruptedIOException.class);
//             assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1100);
//         } finally {
//             server.stop(0);
//         }
//     }
//
//     @Test
//     void anAccessErrorIsNotRetriedByTheSdk() throws Exception {
//         var requests = new AtomicInteger();
//         var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
//         server.createContext("/", exchange -> {
//             requests.incrementAndGet();
//             byte[] body = "{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\",\"message\":\"Access denied\"}}"
//                     .getBytes(java.nio.charset.StandardCharsets.UTF_8);
//             exchange.getResponseHeaders().set("Content-Type", "application/json");
//             exchange.sendResponseHeaders(403, body.length);
//             try (var output = exchange.getResponseBody()) { output.write(body); }
//         });
//         server.start();
//         try (var client = new WeatherAgentApplication().googleGenAiClient("test-key", 2000)) {
//             assertThatThrownBy(() -> client.models.generateContent("test-model", "hello", localOptions(server)))
//                     .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(403));
//             assertThat(requests).hasValue(1);
//         } finally {
//             server.stop(0);
//         }
//     }
//
//     @Test
//     void aTemporary503IsRetriedOnceAndCanRecover() throws Exception {
//         check503Retries(true);
//     }
//
//     @Test
//     void aPersistent503StopsAfterTwoAttempts() throws Exception {
//         check503Retries(false);
//     }
//
//     private void check503Retries(boolean recovers) throws Exception {
//         var requests = new AtomicInteger();
//         var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
//         server.createContext("/", exchange -> {
//             boolean success = requests.incrementAndGet() == 2 && recovers;
//             String response = success
//                     ? "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"OK\"}]}}]}"
//                     : "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\",\"message\":\"Temporarily busy\"}}";
//             byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
//             exchange.getResponseHeaders().set("Content-Type", "application/json");
//             exchange.sendResponseHeaders(success ? 200 : 503, body.length);
//             try (var output = exchange.getResponseBody()) { output.write(body); }
//         });
//         server.start();
//         try (var client = new WeatherAgentApplication().googleGenAiClient("test-key", 2000)) {
//             if (recovers) {
//                 assertThat(client.models.generateContent("test-model", "hello", localOptions(server)).text()).isEqualTo("OK");
//             } else {
//                 assertThatThrownBy(() -> client.models.generateContent("test-model", "hello", localOptions(server)))
//                         .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(503));
//             }
//             assertThat(requests).hasValue(2);
//         } finally {
//             server.stop(0);
//         }
//     }
//
//     private GenerateContentConfig localOptions(HttpServer server) {
//         // Override only the URL; retain the timeout and retry options from our bean.
//         return GenerateContentConfig.builder().httpOptions(HttpOptions.builder()
//                 .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build()).build();
//     }
// }
