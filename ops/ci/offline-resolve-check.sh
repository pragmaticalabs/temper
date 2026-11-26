#!/usr/bin/env bash
# CI guard (#123): the build must resolve every dependency without reading remote metadata. A version RANGE in any pom of the
# dependency graph ("[1.80,1.81)") is resolved from maven-metadata-*.xml, so the build needs a Central fetch or the cached copy,
# and fails with "No versions available" when either is missing although the jar is cached. A managed version (root pom
# dependencyManagement) replaces a range before it is resolved.
#
# The check: copy the local repository, delete every remote maven-metadata-*.xml (the -local ones, which describe our own installed
# artifacts, stay) and every resolver-status.properties from the COPY, then build the whole reactor offline against it. A range-only
# dependency makes that build fail; a pinned one does not. The copy keeps the real .m2-local (and the CI cache) untouched.
# Run after `mvn install`, so the jars and poms are in the repository.
#
# What it does NOT catch: a range that only a goal this run does not execute would resolve (surefire/failsafe providers, packaging
# and deploy plugins' own dependencies; only test-compile runs), and a profile other than acceptance.
# Usage: ops/ci/offline-resolve-check.sh [repo=.m2-local]   (MVN overrides the mvn command)
set -euo pipefail

repo="${1:-.m2-local}"
mvn_cmd="${MVN:-mvn}"
[ -d "$repo" ] || { echo "::error::local repository $repo does not exist: nothing to check" >&2; exit 2; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cp -a "$repo" "$tmp/repo"
removed=$(find "$tmp/repo" -type f \( -name 'maven-metadata-*.xml*' ! -name 'maven-metadata-local.xml*' -o -name resolver-status.properties \) -print -delete | wc -l)
echo "offline-resolve-check: removed $removed remote-metadata files from the copy of $repo"

if ! "$mvn_cmd" -B -ntp -o -Dmaven.repo.local="$tmp/repo" -Pacceptance test-compile; then
    echo "::error::the reactor does not build offline without remote metadata: a dependency is resolved through a version range (grep the log for 'No versions available'); pin it in the root pom's dependencyManagement (#123)" >&2
    exit 1
fi
echo "offline-resolve-check: ok"
