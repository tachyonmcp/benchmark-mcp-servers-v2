package com.benchmark.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import me.kpavlov.kt.schema.Schema;

@Schema
public record Product(
        int id, String sku, String name, double price, double rating,
        @JsonProperty("popularity_rank") int popularityRank) {}
