package org.pragmatica.temper.worker.unit;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// Parses a JVM listing in `jcmd -l` shape (`<pid> <command> [args]` per line; `RunSteps --jvms` prints the same
/// shape) into the pids to dump: every JVM except a jcmd (`sun.tools.jcmd.JCmd`, also in module form
/// `jdk.jcmd/sun.tools.jcmd.JCmd`, or as the main class after a `java` executable).
public sealed interface JcmdList {
    String JCMD_MAIN = "sun.tools.jcmd.JCmd";
    Pattern LINE = Pattern.compile("^([0-9]{1,10})(?:\\s+(.*))?$");

    static List<Long> pids(String jcmdOutput) {
        return jcmdOutput.lines()
                         .map(String::trim)
                         .map(JcmdList::pid)
                         .flatMap(Option::stream)
                         .toList();
    }

    private static Option<Long> pid(String line) {
        var matcher = LINE.matcher(line);

        return matcher.matches() && !isJcmd(option(matcher.group(2)).or(""))
               ? Number.parseLong(matcher.group(1)).option()
               : none();
    }

    private static boolean isJcmd(String commandLine) {
        return Arrays.stream(commandLine.split("\\s+")).anyMatch(word -> word.equals(JCMD_MAIN) || word.endsWith("/" + JCMD_MAIN));
    }

    record unused() implements JcmdList {}
}
