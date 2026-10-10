package com.benchmark.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import me.kpavlov.kt.schema.Schema;

@Schema
public record SearchResponse(
        @JsonProperty("server_type") String serverType,
        String category,
        @JsonProperty("total_found") int totalFound,
        List<Product> products,
        @JsonProperty("top10_popular_ids") List<Integer> top10PopularIds) {}
