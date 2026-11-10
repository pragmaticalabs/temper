package org.pragmatica.kiln.service.store;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.kiln.core.model.ModelError.invalid;


/// kiln-pg connection settings (`[db]` in `kiln.toml`), copied from temper-store's `DbConfig`; the password is read
/// from its file by the caller. `toString` never prints the password.
public record DbConfig(String host, int port, String database, String user, String password, int maxConnections) {
    public static final int DEFAULT_MAX_CONNECTIONS = 10;

    public static Result<DbConfig> dbConfig(String host,
                                            int port,
                                            String database,
                                            String user,
                                            String password,
                                            int maxConnections) {
        return Result.all(Verify.ensure(host,
                                        Verify.Is::notBlank,
                                        invalid("db host")),
                          Verify.ensure(port,
                                        Verify.Is::between,
                                        1,
                                        65535,
                                        invalid("db port")),
                          Verify.ensure(database,
                                        Verify.Is::notBlank,
                                        invalid("db name")),
                          Verify.ensure(user,
                                        Verify.Is::notBlank,
                                        invalid("db user")),
                          Verify.ensure(password,
                                        Verify.Is::notBlank,
                                        invalid("db password")),
                          Verify.ensure(maxConnections,
                                        Verify.Is::between,
                                        1,
                                        100,
                                        invalid("db maxConnections")))
                     .map(DbConfig::new);
    }

    @Override
    public String toString() {
        return "DbConfig[host=" + host
             + ", port=" + port
             + ", database=" + database
             + ", user=" + user
             + ", password=***, maxConnections=" + maxConnections
             + "]";
    }
}
