package com.benchmark.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import me.kpavlov.kt.schema.Description;
import me.kpavlov.kt.schema.Schema;
import org.jspecify.annotations.Nullable;

@Schema
public record SearchRequest(
        @JsonProperty(defaultValue = "Electronics") @Description("Product category. Defaults to Electronics.") @Nullable String category,
        @JsonProperty(value = "min_price", defaultValue = "50.0") @Description("Minimum price. Defaults to 50.0.") @Nullable Double minPrice,
        @JsonProperty(value = "max_price", defaultValue = "500.0") @Description("Maximum price. Defaults to 500.0.") @Nullable Double maxPrice,
        @JsonProperty(defaultValue = "10") @Description("Maximum number of products. Defaults to 10.") @Nullable Integer limit) {
    public SearchRequest {
        category = category == null ? "Electronics" : category;
        minPrice = minPrice == null ? 50.0 : minPrice;
        maxPrice = maxPrice == null ? 500.0 : maxPrice;
        limit = limit == null ? 10 : limit;
    }
}
