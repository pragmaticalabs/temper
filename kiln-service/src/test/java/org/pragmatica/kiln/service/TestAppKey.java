package org.pragmatica.kiln.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;


/// A throwaway RSA key in PKCS#8 PEM form, generated at test time and written only below a test's temp directory. No key
/// is committed.
public final class TestAppKey {
    private TestAppKey() {}

    public static Path write(Path file, String mode) throws IOException {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");

            generator.initialize(2048);
            var der = generator.generateKeyPair().getPrivate().getEncoded();
            var pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                    + "\n-----END PRIVATE KEY-----\n";

            Files.setPosixFilePermissions(Files.createDirectories(file.getParent()), PosixFilePermissions.fromString("rwx------"));
            Files.writeString(file, pem);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));

            return file;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
