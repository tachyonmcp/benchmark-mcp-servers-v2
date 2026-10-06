package com.benchmark.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import dev.tachyonmcp.api.json.JsonSchemaValidator;
import dev.tachyonmcp.core.server.TachyonServer;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandType;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BenchmarkToolsTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "explicit", "empty-items"})
    @Timeout(30)
    @SuppressWarnings("unchecked")
    void benchmarkClientCanDiscoverAndCallAllTools(String argumentMode) throws Exception {
        // Given: a product API and seeded Redis responses at the infrastructure boundaries.
        boolean explicit = argumentMode.equals("explicit");
        String user = explicit ? "user-00017" : "user-00042";
        var redis = (RedisAsyncCommands<String, String>) mock(RedisAsyncCommands.class);
        var popular = BenchmarkToolsTest.<List<String>>pending();
        var history = BenchmarkToolsTest.<List<String>>pending();
        var rate = BenchmarkToolsTest.<Long>pending();
        var recorded = BenchmarkToolsTest.<Long>pending();
        var ranked = BenchmarkToolsTest.<Double>pending();
        when(redis.zrevrange("bench:popular", 0, 9)).thenReturn(popular);
        when(redis.hgetall("bench:cart:" + user)).thenReturn(ready(Map.of(
                "items", "[{\"product_id\":42,\"qty\":2}]", "total", "99.5")));
        when(redis.lrange("bench:history:" + user, 0, 4)).thenReturn(history);
        when(redis.incr("bench:ratelimit:" + user)).thenReturn(rate);
        when(redis.rpush(eq("bench:history:" + user), any(String[].class))).thenReturn(recorded);
        when(redis.zincrby("bench:popular", 1.0, "product:42")).thenReturn(ranked);
        var requests = new CopyOnWriteArrayList<String>();
        var checkoutBodies = new CopyOnWriteArrayList<JsonNode>();
        var api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            String body;
            if (exchange.getRequestURI().getPath().equals("/products/search")) {
                // Pending Redis futures only complete after HTTP arrives: sequential I/O would time out.
                popular.complete(List.of("product:42", "product:1337", "product:3", "product:4", "product:5",
                        "product:6", "product:7", "product:8", "product:9", "product:10"));
                body = json.writeValueAsString(Map.of("total_found", 2251, "products", java.util.Collections.nCopies(10,
                        Map.of("id", 42, "sku", "SKU-42", "name", "Product", "price", 99.5, "rating", 4.2))));
            } else if (exchange.getRequestURI().getPath().equals("/cart/calculate")) {
                checkoutBodies.add(json.readTree(exchange.getRequestBody().readAllBytes()));
                rate.complete(1L);
                recorded.complete(2L);
                ranked.complete(12.0);
                body = "{\"order_id\":\"ORD-test\",\"total\":249.5}";
            } else {
                history.complete(java.util.Collections.nCopies(5, "{\"order_id\":\"previous\"}"));
                body = "{\"id\":42}";
            }
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        api.start();
        try (var http = HttpClient.newHttpClient();
             var server = TachyonServer.builder().port(0)
                     .json(config -> config.schemaValidator(JsonSchemaValidator.noop()))
                     .pipelineCustomizer(p -> p.addBefore("mcp-endpoint", "health", new HealthCheck()))
                     .annotations(annotations -> annotations.register(new BenchmarkTools(http, redis,
                             "http://127.0.0.1:" + api.getAddress().getPort(), "tachyon")))
                     .build()) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();

            // When: the existing benchmark's initialize/notification/tools flow is used.
            var initialized = rpc(http, base, "initialize", Map.of(
                    "protocolVersion", "2024-11-05", "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "benchmark-test", "version", "1.0")));
            assertThat(initialized.has("result")).isTrue();
            var notify = http.send(HttpRequest.newBuilder(URI.create(base + "/mcp"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(notify.statusCode()).isEqualTo(202);
            var listed = rpc(http, base, "tools/list", Map.of());
            assertThat(listed.path("result").path("tools")).hasSize(3);
            for (JsonNode tool : listed.path("result").path("tools")) {
                String requestType = switch (tool.path("name").asText()) {
                    case "search_products" -> "SearchRequest";
                    case "get_user_cart" -> "CartRequest";
                    case "checkout" -> "CheckoutRequest";
                    default -> throw new AssertionError("Unexpected tool " + tool);
                };
                for (String direction : List.of("input", "output")) {
                    String type = direction.equals("input") ? requestType : requestType.replace("Request", "Response");
                    try (var resource = getClass().getResourceAsStream(
                            "/META-INF/kt-schema/schemas/com/benchmark/mcp/model/" + type + ".json")) {
                        assertThat(resource).as("Generated schema for %s", type).isNotNull();
                        assertThat(tool.path(direction + "Schema")).isEqualTo(json.readTree(resource));
                    }
                }
                if (tool.path("name").asText().equals("search_products")) {
                    assertThat(tool.path("inputSchema").path("properties").path("min_price").path("description").asText())
                            .contains("50.0");
                    assertThat(tool.path("inputSchema").path("properties").has("minPrice")).isFalse();
                    assertThat(tool.path("inputSchema").path("properties").path("min_price").path("default").asDouble())
                            .isEqualTo(50.0);
                    assertThat(tool.path("inputSchema").path("required")).isEmpty();
                }
            }
            var arguments = new HashMap<String, Object>();
            if (argumentMode.equals("null")) {
                for (String key : List.of("category", "min_price", "max_price", "limit", "user_id", "items")) {
                    arguments.put(key, null);
                }
            }
            if (explicit) {
                arguments.putAll(Map.of("category", "Books", "min_price", 12.0, "max_price", 300.0,
                        "limit", 7, "user_id", user, "items", List.of(
                                Map.of("product_id", 42, "quantity", 3), Map.of("product_id", 1337, "quantity", 4))));
            } else if (argumentMode.equals("empty-items")) {
                arguments.put("items", List.of());
            }
            var search = call(http, base, "search_products", arguments);
            var cart = call(http, base, "get_user_cart", arguments);
            var checkout = call(http, base, "checkout", arguments);

            // Then: results and external side effects satisfy the benchmark contract.
            assertThat(search.path("total_found").asInt()).isEqualTo(2251);
            assertThat(search.path("products").get(0).path("popularity_rank").asInt()).isEqualTo(1);
            assertThat(search.path("products")).hasSize(10);
            assertThat(search.path("top10_popular_ids")).hasSize(10);
            assertThat(cart.path("user_id").asText()).isEqualTo(user);
            assertThat(cart.path("cart").path("item_count").asInt()).isEqualTo(1);
            assertThat(cart.path("cart").path("estimated_total").asDouble()).isEqualTo(99.5);
            assertThat(cart.path("recent_history").get(0).path("order_id").asText()).isEqualTo("previous");
            assertThat(cart.path("recent_history")).hasSize(5);
            assertThat(checkout.path("order_id").asText()).isEqualTo("ORD-test");
            assertThat(checkout.path("total").asDouble()).isEqualTo(249.5);
            assertThat(checkout.path("items_count").asInt()).isEqualTo(2);
            assertThat(checkout.path("rate_limit_count").asInt()).isEqualTo(1);
            assertThat(checkout.path("status").asText()).isEqualTo("confirmed");
            assertThat(checkout.path("server_type").asText()).isEqualTo("tachyon");
            assertThat(requests).containsExactly(
                    explicit ? "/products/search?category=Books&min_price=12.0&max_price=300.0&limit=7"
                            : "/products/search?category=Electronics&min_price=50.0&max_price=500.0&limit=10",
                    "/products/42", "/cart/calculate");
            assertThat(checkoutBodies.get(0).path("items")).hasSize(2);
            assertThat(checkoutBodies.get(0).path("user_id").asText()).isEqualTo(user);
            assertThat(checkoutBodies.get(0).path("items").get(0).path("quantity").asInt())
                    .isEqualTo(explicit ? 3 : 2);
            var order = ArgumentCaptor.forClass(String.class);
            verify(redis).rpush(eq("bench:history:" + user), order.capture());
            assertThat(json.readTree(order.getValue()).path("items")).isEqualTo(checkoutBodies.get(0).path("items"));
            verify(redis).incr("bench:ratelimit:" + user);
            verify(redis).zincrby("bench:popular", 1.0, "product:42");
            for (String method : List.of("GET", "HEAD")) {
                var health = http.send(HttpRequest.newBuilder(URI.create(base + "/health"))
                        .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(health.statusCode()).isEqualTo(200);
                assertThat(health.body()).isEqualTo(method.equals("HEAD") ? "" : "{\"status\":\"ok\"}");
            }
            var invalid = rpc(http, base, "tools/call", Map.of("name", "checkout", "arguments",
                    Map.of("items", List.of(Map.of("product_id", 42, "quantity", -1)))));
            assertThat(invalid.has("error") || invalid.path("result").path("isError").asBoolean()).isTrue();
            assertThat(checkoutBodies).hasSize(1);
            verify(redis).incr("bench:ratelimit:" + user);
        } finally {
            api.stop(0);
        }
    }

    private JsonNode call(HttpClient http, String base, String tool, Map<String, Object> arguments) throws Exception {
        var result = rpc(http, base, "tools/call", Map.of("name", tool, "arguments", arguments));
        assertThat(result.has("error")).as(result.toString()).isFalse();
        assertThat(result.path("result").path("isError").asBoolean()).as(result.toString()).isFalse();
        var payload = json.readTree(result.path("result").path("content").get(0).path("text").asText());
        assertThat(result.path("result").path("structuredContent")).isEqualTo(payload);
        return payload;
    }

    private JsonNode rpc(HttpClient http, String base, String method, Map<String, Object> params) throws Exception {
        var body = json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
        var response = http.send(HttpRequest.newBuilder(URI.create(base + "/mcp"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String payload = response.body().strip();
        if (!payload.startsWith("{")) {
            payload = payload.lines().filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).strip()).findFirst().orElseThrow();
        }
        return json.readTree(payload);
    }

    private static <T> RedisFuture<T> ready(T value) {
        var future = BenchmarkToolsTest.<T>pending();
        future.complete(value);
        return future;
    }

    private static <T> AsyncCommand<String, String, T> pending() {
        return new AsyncCommand<>(new Command<>(CommandType.PING, null));
    }
}
