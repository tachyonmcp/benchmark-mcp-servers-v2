package com.benchmark.mcp;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;

final class HealthCheck extends ChannelInboundHandlerAdapter {
    private static final byte[] BODY = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
    private boolean health;
    private boolean head;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (message instanceof HttpRequest request) {
            head = request.method().equals(HttpMethod.HEAD);
            health = request.uri().equals("/health")
                    && (head || request.method().equals(HttpMethod.GET));
        }
        if (!health) {
            ctx.fireChannelRead(message);
            return;
        }
        boolean complete = message instanceof LastHttpContent;
        ReferenceCountUtil.release(message);
        if (complete) {
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                    head ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(BODY));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, BODY.length);
            response.headers().set(HttpHeaderNames.CONNECTION, "close");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
