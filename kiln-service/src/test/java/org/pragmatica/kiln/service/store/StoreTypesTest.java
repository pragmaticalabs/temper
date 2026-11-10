package org.pragmatica.kiln.service.store;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// The store's own value types: `DbConfig` never prints its password, and a database failure is retried exactly as its
/// origin says.
class StoreTypesTest {
    @Test
    void dbConfig_toString_hidesThePassword() {
        var config = DbConfig.dbConfig("127.0.0.1", 55442, "kiln", "kiln", "s3cret", 10).unwrap();

        assertThat(config.toString()).isEqualTo("DbConfig[host=127.0.0.1, port=55442, database=kiln, user=kiln, password=***, maxConnections=10]");
    }

    @Test
    void database_isTerminalAndTransient_asItsOrigin() {
        Cause terminal = KilnStoreError.MigrationTampered.FACTORY.apply(1);
        Cause plain = Causes.cause("connection reset");

        assertThat(KilnStoreError.Database.FACTORY.apply(terminal).isTerminal()).isEqualTo(terminal.isTerminal()).isTrue();
        assertThat(KilnStoreError.Database.FACTORY.apply(plain).isTerminal()).isEqualTo(plain.isTerminal()).isFalse();
        assertThat(KilnStoreError.Database.FACTORY.apply(terminal).isTransient()).isEqualTo(terminal.isTransient());
        assertThat(KilnStoreError.Database.FACTORY.apply(transientCause()).isTransient()).isTrue();
    }

    private static Cause transientCause() {
        return new Transient();
    }

    private record Transient() implements Cause {
        @Override
        public String message() {
            return "busy";
        }

        @Override
        public boolean isTransient() {
            return true;
        }
    }
}
