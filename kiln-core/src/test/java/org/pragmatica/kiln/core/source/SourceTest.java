package org.pragmatica.kiln.core.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.ModelError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.Fixtures.failure;
import static org.pragmatica.kiln.core.Fixtures.ok;

/// kiln-f1-design §1.1: each source's key, and the key parsed back to the same source.
class SourceTest {
    @Test
    void key_namesEachSource() {
        assertThat(Source.GITHUB.key()).isEqualTo("github");
        assertThat(Source.MIRROR.key()).isEqualTo("mirror");
        assertThat(Source.TEMPER.key()).isEqualTo("temper");
        assertThat(Source.HOSTS.key()).isEqualTo("hosts");
        assertThat(Source.MAC.key()).isEqualTo("mac");
        assertThat(Source.host(ok(HostName.hostName("bigboy"))).key()).isEqualTo("host:bigboy");
    }

    @ParameterizedTest
    @ValueSource(strings = {"github", "mirror", "temper", "hosts", "mac", "host:bigboy", "host:cloudbb-3"})
    void source_parsesEveryKey_backToTheSameKey(String key) {
        assertThat(ok(Source.source(key)).key()).isEqualTo(key);
    }

    @Test
    void source_parsesToTheSameValue() {
        assertThat(ok(Source.source("github"))).isEqualTo(Source.GITHUB);
        assertThat(ok(Source.source("mac"))).isEqualTo(Source.MAC);
        assertThat(ok(Source.source("hosts"))).isEqualTo(Source.HOSTS);
        assertThat(ok(Source.source("host:bigboy"))).isEqualTo(Source.host(ok(HostName.hostName("bigboy"))));
    }

    /// `host:mac` would be a second key for the Mac's rows; an unknown or malformed key never reads as a source.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"host:mac", "host:", "host:Bigboy", "GitHub", "gh", "bigboy", "xhost:bigboy", " github", "  "})
    void source_refuses(String key) {
        assertThat(failure(Source.source(key))).isInstanceOf(ModelError.InvalidValue.class);
    }
}
