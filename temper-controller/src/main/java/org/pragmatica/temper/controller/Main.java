package org.pragmatica.temper.controller;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.controller.config.ConfigLoader;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.config.ConfigError;
import org.pragmatica.temper.controller.config.ControllerVersion;
import org.pragmatica.temper.controller.config.GitHubIdentity;
import org.pragmatica.temper.controller.config.Secrets;
import org.pragmatica.temper.controller.git.GitCli;
import org.pragmatica.temper.controller.git.GitHubPulls;
import org.pragmatica.temper.controller.git.GitHubRepo;
import org.pragmatica.temper.controller.review.HttpReviewGitHub;
import org.pragmatica.temper.controller.host.LinkTiming;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.store.api.DbConfig;
import org.pragmatica.temper.store.api.Store;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.pg.Postgres;
import org.pragmatica.temper.ghapp.AppAuthConfig;
import org.pragmatica.temper.ghapp.AppAuthError;
import org.pragmatica.temper.ghapp.GitHubAppAuth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// `java -jar temper-controller-<ver>.jar [path/to/controller.toml]` (default `~/.config/temper/controller.toml`).
///
/// Start: parse the config, read the token, the read and worker tokens if configured, the database password and the build's
/// judge ([ControllerVersion#judge]), connect the store (migrations run), upsert the hosts, start the HTTP server, the
/// host links and the scheduler. Any start failure is fatal (exit 1; 78 for a missing GitHub identity or an unsafe App key). SIGTERM stops the links (workers shut down; unit containers keep running and are
/// re-attached at the next start) and the server.
public sealed interface Main {
    Logger LOG = LoggerFactory.getLogger(Main.class);

    /// The JVM entry point: its `void` signature is dictated by the JVM, and it blocks until the controller stops.
    @Contract
    @TerminalOperation
    static void main(String[] args) {
        var home = Path.of(System.getProperty("user.home"));
        var stopped = Promise.<Unit> promise();
        // May run late: these callbacks are what resolve `stopped`, which is all `main` waits for.
        start(configFile(args, home),
              home).onSuccess(running -> stopOnShutdown(running, stopped))
             .onFailure(cause -> failed(stopped, cause));
        stopped.await();
    }

    static Promise<Controller.Running> start(Path file, Path home) {
        return settings(file, home).async()
                       .flatMap(Main::launch)
                       .onSuccess(running -> LOG.info("temper controller listening on :{}",
                                                      running.port()));
    }

    /// The parsed config with the secrets it points at and the build's judge; `toString` masks the tokens.
    record Settings(ControllerConfig config,
                    String token,
                    Option<String> readToken,
                    Option<String> workerToken,
                    Option<String> verdictToken,
                    Option<String> macToken,
                    DbConfig db,
                    Judge judge) {
        static Settings settings(ControllerConfig config,
                                 String token,
                                 Option<String> readToken,
                                 Option<String> workerToken,
                                 Option<String> verdictToken,
                                 Option<String> macToken,
                                 DbConfig db,
                                 Judge judge) {
            return new Settings(config, token, readToken, workerToken, verdictToken, macToken, db, judge);
        }

        /// Never prints a token (the database config masks its own password).
        @Override
        public String toString() {
            return "Settings[config=" + config
                 + ", token=***, readToken=" + masked(readToken)
                 + ", workerToken=" + masked(workerToken)
                 + ", verdictToken=" + masked(verdictToken)
                 + ", macToken=" + masked(macToken)
                 + ", db=" + db
                 + ", judge=" + judge
                 + "]";
        }

        private static String masked(Option<String> token) {
            return token.isPresent()
                   ? "***"
                   : "none";
        }
    }

    private static Path configFile(String[] args, Path home) {
        return args.length > 0
               ? Path.of(args[0])
               : home.resolve(".config/temper/controller.toml");
    }

    /// Not private (an interface has no package-private members): `MainWiringTest` runs this start path from real
    /// files, so a read token that never reaches [Credentials] fails a test.
    static Result<Settings> settings(Path file, Path home) {
        return ConfigLoader.load(file, home).flatMap(Main::withSecrets);
    }

    private static Result<Settings> withSecrets(ControllerConfig config) {
        return Result.all(Secrets.read(config.server().tokenFile()),
                          Secrets.dbConfig(config.db()),
                          ControllerVersion.judge())
                     .flatMap((token, db, judge) -> withReadToken(config, token, db, judge));
    }

