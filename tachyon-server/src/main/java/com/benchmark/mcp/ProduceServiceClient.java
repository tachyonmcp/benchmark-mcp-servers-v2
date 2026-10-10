package com.benchmark.mcp;

import com.benchmark.mcp.model.CheckoutRequest;
import com.benchmark.mcp.model.SearchRequest;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class ProduceServiceClient implements AutoCloseable {
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final HttpClient http;
    private final String apiUrl;
    private final JsonMapper json = JsonMapper.builder().build();

    static ProduceServiceClient connect(String url, String apiUrl) {
        var client = RedisClient.create(url);
        HttpClient http;
        try {
            http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
        } catch (RuntimeException | Error failure) {
            try (client) {
                throw failure;
            }
        }
        return new ProduceServiceClient(client, http, apiUrl);
    }

    ProduceServiceClient(RedisClient client, HttpClient http, String apiUrl) {
        this.client = client;
        this.http = http;
        this.apiUrl = apiUrl;
        try {
            connection = client.connect();
        } catch (RuntimeException | Error failure) {
            try (client; http) {
                throw failure;
            }
        }
    }

    CompletionStage<List<String>> popularProducts() {
        return connection.async().zrevrange("bench:popular", 0, 9);
    }

    CompletionStage<Map<String, String>> cart(String user) {
        return connection.async().hgetall("bench:cart:" + user);
    }

    CompletionStage<List<String>> recentOrders(String user) {
        return connection.async().lrange("bench:history:" + user, 0, 4);
    }

    CompletionStage<Long> incrementRateLimit(int userNum) {
        return connection.async().incr("bench:ratelimit:user-%05d".formatted(userNum % 100));
    }

    CompletionStage<Long> recordOrder(String user, String order) {
        return connection.async().rpush("bench:history:" + user, order);
    }

    CompletionStage<Double> incrementPopularity(int productId) {
        return connection.async().zincrby("bench:popular", 1.0, "product:" + productId);
    }

    JsonNode searchProducts(SearchRequest request) throws Exception {
        return get("/products/search?category=" + URLEncoder.encode(request.category(), StandardCharsets.UTF_8)
                + "&min_price=" + request.minPrice()
                + "&max_price=" + request.maxPrice()
                + "&limit=" + request.limit());
    }

    JsonNode product(int productId) throws Exception {
        return get("/products/" + productId);
    }

    JsonNode calculateCart(CheckoutRequest request) throws Exception {
        var body = json.writeValueAsString(Map.of("user_id", request.userId(), "items", request.items()));
        return send(HttpRequest.newBuilder(URI.create(apiUrl + "/cart/calculate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private JsonNode get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(apiUrl + path)).GET());
    }

    private JsonNode send(HttpRequest.Builder request) throws Exception {
        var response = http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("API service returned HTTP " + response.statusCode());
        }
        return json.readTree(response.body());
    }

    @Override
    public void close() {
        try (client; connection; http) {
            // Close dependents before their client, preserving suppressed failures.
        }
    }
}
