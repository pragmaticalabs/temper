# temper runner image (spec §4.6, m0-design §7.6). Built by the worker on first use:
#   docker build -t temper/runner:<ver> --label temper.runner=<ver> ~/.temper/runner/<ver>
#
# Base: eclipse-temurin:25-noble pinned by its multi-arch OCI index digest (linux/amd64 + linux/arm64 + ...).
# Digest read 2026-10-13 from registry-1.docker.io (docker-content-digest of the 25-noble index).
FROM eclipse-temurin:25-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4

# Tools that projects' TESTS exec, absent from the base (T1 2026-10-14: pragmatica's script-gate tests failed with
# `Cannot run program "python3"`). Audit of pragmatica 0cf563192a / ea364e8952 (default `clean install`):
#   git (>= 2.28, `--initial-branch`; includes git-remote-http): GitBackupRepository, GitFixtures, changelog-check.sh
#   python3 (stdlib only): ForgeFreshnessGateTest, forge.sh via ForgeGateRefusalTest
# Audit of pragmatica release-1.0.0-rc5 a1161a6 (2026-10-20; temper Gate 58 failed `jq: command not found`):
#   jq: tools/pg-firewall.sh destroy (PgFirewallDestroyTest), tools/provision-test-pg.sh --destroy (ProvisionTestPgDestroyTest)
#   openssh-client (ssh, ssh-keygen): provision-test-pg.sh's preflight dependency check; both tests stub the cloud API
#   curl: the same scripts (stubbed first on PATH by the tests); installed for parity with GitHub's ubuntu runners
# Everything else those tests and scripts exec (bash, sh, tar, sed, mktemp, pgrep, ...) is already in the base.
# Reproducibility: these come from the noble archive at build time and are NOT version-pinned (pinning breaks
# whenever Ubuntu supersedes a version), so two builds of this file can differ in the packages' patch level. The
# worker labels the image with the hash of this build context, so a changed Dockerfile is rebuilt on the next unit.
RUN apt-get update \
 && apt-get install -y --no-install-recommends git python3 jq openssh-client curl \
 && rm -rf /var/lib/apt/lists/*

# Maven 3.9.12, verified by its published sha512. The constant is committed, never fetched at build time.
# Source: https://archive.apache.org/dist/maven/maven-3/3.9.12/binaries/apache-maven-3.9.12-bin.tar.gz.sha512,
# cross-checked 2026-10-13 against repo1.maven.org (org/apache/maven/apache-maven/3.9.12) and a locally computed
# sha512 of the downloaded archive (all three equal). downloads.apache.org no longer serves 3.9.12 (302 to archive).
ARG MAVEN_VERSION=3.9.12
ARG MAVEN_SHA512=0a1be79f02466533fc1a80abbef8796e4f737c46c6574ede5658b110899942a94db634477dfd3745501c80aef9aac0d4f841d38574373f7e2d24cce89d694f70

# curl comes from the apt step above (the base image has neither curl nor wget). The archive is fetched at build time
# from Maven Central first, then archive.apache.org, each with retries: a single un-retried fetch from archive.apache.org
# failed on GitHub's runners with `TLS handshake timeout` / `connection reset by peer` / `i/o timeout` (CI 2026-11-05,
# WorkerLaunchLinuxDockerIT and SeedHostLinuxDockerIT). Both serve the same bytes; the sha512 check follows the fetch
# in the same RUN and fails the build on a mismatch, an empty/short constant, or a missing file.
RUN test "${#MAVEN_SHA512}" -eq 128 \
 && for url in \
      https://repo1.maven.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
      https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz; \
    do curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 --connect-timeout 20 --max-time 300 -o /tmp/maven.tgz "$url" && break; done \
 && echo "${MAVEN_SHA512}  /tmp/maven.tgz" | sha512sum -c - \
 && tar -xzf /tmp/maven.tgz -C /opt \
 && ln -s /opt/apache-maven-${MAVEN_VERSION} /opt/maven \
 && rm /tmp/maven.tgz

# Must equal the PATH the worker passes with -e PATH=... (DockerRunArgs.IMAGE_PATH).
ENV PATH=/opt/maven/bin:/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# The step launcher: no shell between the worker and mvn (R-SHELL); it re-applies the R-ENV allow-list because
# docker cannot unset the image's own ENV (LANG, JAVA_VERSION, ...).
COPY RunSteps.java /opt/temper/RunSteps.java
RUN javac -d /opt/temper /opt/temper/RunSteps.java \
 && chmod -R a+rX /opt/temper