    private static Result<Settings> withReadToken(ControllerConfig config, String token, DbConfig db, Judge judge) {
        return Secrets.readToken(config.server(),
                                 token)
                      .flatMap(readToken -> withWorkerToken(config, token, readToken, db, judge));
    }

    private static Result<Settings> withWorkerToken(ControllerConfig config,
                                                    String token,
                                                    Option<String> readToken,
                                                    DbConfig db,
                                                    Judge judge) {
        return Secrets.workerToken(config.server(),
                                   token,
                                   readToken)
                      .flatMap(workerToken -> withT2Tokens(config, token, readToken, workerToken, db, judge));
    }

    /// The verdict and Mac tokens, each distinct from every token loaded before it.
    private static Result<Settings> withT2Tokens(ControllerConfig config,
                                                 String token,
                                                 Option<String> readToken,
                                                 Option<String> workerToken,
                                                 DbConfig db,
                                                 Judge judge) {
        return Secrets.verdictToken(config.server(),
                                    token,
                                    readToken,
                                    workerToken)
                      .flatMap(verdictToken -> Secrets.macToken(config.server(),
                                                                token,
                                                                readToken,
                                                                workerToken,
                                                                verdictToken)
                                                      .map(macToken -> Settings.settings(config,
                                                                                         token,
                                                                                         readToken,
                                                                                         workerToken,
                                                                                         verdictToken,
                                                                                         macToken,
                                                                                         db,
                                                                                         judge)));
    }

    /// The credentials and the GitHub judge (its App key checked) come before the store is touched, so a start refused for
    /// them has connected to nothing.
    private static Promise<Controller.Running> launch(Settings settings) {
        var runner = ProcessRunner.processRunner();

        return credentials(settings).async()
                          .flatMap(credentials -> git(runner,
                                                      settings.config()).async()
                                                     .flatMap(git -> started(settings, credentials, runner, git)));
    }

    /// Not private, for `MainWiringTest` (see [#settings]).
    static Result<Credentials> credentials(Settings settings) {
        return Result.all(BearerToken.bearerToken(settings.token()),
                          Values.optional(settings.readToken(),
                                          BearerToken::bearerToken),
                          Values.optional(settings.workerToken(),
                                          BearerToken::bearerToken),
                          Values.optional(settings.verdictToken(),
                                          BearerToken::bearerToken),
                          Values.optional(settings.macToken(),
                                          BearerToken::bearerToken))
                     .map(Credentials::credentials);
    }

    private static Promise<Controller.Running> started(Settings settings,
                                                       Credentials credentials,
                                                       ProcessRunner runner,
                                                       Controller.Git git) {
        return Postgres.connect(settings.db(),
                                settings.judge())
                       .onSuccess(_ -> LOG.info("temper controller judge {}",
                                                settings.judge()))
                       .flatMap(store -> controller(settings, store, credentials, runner, git).start());
    }

    private static Controller controller(Settings settings,
                                         Store store,
                                         Credentials credentials,
                                         ProcessRunner runner,
                                         Controller.Git git) {
        return Controller.controller(settings.config(),
                                     store,
                                     credentials,
                                     runner,
                                     git,
                                     LinkTiming.DEFAULT,
                                     Controller.POLL_WAIT,
                                     Instant::now);
    }

    /// The submit's git adapters and the production pull-request judge (R-M2-7), which also reads a PR's merged flag
    /// (m3-design §10.5). The judge reads as the controller App when `[github.app]` is configured (the key is checked
    /// here, so a bad key refuses the start), and unauthenticated only for `[github] anonymous = true`. Not private, for
    /// `MainWiringTest`.
    static Result<Controller.Git> git(ProcessRunner runner, ControllerConfig config) {
        return git(runner, config, GitHubPulls.API);
    }

    /// As [#git], against `api` (a stub in tests). One App auth serves the judge and the reviewer's reads.
    static Result<Controller.Git> git(ProcessRunner runner, ControllerConfig config, URI api) {
        var repo = config.repo();
        var git = GitCli.gitCli(runner, repo.origin(), repo.mirror());

        return readAuth(config.github(), repo.origin(), api).map(auth -> wired(git, auth, config, api));
    }

