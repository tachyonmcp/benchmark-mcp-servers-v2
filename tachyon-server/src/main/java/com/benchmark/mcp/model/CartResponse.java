package com.benchmark.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import me.kpavlov.kt.schema.Schema;

@Schema
public record CartResponse(
        @JsonProperty("server_type") String serverType,
        @JsonProperty("user_id") String userId,
        Cart cart,
        @JsonProperty("recent_history") List<Map<String, Object>> recentHistory) {}
