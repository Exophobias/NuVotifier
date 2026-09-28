package com.vexsoftware.votifier.net.protocol.v1crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RSAIOSecurityTest {
    @TempDir
    Path directory;

    @Test
    void corruptPrivateKeyErrorsNeverIncludeRecoverableFileContents() throws Exception {
        String sensitive = "private-file-material-not-safe-to-log%%";
        Path key = directory.resolve("private.key");
        Files.writeString(key, sensitive, StandardCharsets.US_ASCII);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RSAIO.readB64File(directory.toFile(), "private.key"));
        StringWriter report = new StringWriter();
        failure.printStackTrace(new PrintWriter(report));
        assertFalse(report.toString().contains(sensitive));
        assertFalse(report.toString().contains(Base64.getEncoder().encodeToString(
                sensitive.getBytes(StandardCharsets.US_ASCII))));
        assertArrayEquals(sensitive.getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(key));
    }

    @Test
    void rejectsOversizedKeyFilesWithoutChangingTheirBytes() throws Exception {
        byte[] oversized = new byte[16 * 1024 + 1];
        Path key = directory.resolve("private.key");
        Files.write(key, oversized);
        assertThrows(IOException.class, () -> RSAIO.readB64File(directory.toFile(), "private.key"));
        assertArrayEquals(oversized, Files.readAllBytes(key));
    }
}
