package com.benchmark.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import me.kpavlov.kt.schema.Schema;

@Schema
public record CheckoutResponse(
        @JsonProperty("server_type") String serverType,
        @JsonProperty("order_id") String orderId,
        @JsonProperty("user_id") String userId,
        double total,
        @JsonProperty("items_count") int itemsCount,
        @JsonProperty("rate_limit_count") long rateLimitCount,
        String status) {}
