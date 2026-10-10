package com.benchmark.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import me.kpavlov.kt.schema.Schema;

@Schema
public record Cart(
        @JsonProperty("item_count") int itemCount,
        @JsonProperty("estimated_total") double estimatedTotal,
        List<Map<String, Object>> items) {}
