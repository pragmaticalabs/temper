package org.pragmatica.kiln.service;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;

import org.pragmatica.kiln.service.config.ConfigError;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.config.KilnConfigLoader;
import org.pragmatica.kiln.service.config.KilnSecrets;
import org.pragmatica.kiln.service.github.Credential;
import org.pragmatica.kiln.service.github.GitLsRemote;
import org.pragmatica.kiln.service.github.JdkGitHubHttp;
import org.pragmatica.kiln.service.mirror.GitMirror;
import org.pragmatica.kiln.service.scan.SshScanTransport;
import org.pragmatica.kiln.service.store.DbConfig;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.KilnPostgres;
import org.pragmatica.kiln.service.temper.JdkTemperHttp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.ghapp.AppAuthError;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// `java -jar kiln-service-<ver>-all.jar [path/to/kiln.toml]` (default `~/.config/kiln/kiln.toml`; kiln-f1-design §6).
///
/// Start: parse the config, read the secrets it points at (exactly one GitHub credential: `tokenFile`, `[github.app]` or
/// `anonymous = true`, else exit 78; a set tokenFile that cannot be read fails the start, design §9.2), connect kiln-pg (migrations run), assemble [Kiln] and start it. Any start failure
/// is fatal (exit 1; systemd restarts per the unit). SIGTERM stops the loops and the server, then closes the store.
///
/// No token is ever printed: [Settings#toString] masks both, the log names only the token file's path and whether
/// GitHub is read with a token, and no failure message holds a secret's content ([KilnSecrets]).
public sealed interface Main {
    Logger LOG = LoggerFactory.getLogger(Main.class);
    TimeSpan STOP_WAIT = TimeSpan.timeSpan(30).seconds();

    /// The JVM entry point: its `void` signature is dictated by the JVM, and it blocks until kiln stops.
    @Contract
    @TerminalOperation
    static void main(String[] args) {
        var home = Path.of(System.getProperty("user.home"));
        var stopped = Promise.<Unit> promise();
        // May run late: these callbacks are what resolve `stopped`, which is all `main` waits for.
        start(configFile(args, home),
              home).onSuccess(started -> stopOnShutdown(started, stopped))
             .onFailure(cause -> failed(stopped, cause));
        stopped.await();
    }

    /// A started kiln and the store it owns.
    record Started(Kiln.Running running, KilnStore store) {}

    static Promise<Started> start(Path file, Path home) {
        return start(file, home, Main::ports);
    }

    /// The start path with the ports built by `ports`: [#ports] in production; `MainIT` passes fakes, so the store, the
    /// assembly and the server start for real while GitHub, temper, git and ssh are never reached.
    static Promise<Started> start(Path file, Path home, Fn1<Result<Kiln.Ports>, Settings> ports) {
        return settings(file, home).async()
                       .flatMap(settings -> launch(settings, ports))
                       .onSuccess(started -> LOG.info("kiln listening on 127.0.0.1:{}",
                                                      started.running().port()));
    }

    /// The parsed config with the secrets it points at; `toString` masks the tokens (the database config masks its own
    /// password).
    record Settings(KilnConfig config, Credential credential, String temperToken, DbConfig db) {
        @Override
        public String toString() {
            return "Settings[config=" + config
                 + ", gitHubToken=" + (credential.mode() == Credential.Mode.ANONYMOUS
                                       ? "none"
                                       : "***")
                 + ", temperToken=***, db=" + db
                 + "]";
        }
    }

    /// Not private (an interface has no package-private members): `MainTest` runs this start path from real files.
    static Result<Settings> settings(Path file, Path home) {
        return KilnConfigLoader.load(file, home)
                               .flatMap(Main::withSecrets)
                               .onSuccess(Main::logGitHubAccess);
    }

    /// The credential first, alone: a refusal of the credential configuration keeps its own cause, so [#exitCode] can
    /// read it (`Result.all` would fold it into a composite).
    private static Result<Settings> withSecrets(KilnConfig config) {
        return Credential.credential(config.github(),
                                     JdkGitHubHttp.API,
                                     Clock.systemUTC())
                         .flatMap(credential -> Result.all(KilnSecrets.temperToken(config.temper()),
                                                           KilnSecrets.dbPassword(config.db()).flatMap(password -> dbConfig(config.db(),
                                                                                                                            password)))
                                                      .map((temperToken, db) -> new Settings(config,
                                                                                             credential,
                                                                                             temperToken,
                                                                                             db)));
    }

    private static Result<DbConfig> dbConfig(KilnConfig.Database db, String password) {
        return DbConfig.dbConfig(db.host(), db.port(), db.name(), db.user(), password, DbConfig.DEFAULT_MAX_CONNECTIONS);
    }

