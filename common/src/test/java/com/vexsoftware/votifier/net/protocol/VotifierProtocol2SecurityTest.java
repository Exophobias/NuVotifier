package com.vexsoftware.votifier.net.protocol;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.net.VotifierSession;
import com.vexsoftware.votifier.platform.VotifierPlugin;
import com.vexsoftware.votifier.support.forwarding.proxy.client.VoteRequest;
import com.vexsoftware.votifier.support.forwarding.proxy.client.VotifierProtocol2Encoder;
import com.vexsoftware.votifier.util.KeyCreator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VotifierProtocol2SecurityTest {
    private static final Key KEY = KeyCreator.createKeyFrom("test-authentication-key-for-security-tests");

    @Test
    void acceptsAFragmentedAuthenticatedUtf8VoteWithOptionalData() throws Exception {
        VotifierSession session = new VotifierSession();
        VotifierPlugin plugin = plugin();
        JsonObject payload = payload(session);
        payload.addProperty("serviceName", "Tést service");
        payload.addProperty("timestamp", 1_700_000_000_123L);
        payload.addProperty("uuid", "01234567-89ab-cdef-0123-456789abcdef");
        payload.addProperty("additionalData", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        EmbeddedChannel channel = wireChannel(session, plugin);
        ByteBuf frame = frame(envelope(payload.toString(), KEY).toString());
        try {
            assertFalse(channel.writeInbound(frame.readRetainedSlice(1)));
            assertFalse(channel.writeInbound(frame.readRetainedSlice(2)));
            channel.writeInbound(frame.readRetainedSlice(frame.readableBytes()));
            verify(plugin, times(1)).onVoteReceived(eq(new Vote(payload)), eq(VotifierSession.ProtocolVersion.TWO), anyString());
            assertTrue(session.hasCompletedVote());
            assertFalse(channel.isOpen());
            ByteBuf response = channel.readOutbound();
            try {
                assertEquals("{\"status\":\"ok\"}\r\n", response.toString(StandardCharsets.UTF_8));
            } finally {
                response.release();
            }
        } finally {
            frame.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void encoderUsesUtf8ByteLengthsForAnAuthenticatedUnicodeServiceRoundtrip() throws Exception {
        VotifierSession session = new VotifierSession();
        VotifierPlugin plugin = plugin();
        Vote vote = new Vote("Tést 服务", "normal_user_123", "127.0.0.1", "0");
        EmbeddedChannel encoder = new EmbeddedChannel(new VotifierProtocol2Encoder(KEY));
        EmbeddedChannel receiver = wireChannel(session, plugin);
        try {
            assertTrue(encoder.writeOutbound(new VoteRequest(session.getChallenge(), vote)));
            ByteBuf encoded = encoder.readOutbound();
            byte[] frame;
            try {
                assertEquals(0x733A, encoded.getUnsignedShort(encoded.readerIndex()));
                assertEquals(encoded.readableBytes() - 4, encoded.getUnsignedShort(encoded.readerIndex() + 2));
                assertTrue(encoded.readableBytes() <= VotifierProtocol2Decoder.MAX_FRAME_BYTES);
                frame = new byte[encoded.readableBytes()];
                encoded.readBytes(frame);
            } finally {
                encoded.release();
            }
            receiver.writeInbound(Unpooled.wrappedBuffer(frame));
            verify(plugin, times(1)).onVoteReceived(eq(vote), eq(VotifierSession.ProtocolVersion.TWO), anyString());
            assertTrue(session.hasCompletedVote());
            assertFalse(receiver.isOpen());
        } finally {
            encoder.finishAndReleaseAll();
            receiver.finishAndReleaseAll();
        }
    }

    @Test
    void defaultAuthenticationUsesOneTokenGenerationWhenReloadChangesTheView() throws Exception {
        VotifierSession session = new VotifierSession();
        VotifierPlugin plugin = plugin();
        Key nextGeneration = KeyCreator.createKeyFrom("replacement-authentication-key-after-reload");
        when(plugin.getTokens()).thenReturn(Map.of("default", KEY), Map.of("default", nextGeneration));
        EmbeddedChannel receiver = wireChannel(session, plugin);
        try {
            JsonObject payload = payload(session);
            receiver.writeInbound(frame(envelope(payload.toString(), KEY).toString()));
            verify(plugin, times(1)).getTokens();
            verify(plugin, times(1)).onVoteReceived(eq(new Vote(payload)), eq(VotifierSession.ProtocolVersion.TWO), anyString());
            assertTrue(session.hasCompletedVote());
            assertFalse(receiver.isOpen());
        } finally {
            receiver.finishAndReleaseAll();
        }
    }

    @Test
    void encoderRejectsOversizedFramesBeforeWritingAnyPacketBytes() {
        EmbeddedChannel encoder = new EmbeddedChannel(new VotifierProtocol2Encoder(KEY));
        try {
            Vote vote = new Vote("sensitive-large-service".repeat(100), "test_user", "127.0.0.1", "0");
            EncoderException failure = assertThrows(EncoderException.class,
                    () -> encoder.writeOutbound(new VoteRequest("challenge", vote)));
            assertFalse(failure.getMessage().contains("sensitive-large-service"));
            assertNull(encoder.readOutbound());
        } finally {
            encoder.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}", "{payload:'invalid'}", "{\"payload\":", "{}{}"})
    void rejectsMalformedOrNonObjectEnvelopes(String message) {
        assertRejected(message, new VotifierSession());
    }

    @Test
    void rejectsDuplicateEnvelopeFields() throws Exception {
        VotifierSession session = new VotifierSession();
        String valid = envelope(payload(session).toString(), KEY).toString();
        assertRejected("{\"signature\":\"ignored\"," + valid.substring(1), session);
    }

    @Test
    void rejectsDuplicatePayloadFieldsEvenWithAValidMac() throws Exception {
        VotifierSession session = new VotifierSession();
        String valid = payload(session).toString();
        String duplicate = "{\"username\":\"forged\"," + valid.substring(1);
        assertRejected(envelope(duplicate, KEY).toString(), session);
    }

    @Test
    void rejectsDuplicateNamesWrittenWithUnicodeEscapes() throws Exception {
        VotifierSession session = new VotifierSession();
        String valid = payload(session).toString();
        String duplicate = "{\"user\\u006eame\":\"forged\"," + valid.substring(1);
        assertRejected(envelope(duplicate, KEY).toString(), session);
    }

    @Test
    void rejectsMalformedUtf8BeforeItCanBeReplacedForAuthentication() {
        EmbeddedChannel channel = new EmbeddedChannel(new VotifierProtocol2Utf8Decoder());
        try {
            assertThrows(DecoderException.class, () -> channel.writeInbound(
                    Unpooled.wrappedBuffer(new byte[]{(byte) 0xc3, 0x28})));
            assertNull(channel.readInbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"challenge", "serviceName", "username", "address", "timestamp", "uuid", "additionalData"})
    void rejectsNullRequiredAndOptionalFields(String name) throws Exception {
        VotifierSession session = new VotifierSession();
        JsonObject payload = payload(session);
        payload.add(name, JsonNull.INSTANCE);
        assertRejected(envelope(payload.toString(), KEY).toString(), session);
    }

    @ParameterizedTest
    @ValueSource(strings = {"challenge", "serviceName", "username", "address", "uuid", "additionalData"})
    void rejectsNumericFieldsThatMustBeStrings(String name) throws Exception {
        VotifierSession session = new VotifierSession();
        JsonObject payload = payload(session);
        payload.addProperty(name, 123);
        assertRejected(envelope(payload.toString(), KEY).toString(), session);
    }

    @ParameterizedTest
    @ValueSource(strings = {"serviceName", "username", "address", "timestamp"})
    void rejectsControlCharactersInAuthenticatedVoteFields(String name) throws Exception {
        VotifierSession session = new VotifierSession();
        JsonObject payload = payload(session);
        payload.addProperty(name, "bad\nfield");
        assertRejected(envelope(payload.toString(), KEY).toString(), session);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name with space", "name;command", "test-user", "/operator", "tést", "玩家"})
    void rejectsSignedUsernamesOutsideTheJavaPlayerNameAlphabet(String username) throws Exception {
        VotifierSession session = new VotifierSession();
        JsonObject payload = payload(session);
        payload.addProperty("username", username);
        assertRejected(envelope(payload.toString(), KEY).toString(), session);
    }

    @Test
    void rejectsNestedPayloadFieldsAndTrailingData() throws Exception {
        VotifierSession session = new VotifierSession();
        JsonObject payload = payload(session);
        payload.add("extension", new JsonObject());
        assertRejected(envelope(payload.toString(), KEY).toString(), session);
        assertRejected(envelope(payload(session).toString() + "{}", KEY).toString(), session);
    }

    @Test
    void rejectsTooManyFieldsWithoutRecursivelyParsingAttackerData() throws Exception {
        VotifierSession session = new VotifierSession();
        JsonObject payload = payload(session);
        for (int index = 0; index < 33; index++) {
            payload.addProperty("x" + index, index);
        }
        assertRejected(envelope(payload.toString(), KEY).toString(), session);
    }

    @Test
    void rejectsOversizedAndInvalidVoteFields() throws Exception {
        VotifierSession session = new VotifierSession();
        JsonObject payload = payload(session);
        payload.addProperty("username", "x".repeat(17));
        assertRejected(envelope(payload.toString(), KEY).toString(), session);
        payload = payload(session);
        payload.addProperty("serviceName", "x".repeat(129));
        assertRejected(envelope(payload.toString(), KEY).toString(), session);
        payload = payload(session);
        payload.addProperty("uuid", "1-1-1-1-1");
        assertRejected(envelope(payload.toString(), KEY).toString(), session);
        payload = payload(session);
        payload.addProperty("additionalData", "not%base64");
        assertRejected(envelope(payload.toString(), KEY).toString(), session);
        payload = payload(session);
        payload.addProperty("timestamp", 1.5);
        assertRejected(envelope(payload.toString(), KEY).toString(), session);
        assertRejected("{\"payload\":\"" + "x".repeat(1024) + "\"}", session);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 31, 33, 64})
    void rejectsSignaturesWithTheWrongDecodedLength(int length) throws Exception {
        VotifierSession session = new VotifierSession();
        JsonObject message = envelope(payload(session).toString(), KEY);
        message.addProperty("signature", Base64.getEncoder().encodeToString(new byte[length]));
        assertRejected(message.toString(), session);
    }

    @Test
    void rejectsMalformedSignatureAndWrongAuthenticationKey() throws Exception {
        VotifierSession session = new VotifierSession();
        JsonObject message = envelope(payload(session).toString(), KEY);
        message.addProperty("signature", "not%base64");
        assertRejected(message.toString(), session);
        assertRejected(envelope(payload(session).toString(), KeyCreator.createKeyFrom("another-test-key")).toString(), session);
    }

    @Test
    void rejectsReplayOnAConnectionWithADifferentChallenge() throws Exception {
        VotifierSession original = new VotifierSession();
        assertRejected(envelope(payload(original).toString(), KEY).toString(), new VotifierSession());
    }

    @Test
    void oversizedWireFramesCloseWithoutDispatchingVotesOrEchoingInput() throws Exception {
        VotifierSession session = new VotifierSession();
        VotifierPlugin plugin = plugin();
        EmbeddedChannel channel = wireChannel(session, plugin);
        try {
            channel.writeInbound(frame("sensitive-attacker-input".repeat(50)));
            verify(plugin, never()).onVoteReceived(any(), any(), anyString());
            assertFalse(channel.isOpen());
            ByteBuf response = channel.readOutbound();
            try {
                String error = response.toString(StandardCharsets.UTF_8);
                assertTrue(error.contains("\"status\":\"error\""));
                assertFalse(error.contains("sensitive-attacker-input"));
                assertFalse(error.contains("TooLongFrameException"));
            } finally {
                response.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void assertRejected(String message, VotifierSession session) {
        EmbeddedChannel channel = new EmbeddedChannel(new VotifierProtocol2Decoder());
        channel.attr(VotifierSession.KEY).set(session);
        channel.attr(VotifierPlugin.KEY).set(plugin());
        try {
            assertThrows(DecoderException.class, () -> channel.writeInbound(message));
            assertNull(channel.readInbound());
            assertFalse(session.hasCompletedVote());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel wireChannel(VotifierSession session, VotifierPlugin plugin) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(VotifierSession.KEY).set(session);
        channel.attr(VotifierPlugin.KEY).set(plugin);
        channel.pipeline().addLast("protocolDifferentiator", new VotifierProtocolDifferentiator(false, false));
        channel.pipeline().addLast("voteHandler", new VoteInboundHandler(plugin));
        return channel;
    }

    private static VotifierPlugin plugin() {
        VotifierPlugin plugin = mock(VotifierPlugin.class);
        when(plugin.getTokens()).thenReturn(Map.of("default", KEY));
        return plugin;
    }

    private static JsonObject payload(VotifierSession session) {
        JsonObject payload = new Vote("Test", "test_user", "127.0.0.1", "0").serialize();
        payload.addProperty("challenge", session.getChallenge());
        return payload;
    }

    private static JsonObject envelope(String payload, Key key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("payload", payload);
        envelope.addProperty("signature", Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))));
        return envelope;
    }

    private static ByteBuf frame(String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        return Unpooled.buffer(bytes.length + 4).writeShort(0x733A).writeShort(bytes.length).writeBytes(bytes);
    }
}
