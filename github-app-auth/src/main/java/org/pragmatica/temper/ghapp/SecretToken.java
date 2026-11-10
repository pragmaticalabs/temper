package org.pragmatica.temper.ghapp;

/// An installation token. `toString` is `ghs_***`, so printing a [org.pragmatica.lang.Promise], a
/// [org.pragmatica.lang.Result] or any record that holds one never shows the secret; [#value] is the one way to it.
public record SecretToken(String value) {
    @Override
    public String toString() {
        return "ghs_***";
    }
}
