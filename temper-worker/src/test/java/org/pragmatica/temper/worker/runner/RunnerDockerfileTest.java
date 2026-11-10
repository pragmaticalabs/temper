package org.pragmatica.temper.worker.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// The shipped runner `Dockerfile` (spec §4.6, m0-design §7.6) builds from pinned inputs only: every base image is
/// pinned by digest, and the Maven archive is checked against a committed sha512 before anything reads it.
/// Checked semantically (instructions, their order, the shell steps of one `RUN`), not against a golden copy.
class RunnerDockerfileTest {
    record Instruction(String keyword, String arguments) {}

    private static final Pattern DIGEST_PINNED = Pattern.compile("\\S+@sha256:[0-9a-f]{64}");

    // sha512sum ends the pipeline (no pipefail in /bin/sh: a command after it would mask a mismatch).
    private static final Pattern SHA512_CHECK = Pattern.compile("\\|\\s*sha512sum\\s+(-c|--check)(\\s+-)?$");
    private static final Pattern SHA512_ARG = Pattern.compile("MAVEN_SHA512=([0-9a-f]*)");

    @Test
    void everyBaseImage_isPinnedByDigest() throws IOException {
        var bases = instructions().stream()
                                .filter(instruction -> instruction.keyword()
                                                                  .equals("FROM"))
                                .map(instruction -> words(instruction.arguments()).stream()
                                                         .filter(word -> !word.startsWith("--"))
                                                         .findFirst()
                                                         .orElse(""))
                                .toList();

        assertThat(bases).isNotEmpty()
                  .allMatch(base -> DIGEST_PINNED.matcher(base).matches(),
                            "pinned by @sha256:<digest>");
    }

    @Test
    void theMavenArchive_isVerifiedBySha512_beforeAnythingUsesIt() throws IOException {
        var instructions = instructions();
        var download = instructions.get(downloadIndex(instructions));
        // The steps of that RUN joined by `&&`: fetch, then the sha512 check, and only then anything else touching
        // the archive (`tar`), so a mismatch stops the RUN before the file is read.
        var steps = Arrays.stream(download.arguments().split("&&")).map(String::strip).toList();
        var fetch = indexOf(steps, step -> step.contains("curl"));
        var check = indexOf(steps, step -> step.contains("sha512sum"));
        var use = indexOf(steps, step -> step.startsWith("tar"));

        assertThat(download.keyword()).isEqualTo("RUN");
        assertThat(fetch).isLessThan(check);
        assertThat(check).isLessThan(use);
        assertThat(steps.get(check)).matches(step -> SHA512_CHECK.matcher(step).find(),
                                             "a sha512sum check ending its pipeline")
                  .contains("${MAVEN_SHA512}")
                  .doesNotContain(";", "||");
        var expected = instructions.stream()
                                   .filter(instruction -> instruction.keyword()
                                                                     .equals("ARG"))
                                   .map(instruction -> SHA512_ARG.matcher(instruction.arguments()))
                                   .filter(matcher -> matcher.find())
                                   .map(matcher -> matcher.group(1))
                                   .toList();

        assertThat(expected).singleElement().satisfies(sha -> assertThat(sha).hasSize(128));
    }

    @Test
    void theMavenFetch_retriesAndFallsBackToASecondHost() throws IOException {
        var instructions = instructions();
        var fetch = instructions.get(downloadIndex(instructions)).arguments();

        assertThat(fetch).contains("--retry ", "--retry-all-errors", "--connect-timeout", "--max-time", "-f");
        assertThat(Pattern.compile("https://[^/\\s]+/").matcher(fetch).results().map(r -> r.group()).distinct().count()).as("distinct hosts to fetch the archive from")
                  .isGreaterThanOrEqualTo(2);
    }

    private static int indexOf(List<String> steps, java.util.function.Predicate<String> match) {
        for (var index = 0; index < steps.size(); index++) {
            if (match.test(steps.get(index))) {
                return index;
            }
        }

        throw new AssertionError("no matching step in " + steps);
    }

    /// The single RUN that fetches the Maven binary archive.
    private static int downloadIndex(List<Instruction> instructions) {
        var downloads = new ArrayList<Integer>();

        for (var index = 0; index < instructions.size(); index++) {
            var instruction = instructions.get(index);

            if (instruction.keyword().equals("RUN") && instruction.arguments().contains("curl") && instruction.arguments()
                                                                                                              .contains("apache-maven-")) {
                downloads.add(index);
            }
        }

        assertThat(downloads).as("one RUN fetching the Maven archive").hasSize(1);

        return downloads.getFirst();
    }

    private static List<String> words(String arguments) {
        return Arrays.stream(arguments.trim().split("\\s+")).toList();
    }

    /// Logical instructions: comments dropped, `\` continuations joined, keyword upper-cased.
    private static List<Instruction> instructions() throws IOException {
        String text;

        try (var in = RunnerDockerfileTest.class.getResourceAsStream("/runner/Dockerfile")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        var logical = new ArrayList<String>();
        var current = new StringBuilder();

        for (var line : text.split("\n")) {
            var trimmed = line.strip();

            if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.endsWith("\\")) {
                current.append(trimmed, 0, trimmed.length() - 1).append(' ');
                continue;
            }

            logical.add(current.append(trimmed).toString());
            current.setLength(0);
        }

        return logical.stream()
                      .map(line -> line.split("\\s+", 2))
                      .map(parts -> new Instruction(parts[0].toUpperCase(),
                                                    parts.length > 1
                                                    ? parts[1]
                                                    : ""))
                      .toList();
    }
}