    /// Which way GitHub is read, by the token file's path only.
    private static Unit logGitHubAccess(Settings settings) {
        var github = settings.config().github();

        switch (settings.credential().mode()) {
            case APP -> LOG.info("kiln reads GitHub as the {} App, installation {}",
                                 Credential.APP_IDENTITY,
                                 github.app().map(app -> Long.toString(app.installationId())).or("?"));
            case TOKEN -> LOG.info("kiln reads GitHub with the token from {}",
                                   github.tokenFile().map(Path::toString).or("?"));
            case ANONYMOUS -> LOG.info("kiln reads GitHub unauthenticated: [github] anonymous = true");
        }

        return unit();
    }

    private static Promise<Started> launch(Settings settings, Fn1<Result<Kiln.Ports>, Settings> ports) {
        return KilnPostgres.connect(settings.db(),
                                    settings.config().github().repo(),
                                    settings.config().db().highWaterFile())
                           .flatMap(store -> started(settings, store, ports));
    }

    /// FER: a store opened for a kiln that then fails to start is closed, and the start fails with the first cause.
    private static Promise<Started> started(Settings settings,
                                            KilnStore store,
                                            Fn1<Result<Kiln.Ports>, Settings> portsOf) {
        return portsOf.apply(settings)
                      .flatMap(ports -> Kiln.assemble(settings.config(),
                                                      store,
                                                      ports))
                      .async()
                      .flatMap(Kiln::start)
                      .map(running -> new Started(running, store))
                      .onFailure(_ -> store.close());
    }

    /// The production ports: GitHub over the JDK client (the token, when there is one, only in its header), temper with
    /// the read token, the git mirror, and ssh per host with `[scan] transportTimeoutSeconds` (WP9 open item 7).
    static Result<Kiln.Ports> ports(Settings settings) {
        return ports(settings, JdkGitHubHttp.API);
    }

    /// [#ports] with GitHub's API at `api` (a stub in tests): every GitHub request goes through the one
    /// [Credential] of `settings`.
    static Result<Kiln.Ports> ports(Settings settings, URI api) {
        var runner = ProcessRunner.processRunner();
        var config = settings.config();
        var http = JdkGitHubHttp.httpClient();

        return GitMirror.gitMirror(runner,
                                   config.mirror().origin(),
                                   config.mirror().path())
                        .map(mirror -> Kiln.Ports.ports(JdkGitHubHttp.jdkGitHubHttp(http,
                                                                                    api,
                                                                                    settings.credential(),
                                                                                    JdkGitHubHttp.TIMEOUT),
                                                        JdkTemperHttp.jdkTemperHttp(http,
                                                                                    config.temper().url().uri(),
                                                                                    settings.temperToken(),
                                                                                    JdkTemperHttp.TIMEOUT),
                                                        mirror,
                                                        host -> SshScanTransport.sshScanTransport(host,
                                                                                                  runner,
                                                                                                  TimeSpan.fromDuration(config.scan()
                                                                                                                              .transportTimeout())),
                                                        Instant::now,
                                                        GitLsRemote.gitLsRemote(runner, GitLsRemote.TIMEOUT)));
    }

    /// The config file: the first argument, else `~/.config/kiln/kiln.toml`.
    static Path configFile(String[] args, Path home) {
        return args.length > 0
               ? Path.of(args[0])
               : home.resolve(".config/kiln/kiln.toml");
    }

    private static Unit stopOnShutdown(Started started, Promise<Unit> stopped) {
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> stop(started, stopped)));

        return unit();
    }

    /// Runs on the JVM's shutdown-hook thread, which must not return before the loops, the server and the store are
    /// stopped.
    @TerminalOperation
    private static Unit stop(Started started, Promise<Unit> stopped) {
        LOG.info("kiln stopping");
        started.running()
               .stop()
               .flatMap(_ -> started.store()
                                    .close())
               .await(STOP_WAIT)
               .onFailure(cause -> LOG.warn("kiln did not stop cleanly: {}",
                                            cause.message()));
        stopped.succeed(unit());

        return unit();
    }

    /// EX_CONFIG: the exit code of a refused GitHub credential configuration.
    static final int EXIT_CONFIG = 78;

    /// 78 (EX_CONFIG) for a refused GitHub credential or `[server] bind` configuration, or a key file open beyond its owner, else 1.
    static int exitCode(Cause cause) {
        return switch (cause) {
            case ConfigError.Credential _ -> EXIT_CONFIG;
            case ConfigError.Listen _ -> EXIT_CONFIG;
            case AppAuthError.KeyFileTooOpen open -> open.exitCode();
            case AppAuthError.KeyFileUnsafe unsafe -> unsafe.exitCode();
            default -> 1;
        };
    }

    /// A start failure is fatal: the process exits with [#exitCode] (systemd restarts it per the unit's policy).
    @TerminalOperation
    private static Unit failed(Promise<Unit> stopped, Cause cause) {
        LOG.error("kiln failed to start: {}", cause.message());
        stopped.succeed(unit());
        Runtime.getRuntime().exit(exitCode(cause));

        return unit();
    }

    record unused() implements Main {}
}