    private static Controller.Git wired(GitCli git, Option<GitHubAppAuth> auth, ControllerConfig config, URI api) {
        var pulls = pulls(auth,
                          config.repo().origin(),
                          api);

        return Controller.Git.git(git, git, git, pulls, pulls).withReviews(config.reviewer()
                                                                                 .map(reviewer -> HttpReviewGitHub.httpReviewGitHub(api,
                                                                                                                                    auth,
                                                                                                                                    reviewer)));
    }

    private static GitHubPulls pulls(Option<GitHubAppAuth> auth, String origin, URI api) {
        return auth.map(source -> GitHubPulls.gitHubPulls(api, origin, GitHubPulls.TIMEOUT, source))
                   .or(() -> GitHubPulls.gitHubPulls(api, origin));
    }

    /// The controller App's auth (the key is checked here, so a bad key refuses the start), or none for `[github]
    /// anonymous = true`.
    private static Result<Option<GitHubAppAuth>> readAuth(GitHubIdentity identity, String origin, URI api) {
        return switch (identity) {
            case GitHubIdentity.App app -> appAuthConfig(app, origin, api).flatMap(config -> GitHubAppAuth.githubAppAuth(config,
                                                                                                                         GitHubAppAuth.httpClient(),
                                                                                                                         Clock.systemUTC())).map(Option::some);
            case GitHubIdentity.Anonymous _ -> anonymous();
        };
    }

    private static Result<Option<GitHubAppAuth>> anonymous() {
        LOG.warn("temper controller reads GitHub anonymously ([github] anonymous = true): 60 requests/h per address");

        return Result.success(Option.none());
    }

    /// Token narrowed to the one repository and to `pull_requests: read` (C.5): every read of the judge and
    /// of the review path uses it. The dismissal's write mint is WP-4b's, a config of its own.
    static Result<AppAuthConfig> appAuthConfig(GitHubIdentity.App app, String origin) {
        return appAuthConfig(app, origin, GitHubPulls.API);
    }

    static Result<AppAuthConfig> appAuthConfig(GitHubIdentity.App app, String origin, URI api) {
        return GitHubRepo.gitHubRepo(origin).flatMap(repo -> AppAuthConfig.appAuthConfig("controller-app",
                                                                                         app.clientId(),
                                                                                         app.installationId(),
                                                                                         app.keyFile(),
                                                                                         List.of(repo.name()),
                                                                                         Map.of("pull_requests", "read"),
                                                                                         api));
    }

    /// 78 (EX_CONFIG) for a start refused over GitHub identity or its key file or over the database's idle_session_timeout, 1 for every other start failure. The
    /// config loader reports several failures at once as one composite, so every cause in it is looked at.
    static int exitCode(Cause cause) {
        return cause.stream()
                    .mapToInt(Main::ownExitCode)
                    .max()
                    .orElse(1);
    }

    private static int ownExitCode(Cause cause) {
        return switch (cause) {
            case ConfigError.General general when general == ConfigError.General.NO_GITHUB_IDENTITY -> AppAuthError.EXIT_CONFIG;
            case StoreError.IdleSessionTimeout _ -> AppAuthError.EXIT_CONFIG;
            case AppAuthError.KeyFileTooOpen open -> open.exitCode();
            case AppAuthError.KeyFileUnsafe unsafe -> unsafe.exitCode();
            default -> 1;
        };
    }

    private static Unit stopOnShutdown(Controller.Running running, Promise<Unit> stopped) {
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> stop(running, stopped)));

        return unit();
    }

    /// Runs on the JVM's shutdown-hook thread, which must not return before the links and the server are stopped.
    @TerminalOperation
    private static Unit stop(Controller.Running running, Promise<Unit> stopped) {
        LOG.info("temper controller stopping");
        running.stop()
               .await(Controller.POLL_WAIT)
               .onFailure(cause -> LOG.warn("temper controller did not stop cleanly: {}",
                                            cause.message()));
        stopped.succeed(unit());

        return unit();
    }

    /// A start failure is fatal: the process exits 1, or 78 per [#exitCode] (systemd restarts it per the unit's policy).
    private static Unit failed(Promise<Unit> stopped, Cause cause) {
        LOG.error("temper controller failed to start: {}", cause.message());
        stopped.fail(cause);
        System.exit(exitCode(cause));

        return unit();
    }

    record unused() implements Main {}
}
