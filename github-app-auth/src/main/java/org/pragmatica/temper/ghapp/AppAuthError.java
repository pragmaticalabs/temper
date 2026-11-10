package org.pragmatica.temper.ghapp;

import java.nio.file.Path;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Why an installation token cannot be had. No message carries a token, a JWT or an
/// `Authorization` header: every value below is a path, a status, a class name or text GitHub sent about the request.
public sealed interface AppAuthError extends Cause {
    /// The exit code a service ends with when it refuses to start for a key file that is group- or other-readable (§6.1).
    int EXIT_CONFIG = 78;

    /// The settings are unusable.
    record ConfigInvalid(String reason, String message) implements AppAuthError {
        public static final Fn1<ConfigInvalid, String> FACTORY = Causes.forOneValue("config: %s", ConfigInvalid::new);
    }

    /// The key file is group- or other-readable: a key is longer-lived than a token (§6.1), so this refuses, where the
    /// CLI's token files only warn.
    record KeyFileTooOpen(Path path, String mode, String message) implements AppAuthError {
        public static final Fn2<KeyFileTooOpen, Path, String> FACTORY = Causes.forTwoValues("key file %s is group- or other-readable (%s); chmod 600",
                                                                                            KeyFileTooOpen::new);

        public int exitCode() {
            return EXIT_CONFIG;
        }
    }

    /// The key file is a symbolic link, or sits in a directory that group or others can write (§6.1); exit 78 like
    /// [KeyFileTooOpen]. The wrappers refuse the same two cases (exit 64).
    record KeyFileUnsafe(Path path, String reason, String message) implements AppAuthError {
        public static final Fn2<KeyFileUnsafe, Path, String> FACTORY = Causes.forTwoValues("key file %s refused: %s; use a regular file in a 0700 directory",
                                                                                           KeyFileUnsafe::new);

        public int exitCode() {
            return EXIT_CONFIG;
        }
    }

    /// The key file cannot be read.
    record KeyUnreadable(Path path, String reason, String message) implements AppAuthError {
        public static final Fn2<KeyUnreadable, Path, String> FACTORY = Causes.forTwoValues("key file %s cannot be read: %s",
                                                                                           KeyUnreadable::new);
    }

    /// The key file holds no RSA private key in PKCS#1 or PKCS#8 PEM form.
    record KeyMalformed(Path path, String reason, String message) implements AppAuthError {
        public static final Fn2<KeyMalformed, Path, String> FACTORY = Causes.forTwoValues("key file %s is not an RSA private key (PKCS#1 or PKCS#8 PEM): %s",
                                                                                          KeyMalformed::new);
    }

    /// The JWT could not be signed.
    record SigningFailed(String reason, String message) implements AppAuthError {
        public static final Fn1<SigningFailed, String> FACTORY = Causes.forOneValue("JWT signing failed: %s",
                                                                                    SigningFailed::new);
    }

    /// The exchange with GitHub failed: connect, TLS, the deadline.
    record MintUnreachable(String reason, String message) implements AppAuthError {
        public static final Fn1<MintUnreachable, String> FACTORY = Causes.forOneValue("mint unreachable: %s",
                                                                                      MintUnreachable::new);
    }

    /// GitHub answered the mint with a status other than 201 (401 bad JWT or clock, 404 not installed, 422 permission).
    record MintRefused(int status, String reason, String message) implements AppAuthError {
        public static final Fn2<MintRefused, Integer, String> FACTORY = Causes.forTwoValues("mint refused: HTTP %s %s",
                                                                                            MintRefused::new);
    }

    /// GitHub answered 201 with a body that holds no usable token.
    record MintMalformed(String reason, String message) implements AppAuthError {
        public static final Fn1<MintMalformed, String> FACTORY = Causes.forOneValue("mint answer unusable: %s",
                                                                                    MintMalformed::new);
    }

    /// No token is held that has not expired, and none could be minted. Callers refuse the GitHub request locally; there
    /// is no fallback to an unauthenticated read or to another identity (§3.2).
    record CredentialUnavailable(String reason, String message) implements AppAuthError {
        public static final Fn1<CredentialUnavailable, String> FACTORY = Causes.forOneValue("credential unavailable: %s",
                                                                                            CredentialUnavailable::new);
    }
}
