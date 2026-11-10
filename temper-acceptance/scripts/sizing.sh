#!/usr/bin/env bash
# The §12.1 sizing report on the controller host (m1-design §8.3): org.pragmatica.temper.acceptance.sizing.SizingReport.
# Arguments: README.md "Sizing". The jar is temper-acceptance's shaded jar (mvn install -Pacceptance from the root
# builds it); override it with TEMPER_ACCEPTANCE_JAR and the java with JAVA (Java 25+).
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
jar=${TEMPER_ACCEPTANCE_JAR:-$here/target/temper-acceptance-0.1.0-SNAPSHOT-all.jar}
[ -r "$jar" ] || { echo "sizing.sh: no jar at $jar (build it with: mvn install -Pacceptance)" >&2; exit 64; }
exec "${JAVA:-java}" -cp "$jar" org.pragmatica.temper.acceptance.sizing.SizingReport "$@"
