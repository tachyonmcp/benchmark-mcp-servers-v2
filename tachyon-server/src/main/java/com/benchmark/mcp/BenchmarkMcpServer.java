package com.benchmark.mcp;

import dev.tachyonmcp.api.json.JsonSchemaValidator;
import dev.tachyonmcp.core.server.TachyonServer;
import io.lettuce.core.RedisClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

public final class BenchmarkMcpServer {
    public static void main(String[] args) throws Exception {
        var redisClient = RedisClient.create(env("REDIS_URL", "redis://mcp-redis:6379"));
        try (redisClient;
             var redis = redisClient.connect();
             var http = HttpClient.newBuilder()
                     .connectTimeout(Duration.ofSeconds(5))
                     .version(HttpClient.Version.HTTP_1_1)
                     .build();
             var server = TachyonServer.builder()
                     .info(info -> info.name("benchmark-tachyon").version("1.0.0"))
                     .json(json -> json.schemaValidator(JsonSchemaValidator.noop()))
                     .network(network -> network.host("0.0.0.0")
                             .port(Integer.parseInt(env("SERVER_PORT", "8099")))
                             .endpointPath("/mcp")
                             .allowedHosts("mcp-tachyon-server"))
                     .pipelineCustomizer(pipeline -> pipeline.addBefore(
                             "mcp-endpoint", "health", new HealthCheck()))
                     .annotations(annotations -> annotations.register(new BenchmarkTools(http, redis.async(),
                             env("API_SERVICE_URL", "http://mcp-api-service:8100"),
                             env("SERVER_TYPE", "tachyon"))))
                     .build()) {
            var stopped = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.close();
                stopped.countDown();
            }));
            server.start();
            System.out.println("Tachyon benchmark listening on " + server.port());
            stopped.await();
        }
    }

    private static String env(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }
}
