package com.vexsoftware.votifier.util;

import java.security.SecureRandom;
import java.util.Base64;

public class TokenUtil {
    private TokenUtil() {

    }

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String newToken() {
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }
}
