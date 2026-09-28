package com.vexsoftware.votifier.net.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Rejects malformed UTF-8 instead of silently replacing bytes before MAC verification. */
final class VotifierProtocol2Utf8Decoder extends MessageToMessageDecoder<ByteBuf> {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf message, List<Object> output) {
        if (!ByteBufUtil.isText(message, StandardCharsets.UTF_8)) {
            throw new CorruptedFrameException("Malformed UTF-8 vote packet");
        }
        output.add(message.toString(StandardCharsets.UTF_8));
    }
}
