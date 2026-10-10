package com.benchmark.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import me.kpavlov.kt.schema.Description;
import me.kpavlov.kt.schema.Schema;

@Schema
public record CheckoutItem(
        @JsonProperty("product_id") @Description("Positive product ID.") int productId,
        @Description("Positive item quantity.") int quantity) {
    public CheckoutItem {
        if (productId < 1 || quantity < 1) {
            throw new IllegalArgumentException("product_id and quantity must be positive");
        }
    }
}
