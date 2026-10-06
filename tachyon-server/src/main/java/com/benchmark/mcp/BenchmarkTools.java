package com.benchmark.mcp;

import com.benchmark.mcp.model.*;
import dev.tachyonmcp.api.annotations.McpTool;
import io.lettuce.core.api.async.RedisAsyncCommands;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BenchmarkTools {
    private final HttpClient http;
    private final RedisAsyncCommands<String, String> redis;
    private final String apiUrl;
    private final String serverType;
    private final JsonMapper json = JsonMapper.builder().build();

    BenchmarkTools(HttpClient http,
                   RedisAsyncCommands<String, String> redis,
                   String apiUrl,
                   String serverType) {
        this.http = http;
        this.redis = redis;
        this.apiUrl = apiUrl;
        this.serverType = serverType;
    }

    @McpTool(name = "search_products",
            description = "Search products by category and price range, merged with popularity data")
    public SearchResponse searchProducts(SearchRequest request) throws Exception {
        String category = request.category();
        var popular = redis.zrevrange("bench:popular", 0, 9);
        var data = get("/products/search?category=" + URLEncoder.encode(category, StandardCharsets.UTF_8)
                + "&min_price=" + request.minPrice()
                + "&max_price=" + request.maxPrice()
                + "&limit=" + request.limit());
        var ranks = new HashMap<Integer, Integer>();
        var ids = new ArrayList<Integer>();
        for (String member : popular.toCompletableFuture().join()) {
            int id = Integer.parseInt(member.substring(member.indexOf(':') + 1));
            ids.add(id);
            ranks.put(id, ids.size());
        }
        var products = new ArrayList<Product>();
        for (JsonNode product : data.path("products")) {
            products.add(new Product(
                    product.path("id").asInt(),
                    product.path("sku").asString(),
                    product.path("name").asString(),
                    product.path("price").asDouble(),
                    product.path("rating").asDouble(),
                    ranks.getOrDefault(product.path("id").asInt(), 0)));
        }
        return new SearchResponse(serverType, category, data.path("total_found").asInt(), products, ids);
    }

    @McpTool(name = "get_user_cart", description = "Get user cart details with recent order history")
    public CartResponse getUserCart(CartRequest request) throws Exception {
        String user = request.userId();
        var cartHash = redis.hgetall("bench:cart:" + user).toCompletableFuture().join();
        var items = json.readValue(cartHash.getOrDefault("items", "[]"),
                new TypeReference<List<Map<String, Object>>>() {
                });
        int firstId = items.isEmpty() ? 1 : ((Number) items.getFirst().getOrDefault("product_id", 0)).intValue();
        var history = redis.lrange("bench:history:" + user, 0, 4);
        get("/products/" + firstId);
        var recent = new ArrayList<Map<String, Object>>();
        for (String entry : history.toCompletableFuture().join()) {
            recent.add(json.readValue(entry, new TypeReference<Map<String, Object>>() {
            }));
        }
        var cart = new Cart(items.size(), Double.parseDouble(cartHash.getOrDefault("total", "0")), items);
        return new CartResponse(serverType, user, cart, recent);
    }

    @McpTool(name = "checkout",
            description = "Process checkout: calculate total, update rate limit, record history")
    public CheckoutResponse checkout(CheckoutRequest request) throws Exception {
        String user = request.userId();
        var items = request.items();
        int userNum;
        try {
            userNum = Integer.parseInt(user.substring(user.lastIndexOf('-') + 1));
        } catch (NumberFormatException e) {
            userNum = 42;
        }
        long ts = Instant.now().getEpochSecond();
        String orderId = "ORD-" + user + "-" + ts;
        var order = Map.of("order_id", orderId, "items", items, "ts", ts);
        var rate = redis.incr("bench:ratelimit:user-%05d".formatted(userNum % 100));
        var history = redis.rpush("bench:history:" + user, json.writeValueAsString(order));
        var popularity = redis.zincrby("bench:popular", 1.0, "product:" + items.getFirst().productId());
        var body = json.writeValueAsString(Map.of("user_id", user, "items", items));
        var data = send(HttpRequest.newBuilder(URI.create(apiUrl + "/cart/calculate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        long rateCount = rate.toCompletableFuture().join();
        history.toCompletableFuture().join();
        popularity.toCompletableFuture().join();
        return new CheckoutResponse(serverType,
                data.path("order_id").asString(orderId),
                user,
                data.path("total").asDouble(),
                items.size(),
                rateCount,
                "confirmed"
        );
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
}
