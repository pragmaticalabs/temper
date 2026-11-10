package org.pragmatica.temper.ghapp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

/// Throwaway RSA keys, generated at test time and written only below a test's temp directory. No key is committed.
final class TestKeys {
    /// `SEQUENCE(4) INTEGER 0(3) AlgorithmIdentifier(15) OCTET STRING(4)` in front of the PKCS#1 body of a 2048-bit key.
    private static final int PKCS8_PREFIX = 26;

    private TestKeys() {}

    static KeyPair generate() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");

            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String pkcs8Pem(KeyPair pair) {
        return pem("PRIVATE KEY", pair.getPrivate().getEncoded());
    }

    static String pkcs1Pem(KeyPair pair) {
        var pkcs8 = pair.getPrivate().getEncoded();

        return pem("RSA PRIVATE KEY", Arrays.copyOfRange(pkcs8, PKCS8_PREFIX, pkcs8.length));
    }

    static Path write(Path file, String pem, String mode) throws IOException {
        Files.writeString(file, pem);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
        return file;
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
               + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
               + "\n-----END " + type + "-----\n";
    }
}
