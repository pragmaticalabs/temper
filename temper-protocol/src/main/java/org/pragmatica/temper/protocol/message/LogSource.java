package org.pragmatica.temper.protocol.message;

import com.fasterxml.jackson.annotation.JsonProperty;


/// Where a log tail came from: the unit's `log` file (written at exit) or `docker logs` (still running).
public enum LogSource {
    @JsonProperty("file")
    FILE,
    @JsonProperty("docker")
    DOCKER
}
