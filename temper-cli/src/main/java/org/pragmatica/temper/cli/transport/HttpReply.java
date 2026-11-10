package org.pragmatica.temper.cli.transport;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;


/// The controller's answer: status and body text, undecoded, and its `Temper-Version` header (D-6), none when the
/// answer has none.
public record HttpReply(int status, String body, Option<String> temperVersion) {
    public static HttpReply httpReply(int status, String body) {
        return httpReply(status, body, none());
    }

    public static HttpReply httpReply(int status, String body, Option<String> temperVersion) {
        return new HttpReply(status, body, temperVersion);
    }
}
