package org.pragmatica.temper.cli.transport;

import org.pragmatica.temper.protocol.api.RefusedResponse;
import org.pragmatica.temper.protocol.api.SubmitResponse;


/// The two well-formed answers to `POST /jobs`: accepted (`200`, possibly deduplicated) or refused (`422`).
public sealed interface SubmitAnswer {
    record Accepted(SubmitResponse response) implements SubmitAnswer {}

    record Refused(RefusedResponse response) implements SubmitAnswer {}
}
