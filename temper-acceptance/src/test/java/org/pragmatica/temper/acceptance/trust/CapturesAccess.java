package org.pragmatica.temper.acceptance.trust;

/// Test-resource text for the tests of other packages.
public final class CapturesAccess {
    private CapturesAccess() {}

    public static String text(String resource) {
        return Captures.text(resource);
    }
}
