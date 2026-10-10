package com.benchmark.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import me.kpavlov.kt.schema.Description;
import me.kpavlov.kt.schema.Schema;
import org.jspecify.annotations.Nullable;

@Schema
public record CartRequest(
        @JsonProperty(value = "user_id", defaultValue = "user-00042") @Description("User ID. Defaults to user-00042.") @Nullable String userId) {
    public CartRequest {
        userId = userId == null ? "user-00042" : userId;
    }
}
