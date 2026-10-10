package com.benchmark.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import me.kpavlov.kt.schema.Description;
import me.kpavlov.kt.schema.Schema;
import org.jspecify.annotations.Nullable;

@Schema
public record CheckoutRequest(
        @JsonProperty(value = "user_id", defaultValue = "user-00042") @Description("User ID. Defaults to user-00042.") @Nullable String userId,
        @Description("Items to purchase. Missing, null or empty uses the benchmark's two default items.")
        @Nullable List<CheckoutItem> items) {
    public CheckoutRequest {
        userId = userId == null ? "user-00042" : userId;
        items = items == null || items.isEmpty()
                ? List.of(new CheckoutItem(42, 2), new CheckoutItem(1337, 1))
                : List.copyOf(items);
    }
}
