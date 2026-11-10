package org.pragmatica.temper.ghapp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Reading the App's private key: the file is refused when group- or other-readable, when it is a
/// symbolic link or when its directory or any directory above it up to the home is group- or other-writable, and its PEM is
/// accepted in either form GitHub may download, PKCS#1 (`BEGIN RSA PRIVATE KEY`) or PKCS#8 (`BEGIN PRIVATE KEY`). The JDK
/// `KeyFactory` reads only PKCS#8, so a PKCS#1 body is wrapped into a PKCS#8 `PrivateKeyInfo` first.
sealed interface AppKey {
    Pattern PEM = Pattern.compile("\\s*-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\\s]+)-----END \\1-----\\s*");
    String PKCS1 = "RSA PRIVATE KEY";
    String PKCS8 = "PRIVATE KEY";

    /// `SEQUENCE { OID rsaEncryption, NULL }`
    byte[] RSA_ALGORITHM = {0x30,
                            0x0d,
                            0x06,
                            0x09,
                            0x2a,
                            (byte) 0x86,
                            0x48,
                            (byte) 0x86,
                            (byte) 0xf7,
                            0x0d,
                            0x01,
                            0x01,
                            0x01,
                            0x05,
                            0x00};

    /// `INTEGER 0`
    byte[] VERSION_ZERO = {0x02, 0x01, 0x00};

    int SEQUENCE = 0x30;
    int OCTET_STRING = 0x04;

    /// The key at `file`, read afresh on every call so that a rotated key needs no restart. Directories are checked up
    /// to the user's home (`user.home`).
    static Result<PrivateKey> load(Path file) {
        return load(file,
                    Path.of(System.getProperty("user.home")));
    }

    /// As [#load(Path)], with the directory walk ending at `home`.
    static Result<PrivateKey> load(Path file, Path home) {
        return placed(file, home).flatMap(_ -> permissions(file))
                     .flatMap(_ -> read(file))
                     .flatMap(pem -> parse(file, pem));
    }

    /// The key is a regular file reached without a link, in a directory that only its owner can write, and so is every
    /// directory above it up to `home` (like ssh `StrictModes`): a link or a writable directory on the way lets another
    /// user swap the key, or the directory that holds it, between the mode check and the read. Links
    /// among the directories are resolved first, so it is the real directories that are checked. A key whose real
    /// directory is outside `home` has only that directory checked: nothing above it is the user's to judge.
    private static Result<Path> placed(Path file, Path home) {
        if (Files.isSymbolicLink(file)) {
            return AppAuthError.KeyFileUnsafe.FACTORY.apply(file, "it is a symbolic link").result();
        }

        return writableDirectory(file, home).flatMap(unsafe -> unsafe.<Result<Path>> map(directory -> AppAuthError.KeyFileUnsafe.FACTORY.apply(file,
                                                                                                                                               "directory " + directory
                                                                                                                                              + " is group- or other-writable").result())
                                                                     .or(() -> success(file)));
    }

    /// The first real directory from the key's own up to `home` that group or others can write.
    private static Result<Option<Path>> writableDirectory(Path file, Path home) {
        return realPath(file,
                        file.toAbsolutePath().getParent()).flatMap(directory -> realPath(file, home).flatMap(root -> walk(file,
                                                                                                                          directory,
                                                                                                                          root,
                                                                                                                          directory.startsWith(root))));
    }

    private static Result<Path> realPath(Path file, Path path) {
        return Result.lift(thrown -> AppAuthError.KeyUnreadable.FACTORY.apply(file,
                                                                              thrown.getClass().getSimpleName()),
                           () -> path.toRealPath());
    }

    /// `current` and, while `within` the home, each parent up to `root`.
    private static Result<Option<Path>> walk(Path file, Path current, Path root, boolean within) {
        return writableByOthers(file, current).flatMap(writable -> writable
                                                                   ? success(some(current))
                                                                   : within && !current.equals(root)
                                                                     ? walk(file, current.getParent(), root, true)
                                                                     : success(none()));
    }

    private static Result<Boolean> writableByOthers(Path file, Path directory) {
        return Result.<Set<PosixFilePermission>> lift(thrown -> AppAuthError.KeyUnreadable.FACTORY.apply(file,
                                                                                                         thrown.getClass()
                                                                                                               .getSimpleName()),
                                                      () -> Files.getPosixFilePermissions(directory))
                     .map(AppKey::writableByOthers);
    }

