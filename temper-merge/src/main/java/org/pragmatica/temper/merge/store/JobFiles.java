package org.pragmatica.temper.merge.store;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Functions.ThrowingRunnable;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.merge.engine.Job;


/// The job store: one JSON file per job, `<dir>/<id>.json`. A file store, because the runner has one writer per
/// job (the serve loop; `submit` only creates), a few dozen jobs at most, no query but "every job not terminal", and
/// its operator reads it with `cat` over ssh; a database would couple a small service to Postgres for none of that.
///
/// Durability: [#create] writes a new file and fails if the id exists (`CREATE_NEW`); [#save] writes `<id>.json.tmp`,
/// forces it to disk, then renames it over `<id>.json` (`ATOMIC_MOVE`), so a reader or a restart sees the old job or
/// the new one, never a torn file. A leftover `.tmp` from a crash is ignored.
public record JobFiles(Path dir, JsonMapper json) {
    static final String SUFFIX = ".json";
    static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    public static JobFiles jobFiles(Path dir) {
        return new JobFiles(dir,
                            JsonMapper.jsonMapper().withPragmaticaTypes().build());
    }

    public Result<Unit> create(Job job) {
        return checkedId(job.id()).flatMap(_ -> json.writeAsString(job))
                        .flatMap(text -> created(job.id(),
                                                 text));
    }

    public Result<Unit> save(Job job) {
        return checkedId(job.id()).flatMap(_ -> json.writeAsString(job))
                        .flatMap(text -> replaced(job.id(),
                                                  text));
    }

    /// The job, or none when there is no such file.
    public Result<Option<Job>> load(String id) {
        return checkedId(id).flatMap(_ -> Files.exists(file(id))
                                          ? read(file(id)).map(Option::some)
                                          : Result.success(Option.none()));
    }

    /// Every stored job, oldest submit first.
    public Result<List<Job>> all() {
        return files().flatMap(paths -> Result.allOf(paths.stream().map(this::read).toList()))
                    .map(jobs -> jobs.stream()
                                     .sorted(Comparator.comparing(Job::submittedAt))
                                     .toList());
    }

    private Result<String> checkedId(String id) {
        return ID.matcher(id).matches()
               ? Result.success(id)
               : Causes.cause("not a job id: " + id).result();
    }

    private Path file(String id) {
        return dir.resolve(id + SUFFIX);
    }

    private Result<List<Path>> files() {
        return lifted("cannot create " + dir, () -> Files.createDirectories(dir)).flatMap(_ -> Result.lift(thrown -> Causes.cause("cannot list " + dir
                                                                                                                                 + ": " + thrown),
                                                                                                           () -> {
                                                                                                               try (Stream<Path> listed = Files.list(dir)) {
                                                                                                               return listed.filter(path -> path.getFileName()
                                                                                                                                                .toString()
                                                                                                                                                .endsWith(SUFFIX))
                                                                                                                            .sorted()
                                                                                                                            .toList();
                                                                                                           }
                                                                                                           }));
    }

    private Result<Job> read(Path path) {
        return Result.lift(thrown -> Causes.cause("cannot read " + path + ": " + thrown),
                           () -> Files.readString(path))
                     .flatMap(text -> json.readString(text, Job.class));
    }

    private Result<Unit> created(String id, String text) {
        var why = "cannot create job " + id;

        return lifted(why,
                      () -> Files.createDirectories(dir)).flatMap(_ -> lifted(why,
                                                                              () -> Files.writeString(file(id),
                                                                                                      text,
                                                                                                      StandardCharsets.UTF_8,
                                                                                                      StandardOpenOption.CREATE_NEW,
                                                                                                      StandardOpenOption.WRITE)))
                     .flatMap(_ -> forced(why,
                                          file(id)));
    }

    private Result<Unit> replaced(String id, String text) {
        var why = "cannot save job " + id;
        var tmp = dir.resolve(id + SUFFIX + ".tmp");

        return lifted(why,
                      () -> Files.createDirectories(dir)).flatMap(_ -> lifted(why,
                                                                              () -> Files.writeString(tmp,
                                                                                                      text,
                                                                                                      StandardCharsets.UTF_8)))
                     .flatMap(_ -> forced(why, tmp))
                     .flatMap(_ -> lifted(why,
                                          () -> Files.move(tmp,
                                                           file(id),
                                                           StandardCopyOption.ATOMIC_MOVE,
                                                           StandardCopyOption.REPLACE_EXISTING)));
    }

    private static Result<Unit> forced(String why, Path path) {
        return lifted(why,
                      () -> {
                          try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                          channel.force(true);
                      }
                      });
    }

    private static Result<Unit> lifted(String why, ThrowingRunnable action) {
        return Result.lift(thrown -> Causes.cause(why + ": " + thrown), action);
    }
}
