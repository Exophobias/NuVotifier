package com.vexsoftware.votifier.net.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.net.VotifierSession;
import com.vexsoftware.votifier.platform.VotifierPlugin;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToMessageDecoder;

import javax.crypto.Mac;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Decodes bounded, unambiguous protocol 2 JSON votes. */
public class VotifierProtocol2Decoder extends MessageToMessageDecoder<String> {
    public static final int MAX_FRAME_BYTES = 1024;
    private static final int MAX_MESSAGE_BYTES = MAX_FRAME_BYTES - 4;
    private static final int MAX_FIELDS = 32;
    private static final int MAX_FIELD_NAME_LENGTH = 64;

    @Override
    protected void decode(ChannelHandlerContext ctx, String message, List<Object> list) throws Exception {
        JsonObject envelope = readObject(message);
        VotifierSession session = ctx.channel().attr(VotifierSession.KEY).get();
        if (session == null || session.hasCompletedVote()) {
            throw invalid("Invalid vote session");
        }

        String payload = requireString(envelope, "payload", MAX_MESSAGE_BYTES, false);
        JsonObject fields = readObject(payload);
        String challenge = requireString(fields, "challenge", 128, false);
        if (!challenge.equals(session.getChallenge())) {
            throw invalid("Challenge is not valid");
        }

        String serviceName = requireString(fields, "serviceName", 128, false);
        VotifierPlugin plugin = ctx.channel().attr(VotifierPlugin.KEY).get();
        Map<String, Key> tokens = plugin.getTokens();
        Key key = tokens.get(serviceName);
        if (key == null) {
            key = tokens.get("default");
        }
        if (key == null) {
            // Do not reflect an unauthenticated field into the response or logs.
            throw invalid("Vote service is not configured");
        }

        String encodedSignature = requireString(envelope, "signature", 44, false);
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(encodedSignature);
        } catch (IllegalArgumentException exception) {
            throw invalid("Signature is not valid");
        }
        if (signature.length != 32 || !hmacEqual(signature, payload.getBytes(StandardCharsets.UTF_8), key)) {
            throw invalid("Signature is not valid");
        }

        String username = requireString(fields, "username", 16, false);
        if (!username.matches("[A-Za-z0-9_]{1,16}")) {
            throw invalid("Invalid username characters");
        }
        requireString(fields, "address", 128, true);
        validateTimestamp(fields.get("timestamp"));
        if (fields.has("uuid")) {
            String uuid = requireString(fields, "uuid", 36, false);
            try {
                if (!UUID.fromString(uuid).toString().equalsIgnoreCase(uuid)) {
                    throw invalid("Invalid uuid");
                }
            } catch (IllegalArgumentException exception) {
                throw invalid("Invalid uuid");
            }
        }
        if (fields.has("additionalData")) {
            String data = requireString(fields, "additionalData", MAX_MESSAGE_BYTES, true);
            try {
                Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException exception) {
                throw invalid("Invalid additionalData");
            }
        }

        list.add(new Vote(fields));
        ctx.pipeline().remove(this);
    }

    /** Flat scalar objects retain extension fields while rejecting duplicates and nested input. */
    private static JsonObject readObject(String json) {
        if (json.length() > MAX_MESSAGE_BYTES
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw invalid("Vote packet is too large");
        }
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.setNestingLimit(1);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw invalid("Malformed vote packet");
            }
            reader.beginObject();
            JsonObject object = new JsonObject();
            Set<String> names = new HashSet<>();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (name.length() > MAX_FIELD_NAME_LENGTH || names.size() >= MAX_FIELDS || !names.add(name)) {
                    throw invalid("Duplicate or excessive vote fields");
                }
                switch (reader.peek()) {
                    case STRING:
                        object.addProperty(name, reader.nextString());
                        break;
                    case NUMBER:
                        String number = reader.nextString();
                        if (number.length() > 64) {
                            throw invalid("Invalid numeric vote field");
                        }
                        object.add(name, new JsonPrimitive(new BigDecimal(number)));
                        break;
                    case BOOLEAN:
                        object.addProperty(name, reader.nextBoolean());
                        break;
                    case NULL:
                        reader.nextNull();
                        object.add(name, JsonNull.INSTANCE);
                        break;
                    default:
                        throw invalid("Nested vote fields are not supported");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw invalid("Trailing vote packet data");
            }
            return object;
        } catch (IOException | IllegalStateException | NumberFormatException exception) {
            // Parser exception messages can include attacker-controlled input.
            throw invalid("Malformed vote packet");
        }
    }

    private static String requireString(JsonObject fields, String name, int maximumLength, boolean allowEmpty) {
        JsonElement field = fields.get(name);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) {
            throw invalid("Missing or invalid " + name);
        }
        String value = field.getAsString();
        if ((!allowEmpty && value.isEmpty()) || value.length() > maximumLength) {
            throw invalid("Invalid " + name + " length");
        }
        // payload is JSON text and can contain whitespace; individual fields cannot inject log lines.
        if (!"payload".equals(name) && value.codePoints().anyMatch(character ->
                Character.isISOControl(character) || character == 0x2028 || character == 0x2029)) {
            throw invalid("Invalid " + name + " characters");
        }
        return value;
    }

    private static void validateTimestamp(JsonElement timestamp) {
        if (timestamp == null || !timestamp.isJsonPrimitive()) {
            throw invalid("Missing or invalid timestamp");
        }
        JsonPrimitive value = timestamp.getAsJsonPrimitive();
        if (value.isString()) {
            JsonObject field = new JsonObject();
            field.add("timestamp", value);
            requireString(field, "timestamp", 64, false);
        } else if (value.isNumber()) {
            try {
                value.getAsBigDecimal().longValueExact();
            } catch (ArithmeticException exception) {
                throw invalid("Invalid timestamp");
            }
        } else {
            throw invalid("Invalid timestamp type");
        }
    }

    private static boolean hmacEqual(byte[] signature, byte[] message, Key key)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        // Both inputs are fixed-length SHA-256 MACs; MessageDigest compares every byte.
        return MessageDigest.isEqual(mac.doFinal(message), signature);
    }

    private static CorruptedFrameException invalid(String message) {
        return new CorruptedFrameException(message);
    }
}
