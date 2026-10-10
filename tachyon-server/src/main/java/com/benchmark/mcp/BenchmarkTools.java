package com.benchmark.mcp;

import com.benchmark.mcp.model.*;
import dev.tachyonmcp.api.annotations.McpTool;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BenchmarkTools {
    private final ProduceServiceClient redis;
    private final String serverType;
    private final JsonMapper json = JsonMapper.builder().build();

    BenchmarkTools(ProduceServiceClient redis, String serverType) {
        this.redis = redis;
        this.serverType = serverType;
    }

    @McpTool(name = "search_products",
            description = "Search products by category and price range, merged with popularity data")
    public SearchResponse searchProducts(SearchRequest request) throws Exception {
        String category = request.category();
        var popular = redis.popularProducts();
        var data = redis.searchProducts(request);
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
        var cartHash = redis.cart(user).toCompletableFuture().join();
        var items = json.readValue(cartHash.getOrDefault("items", "[]"),
                new TypeReference<List<Map<String, Object>>>() {
                });
        int firstId = items.isEmpty() ? 1 : ((Number) items.getFirst().getOrDefault("product_id", 0)).intValue();
        var history = redis.recentOrders(user);
        redis.product(firstId);
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
        var rate = redis.incrementRateLimit(userNum);
        var history = redis.recordOrder(user, json.writeValueAsString(order));
        var popularity = redis.incrementPopularity(items.getFirst().productId());
        var data = redis.calculateCart(request);
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

}