    private static boolean writableByOthers(Set<PosixFilePermission> modes) {
        return modes.contains(PosixFilePermission.GROUP_WRITE) || modes.contains(PosixFilePermission.OTHERS_WRITE);
    }

    private static Result<Set<PosixFilePermission>> permissions(Path file) {
        return Result.<Set<PosixFilePermission>> lift(thrown -> AppAuthError.KeyUnreadable.FACTORY.apply(file,
                                                                                                         thrown.getClass()
                                                                                                               .getSimpleName()),
                                                      () -> Files.getPosixFilePermissions(file))
                     .flatMap(modes -> Verify.ensure(modes,
                                                     AppKey::closed,
                                                     open -> AppAuthError.KeyFileTooOpen.FACTORY.apply(file,
                                                                                                       PosixFilePermissions.toString(open))));
    }

    private static boolean closed(Set<PosixFilePermission> modes) {
        return ! openToOthers(modes);
    }

    private static boolean openToOthers(Set<PosixFilePermission> modes) {
        return modes.stream()
                    .anyMatch(AppKey::groupOrOther);
    }

    private static boolean groupOrOther(PosixFilePermission mode) {
        return mode.name()
                   .startsWith("GROUP_") || mode.name()
                                                .startsWith("OTHERS_");
    }

    private static Result<String> read(Path file) {
        return Result.lift(thrown -> AppAuthError.KeyUnreadable.FACTORY.apply(file,
                                                                              thrown.getClass().getSimpleName()),
                           () -> Files.readString(file, StandardCharsets.US_ASCII));
    }

    private static Result<PrivateKey> parse(Path file, String pem) {
        var matcher = PEM.matcher(pem);

        return matcher.matches()
               ? pkcs8(file, matcher.group(1), matcher.group(2)).flatMap(der -> generate(file, der))
               : AppAuthError.KeyMalformed.FACTORY.apply(file, "no unencrypted PEM block").result();
    }

    private static Result<byte[]> pkcs8(Path file, String type, String body) {
        return switch (type) {
            case PKCS1 -> decode(file, body).map(AppKey::wrap);
            case PKCS8 -> decode(file, body);
            default -> AppAuthError.KeyMalformed.FACTORY.apply(file, "PEM type " + type).result();
        };
    }

    private static Result<byte[]> decode(Path file, String body) {
        return Result.lift(thrown -> AppAuthError.KeyMalformed.FACTORY.apply(file, "base64"),
                           () -> Base64.getMimeDecoder().decode(body));
    }

    private static Result<PrivateKey> generate(Path file, byte[] der) {
        return Result.lift(thrown -> AppAuthError.KeyMalformed.FACTORY.apply(file,
                                                                             thrown.getClass().getSimpleName()),
                           () -> KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der)));
    }

    /// `PrivateKeyInfo ::= SEQUENCE { version 0, rsaEncryption, OCTET STRING { RSAPrivateKey } }`
    static byte[] wrap(byte[] pkcs1) {
        var content = new ByteArrayOutputStream();

        content.writeBytes(VERSION_ZERO);
        content.writeBytes(RSA_ALGORITHM);
        content.writeBytes(der(OCTET_STRING, pkcs1));

        return der(SEQUENCE, content.toByteArray());
    }

    private static byte[] der(int tag, byte[] content) {
        var out = new ByteArrayOutputStream();

        out.write(tag);
        out.writeBytes(length(content.length));
        out.writeBytes(content);

        return out.toByteArray();
    }

    private static byte[] length(int length) {
        return length < 0x80
               ? new byte[]{(byte) length}
               : longForm(length);
    }

    private static byte[] longForm(int length) {
        var significant = Integer.BYTES - Integer.numberOfLeadingZeros(length) / Byte.SIZE;
        var out = new byte[1 + significant];

        out[0] = (byte)(0x80 | significant);
        for (var i = 0; i < significant; i++) {
            out[1 + i] = (byte)(length >>(Byte.SIZE * (significant - 1 - i)));
        }

        return out;
    }

    record unused() implements AppKey {}
}
