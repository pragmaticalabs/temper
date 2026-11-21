#!/usr/bin/env bash
# install-bb-temper-local.sh <new-file> <dest> <expected-md5> -- replace a LOCAL tool file (the Mac's ~/temper-wt/bb-temper) ATOMICALLY.
# Why a rename (CTO 2026-11-21): bash reads a running script incrementally, so writing onto the live path (cp, >, cat >) changes the
# bytes under every bb-temper invocation in flight, and a lane mid-run executes a splice of two versions. A rename leaves every running
# process on the old inode: <new-file> is copied to a temp file in <dest>'s own directory (same filesystem, so mv is a rename(2)),
# chmod 0755, md5-checked, then `mv -f` over <dest>. The old file is kept as <dest>.bak-<its md5> (bb-drift skips <known>.bak*).
# Refused, <dest> untouched: <new-file>'s md5 is not <expected-md5> (exit 9); usage (exit 2). <dest> already at <expected-md5>: "unchanged", exit 0.
# Output: `installed <dest> <old md5|absent> -> <new md5>` (bak: <path>) or `unchanged <dest> <md5>`.
set -euo pipefail
[ $# -eq 3 ] || { echo "usage: install-bb-temper-local.sh <new-file> <dest> <expected-md5>" >&2; exit 2; }
new=$1 dest=$2 want=$3
sum() { if command -v md5sum > /dev/null 2>&1; then md5sum < "$1" | cut -d' ' -f1; else md5 -q "$1"; fi; }
got=$(sum "$new")
[ "$got" = "$want" ] || { echo "refused: $new md5 $got is not the expected $want; $dest untouched" >&2; exit 9; }
old=absent; [ ! -e "$dest" ] || old=$(sum "$dest")
[ "$old" != "$want" ] || { echo "unchanged $dest $want"; exit 0; }
bak=
if [ "$old" != absent ]; then bak=$dest.bak-$old; [ -e "$bak" ] || cp -p "$dest" "$bak"; fi   # a .bak of that name holds the same bytes
tmp=$(mktemp "$(dirname "$dest")/.$(basename "$dest").XXXXXX")
trap 'rm -f "$tmp"' EXIT
cat "$new" > "$tmp"; chmod 0755 "$tmp"
[ "$(sum "$tmp")" = "$want" ] || { echo "refused: the temp copy $tmp does not hold $want; $dest untouched" >&2; exit 9; }
mv -f "$tmp" "$dest"; trap - EXIT
echo "installed $dest $old -> $want${bak:+ (bak: $bak)}"
