#!/usr/bin/env bash
# T2 crashed fork on the controller host (m1-design §8.2): org.pragmatica.temper.acceptance.trust.T2Harness.
# Arguments: README.md "T2". The jar is temper-acceptance's shaded jar (mvn install -Pacceptance from the root
# builds it); override it with TEMPER_ACCEPTANCE_JAR and the java with JAVA (Java 25+).
#
# The CLI the harness submits through is the one built in this same checkout, never whatever `temper` is on PATH
# (M1 verification F2: a CLI older than the controller cannot read an UNKNOWN job): unless --temper is given, it is
# "java -jar <checkout>/temper-cli/target/temper-cli-<ver>.jar" (override the jar with TEMPER_CLI_JAR; its path must
# hold no space). Build the CLI and the controller from one commit (the deploy runbook).
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
jar=${TEMPER_ACCEPTANCE_JAR:-$here/target/temper-acceptance-0.1.0-SNAPSHOT-all.jar}
cli=${TEMPER_CLI_JAR:-$(cd "$here/.." && pwd)/temper-cli/target/temper-cli-0.1.0-SNAPSHOT.jar}
[ -r "$jar" ] || { echo "t2.sh: no jar at $jar (build it with: mvn install -Pacceptance)" >&2; exit 64; }
temper=()
case " $* " in
    *" --temper "*) ;;
    *) [ -r "$cli" ] || { echo "t2.sh: no CLI jar at $cli (build it with: mvn install, or pass --temper)" >&2; exit 64; }
       temper=(--temper "${JAVA:-java} -jar $cli") ;;
esac
echo "t2.sh: CLI: ${temper[1]:-the given --temper}" >&2
exec "${JAVA:-java}" -cp "$jar" org.pragmatica.temper.acceptance.trust.T2Harness ${temper[@]+"${temper[@]}"} "$@"
