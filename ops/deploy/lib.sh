# shellcheck shell=bash
# lib.sh -- the shared library of the per-unit deploy scripts.
# Sourced, never executed: `. "$(cd "$(dirname "$0")" && pwd)/lib.sh"` from deploy-<unit>.sh in the same directory,
# after `set -euo pipefail; umask 022`. bash 3.2 compatible (the Mac's /bin/bash): no associative arrays, mapfile,
# case-changing expansions, namerefs, coproc, `|&`, `&>>` or `[[ -v`.
#
# The unit script defines DL_UNIT DL_OS DL_HOST DL_BUILD_ARGS DL_SERVICE and its unit_* hooks, then `dl_main "$@"`.
# Verbs: stage, stage-local, install, live.
#
# How install runs (contract D3.7 step 6): the plan (D3.10) is built once, as text, from the staged dir and the live
# state; `--dry-run` prints it; a real install executes the plan's own lines, one by one, and never an action the plan
# does not hold. The three imperative hooks of the contract table (unit_before_swap, unit_after_swap,
# unit_after_health) are therefore plan-printing hooks here: unit_plan_prepare, unit_plan_restart <which>,
# unit_plan_after <which> print `<action> <field>...` lines (the action vocabulary of D3.10), and unit_probes <which>
# prints `<id> <rule>` lines for the plan's probe actions.

: "${DL_RC_USAGE:=2}"
: "${DL_RC_REFUSED:=3}"
: "${DL_RC_BUILD:=4}"
: "${DL_RC_ROLLED_BACK:=5}"
: "${DL_RC_ROLLBACK_FAILED:=6}"
: "${DL_RC_FORWARD_ONLY:=7}"
: "${DL_RC_PARTIAL:=8}"
: "${DL_RC_UNREACHABLE:=9}"

: "${DL_SYSTEMCTL:=systemctl}"
: "${DL_LAUNCHCTL:=launchctl}"
: "${DL_DOCKER:=docker}"
: "${DL_CURL:=curl}"
: "${DL_JAVA:=java}"
: "${DL_GIT:=git}"
: "${DL_HEALTH_INTERVAL:=2}"
: "${DL_FETCH_SLEEP:=5}"
: "${DL_BUILD_HOST:=bigboy}"

DL_TMP=
DL_LOCK_HELD=0
DL_PUMPS=
DL_BG=
DL_RECORDED=0
DL_REC_OUTCOME=
DL_PHASE=
DL_PID_BEFORE=0
DL_PASS_PID=
DL_PASS_NR=
DL_FAILED_AT=
DL_B=
DL_SCHEMA_BEFORE=
DL_SCHEMA_AFTER=
DL_DUMP_CONTAINER=
DL_ROUNDS_DONE=0
DL_PAST_HEALTH=0
DL_FAIL_PHASE=
DL_FAIL_ACT=
DL_HT=0
DL_HI=0
DL_SET_NEW=
DL_SET_OLD=

# ---------------------------------------------------------------------------------------------------------- basics

dl_die() {  # dl_die <rc> <message>
  local rc=$1
  shift
  printf '%s\n' "$*" >&2
  exit "$rc"
}

dl_now() { printf '%s\n' "${DL_NOW:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"; }

dl_stamp() { dl_now | tr -d ':-'; }

dl_has() { [ "$(type -t "$1" 2> /dev/null)" = function ]; }

dl_sha256() {  # dl_sha256 <file>
  if command -v sha256sum > /dev/null 2>&1; then
    sha256sum < "$1" | cut -d' ' -f1
  else
    shasum -a 256 < "$1" | cut -d' ' -f1
  fi
}

dl_md5() {  # dl_md5 <file>
  if command -v md5sum > /dev/null 2>&1; then
    md5sum < "$1" | cut -d' ' -f1
  else
    md5 -q "$1"
  fi
}

dl_jar_main() {  # the Main-Class of a jar's manifest, empty when it has none
  unzip -p "$1" META-INF/MANIFEST.MF 2> /dev/null | tr -d '\r' | sed -n 's/^Main-Class: *//p' | head -n 1 || true
}

dl_jar_prop() {  # dl_jar_prop <jar> <resource> <key>: the trimmed value of `key=` in the resource, empty when none
  { unzip -p "$1" "$2" 2> /dev/null | tr -d '\r' | sed -n "s/^[[:space:]]*$3=//p" | head -n 1 \
      | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'; } || true
}

dl_clean_judge() {  # dl_clean_judge <label> <judge> <commit>: 1, after a `refused:` line, unless the judge is <commit>
  local label=$1 judge=$2 commit=$3
  case "$judge" in
    '') echo "refused: $label has no judge= line (built before the judge existed?)" >&2; return 1 ;;
    unjudged) echo "refused: $label is judged 'unjudged' (a plain mvn install, not the bb build)" >&2; return 1 ;;
    *-dirty) echo "refused: $label is judged '$judge' (built from a dirty tree)" >&2; return 1 ;;
  esac
  case "$judge" in
    *[!0-9a-f]*) echo "refused: $label judge '$judge' is not 40 lowercase hex" >&2; return 1 ;;
  esac
  [ "${#judge}" -eq 40 ] || { echo "refused: $label judge '$judge' is not 40 lowercase hex" >&2; return 1; }
  [ "$judge" = "$commit" ] || { echo "refused: $label judge $judge is not the commit $commit" >&2; return 1; }
}

dl_is_hex40() { case "$1" in *[!0-9a-f]*) return 1 ;; esac; [ "${#1}" -eq 40 ]; }

dl_is_hex64() { case "$1" in *[!0-9a-f]*) return 1 ;; esac; [ "${#1}" -eq 64 ]; }

dl_is_int() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; }

# -------------------------------------------------------------------------------------- service and probe helpers

dl_restart() { $DL_SYSTEMCTL --user restart "$1"; }
dl_start() { $DL_SYSTEMCTL --user start "$1"; }
dl_stop() { $DL_SYSTEMCTL --user stop "$1"; }

dl_service_state() {  # dl_service_state <svc>: DL_ACTIVE DL_PID DL_NRESTARTS, parsed by key
  local key value out
  DL_ACTIVE= DL_PID=0 DL_NRESTARTS=0
  out=$($DL_SYSTEMCTL --user show "$1" -p ActiveState -p MainPID -p NRestarts 2> /dev/null) || true
  while IFS='=' read -r key value; do
    case "$key" in
      ActiveState) DL_ACTIVE=$value ;;
      MainPID) DL_PID=$value ;;
      NRestarts) DL_NRESTARTS=$value ;;
    esac
  done <<EOF
$out
EOF
  return 0
}

dl_fresh_active() {  # dl_fresh_active <svc> <pid-before>
  dl_service_state "$1"
  [ "$DL_ACTIVE" = active ] && [ "$DL_PID" != 0 ] && [ "$DL_PID" != "$2" ]
}

dl_remember_round() {  # the state of the passing round, which dl_settled compares against
  if [ -n "$DL_SERVICE" ]; then
    dl_service_state "$DL_SERVICE"
    DL_PASS_PID=$DL_PID
    DL_PASS_NR=$DL_NRESTARTS
  fi
}

dl_settled() {  # dl_settled <svc>: still active, same MainPID and NRestarts as the passing round
  dl_service_state "$1"
  [ "$DL_ACTIVE" = active ] && [ "$DL_PID" = "$DL_PASS_PID" ] && [ "$DL_NRESTARTS" = "$DL_PASS_NR" ]
}

dl_http() {  # dl_http <code> <url> [--token-file <f>] [--host <h>] [--body <ERE>] [--header <Name> <ERE>]
  local code=$1 url=$2 tokf= host= body= hname= hre= d got why= value
  shift 2
  while [ $# -gt 0 ]; do
    case "$1" in
      --token-file) tokf=$2; shift 2 ;;
      --host) host=$2; shift 2 ;;
      --body) body=$2; shift 2 ;;
      --header) hname=$2; hre=$3; shift 3 ;;
      *) echo "dl_http: unknown option $1" >&2; return 2 ;;
    esac
  done
  d=$(mktemp -d "${TMPDIR:-/tmp}/dl-http.XXXXXX") || return 1
  set -- -s -m 10 -o "$d/body" -D "$d/headers" -w '%{http_code}'
  if [ -n "$tokf" ]; then
    (umask 077; printf 'Authorization: Bearer %s\n' "$(tr -d '\r\n' < "$tokf")" > "$d/auth")
    set -- "$@" -H "@$d/auth"
  fi
  [ -z "$host" ] || set -- "$@" -H "Host: $host"
  got=$($DL_CURL "$@" "$url" 2> /dev/null) || got=${got:-000}
  [ "$got" = "$code" ] || why="code $got, want $code"
  if [ -z "$why" ] && [ -n "$body" ]; then
    grep -Eq -- "$body" "$d/body" 2> /dev/null || why="body does not match $body"
  fi
  if [ -z "$why" ] && [ -n "$hname" ]; then
    value=$(tr -d '\r' < "$d/headers" 2> /dev/null \
              | awk -F: -v n="$(printf '%s' "$hname" | tr 'A-Z' 'a-z')" 'tolower($1) == n { sub(/^[^:]*:[ \t]*/, ""); sub(/[ \t]+$/, ""); print; exit }') || true
    printf '%s\n' "$value" | grep -Eq -- "$hre" || why="header $hname '$value' does not match $hre"
  fi
  rm -rf "$d"
  if [ -z "$why" ]; then
    printf 'probe %s %s ok\n' "$url" "$got"
  else
    printf 'probe %s %s FAIL %s\n' "$url" "$got" "$why"
    return 1
  fi
}

dl_rc() {  # dl_rc <expected-rc> <command...>
  local want=$1 rc=0
  shift
  "$@" < /dev/null > /dev/null 2>&1 || rc=$?
  [ "$rc" = "$want" ]
}

# ----------------------------------------------------------------------------------------------- the record

dl_record_fmt() {  # dl_record_fmt <time> <event> <unit> <commit> <sha> <outcome> <rollback> <schema> <dir>
  printf '%s event=%s unit=%s commit=%s sha256=%s outcome=%s rollback=%s schema=%s dir=%s\n' "$@"
}

dl_record_line() {  # dl_record_line <event> <unit> <commit> <sha> <outcome> <rollback> <schema> <dir>
  dl_record_fmt "$(dl_now)" "$@"
}

dl_record() {  # dl_record <line>: appended to ~/.deploy/deploys.log; a failed append never changes the exit code
  if { mkdir -p "$HOME/.deploy" && chmod 700 "$HOME/.deploy" && printf '%s\n' "$1" >> "$HOME/.deploy/deploys.log"; } 2> /dev/null; then
    # only a record that is really in the log makes a later stop "after the record" (F-E)
    case "$1" in *' event=install '*) DL_RECORDED=1; DL_REC_OUTCOME=${1##*' outcome='}; DL_REC_OUTCOME=${DL_REC_OUTCOME%% *} ;; esac
  else
    printf 'RECORD NOT WRITTEN: %s\n' "$1" >&2
  fi
  return 0
}

# ------------------------------------------------------------------------------------------ hashes and sets

dl_set_id() {  # dl_set_id <manifest-file>
  local f=$1 n
  n=$(grep -c '' "$f" || true)
  if [ "$n" -eq 0 ] || ! grep -qv '^absent  ' "$f"; then
    echo absent
  elif [ "$n" -eq 1 ]; then
    cut -d' ' -f1 < "$f"
  else
    dl_sha256 "$f"
  fi
}

dl_load_files() {  # validated unit_files into $DL_TMP/files
  local name target mode extra
  unit_files > "$DL_TMP/files.raw" || dl_die "$DL_RC_REFUSED" "refused: unit_files failed"
  : > "$DL_TMP/files"
  while read -r name target mode extra; do
    [ -n "$name" ] || continue
    case "$name" in *[!A-Za-z0-9._-]*) dl_die "$DL_RC_REFUSED" "refused: unit file name '$name' is not [A-Za-z0-9._-]+" ;; esac
    case "$target" in "$HOME"/*) ;; *) dl_die "$DL_RC_REFUSED" "refused: target '$target' is not under \$HOME/" ;; esac
    case "$target" in *[[:space:]]*) dl_die "$DL_RC_REFUSED" "refused: target '$target' holds whitespace" ;; esac
    case "$mode" in ''|*[!0-7]*) dl_die "$DL_RC_REFUSED" "refused: mode '$mode' of $name is not octal" ;; esac
    [ -z "$extra" ] || dl_die "$DL_RC_REFUSED" "refused: unit_files line for $name has extra fields"
    printf '%s %s %s\n' "$name" "$target" "$mode" >> "$DL_TMP/files"
  done < "$DL_TMP/files.raw"
  [ -s "$DL_TMP/files" ] || dl_die "$DL_RC_REFUSED" "refused: unit_files printed nothing"
}

dl_dir_manifest() {  # dl_dir_manifest <dir> <out>: MANIFEST of <dir>/files/<name>, in unit_files order
  local name target mode
  : > "$2"
  while read -r name target mode; do
    [ -f "$1/files/$name" ] || { printf 'absent  %s\n' "$name" >> "$2"; continue; }
    printf '%s  %s\n' "$(dl_sha256 "$1/files/$name")" "$name" >> "$2"
  done < "$DL_TMP/files"
}

dl_live_manifest() {  # dl_live_manifest <out>
  local name target mode
  : > "$1"
  while read -r name target mode; do
    if [ -f "$target" ]; then
      printf '%s  %s\n' "$(dl_sha256 "$target")" "$name" >> "$1"
    else
      printf 'absent  %s\n' "$name" >> "$1"
    fi
  done < "$DL_TMP/files"
}

dl_pump() {  # dl_pump <fd>: every line of stdin to that saved stream (a failed write is dropped) and to B/install.log (DL_TMP until B exists)
  local line t="$DL_TMP/install.$1.log"
  trap '' INT TERM  # HUP and PIPE stay ignored, inherited from dl_shield
  while IFS= read -r line || [ -n "$line" ]; do
    if [ -d "$DL_B" ]; then
      [ ! -s "$t" ] || { cat "$t" >> "$DL_B/install.log" && : > "$t"; } 2> /dev/null || true
      printf '%s\n' "$line" 2> /dev/null >> "$DL_B/install.log" || true
    else
      printf '%s\n' "$line" 2> /dev/null >> "$t" || true
    fi
    printf '%s\n' "$line" 2> /dev/null >&"$1" || true
  done
}

dl_shield() {  # D3.7a, once the lock is held: SIGHUP and SIGPIPE ignored, all output also in B/install.log, SIGTERM/SIGINT end partial
  trap '' HUP PIPE
  DL_B="$HOME/.deploy/rollback/$DL_UNIT-$(dl_stamp)"
  # a closed stdout/stderr (`>&-`): bash 3.2 lets `true >&1` pass there, so the descriptor's own /dev/fd entry is the test
  if [ -e /dev/fd/1 ]; then exec 5>&1; else exec 5> /dev/null; fi
  if [ -e /dev/fd/2 ]; then exec 6>&2; else exec 6> /dev/null; fi
  mkfifo "$DL_TMP/out" "$DL_TMP/err"
  dl_pump 5 < "$DL_TMP/out" &
  DL_PUMPS="$!"
  dl_pump 6 < "$DL_TMP/err" &
  DL_PUMPS="$DL_PUMPS $!"
  exec > "$DL_TMP/out" 2> "$DL_TMP/err"
  trap 'dl_on_signal TERM' TERM
  trap 'dl_on_signal INT' INT
}

dl_on_signal() {  # dl_on_signal <NAME>: an operator's deliberate stop: record partial, release the lock, no rollback
  trap '' INT TERM
  [ -z "$DL_BG" ] || kill "$DL_BG" 2> /dev/null || true
  if [ "$DL_RECORDED" = 1 ]; then
    echo "stopped by SIG$1 during ${DL_PHASE:-start}, after the install record was written; no second record; live state: see $DL_B/" >&2
    # the install itself completed when the record says installed or unchanged: only health-failed leaves work undone
    case "$DL_REC_OUTCOME" in installed|unchanged) exit 0 ;; esac
    exit "$DL_RC_PARTIAL"
  fi
  echo "PARTIAL: stopped by SIG$1 during ${DL_PHASE:-start}; live state: see $DL_B/" >&2
  [ -d "$DL_B" ] || DL_B=none
  # shellcheck disable=SC2046
  dl_do_record $(dl_rec install partial)
  exit "$DL_RC_PARTIAL"
}

dl_cleanup() {  # never aborts: the lock goes first, a missing saved stream (fd 5/6 gone) falls back to /dev/null
  [ "$DL_LOCK_HELD" != 1 ] || rm -rf "$HOME/.deploy/lock"
  DL_LOCK_HELD=0
  if [ -n "$DL_PUMPS" ]; then
    if [ -e /dev/fd/5 ]; then exec 1>&5; else exec 1> /dev/null; fi
    if [ -e /dev/fd/6 ]; then exec 2>&6; else exec 2> /dev/null; fi
    # shellcheck disable=SC2086
    wait $DL_PUMPS 2> /dev/null || true
    DL_PUMPS=
  fi
  [ -z "$DL_TMP" ] || rm -rf "$DL_TMP"
  return 0
}

dl_schema_join() {  # dl_schema_join <before> <after> -> V<n> | V<b>->V<a> | n/a | unknown
  if dl_is_int "$1" && dl_is_int "$2"; then
    if [ "$1" = "$2" ]; then printf 'V%s' "$1"; else printf 'V%s->V%s' "$1" "$2"; fi
  elif [ "$1" = n/a ] && [ "$2" = n/a ]; then
    printf 'n/a'
  else
    printf 'unknown'
  fi
}

dl_schema_one() {  # dl_schema_one <value> -> V<n> | n/a | unknown
  if dl_is_int "$1"; then printf 'V%s' "$1"; elif [ "$1" = n/a ]; then printf 'n/a'; else printf 'unknown'; fi
}

dl_read_schema() {
  local v=n/a
  if dl_has unit_schema; then v=$(unit_schema 2> /dev/null) || v=unknown; fi
  case "$v" in n/a|unknown) ;; *) dl_is_int "$v" || v=unknown ;; esac
  printf '%s' "$v"
}

# ------------------------------------------------------------------------------------------------ the plan

dl_p() {  # dl_p <phase> <action> [field...]: one numbered plan line; a field with whitespace refuses
  local a
  for a in "$@"; do
    case "$a" in ''|*[[:space:]]*) dl_die "$DL_RC_REFUSED" "refused: plan field '$a' is empty or holds whitespace" ;; esac
  done
  DL_N=$((DL_N + 1))
  printf 'plan %03d %s' "$DL_N" "$1" >> "$DL_PLAN"
  shift
  printf ' %s' "$@" >> "$DL_PLAN"
  printf '\n' >> "$DL_PLAN"
}

dl_p_lines() {  # dl_p_lines <phase> <file>: each line of the file is `<action> <field>...`
  local line
  [ -s "$2" ] || return 0
  set -f
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    # shellcheck disable=SC2086
    dl_p "$1" $line
  done < "$2"
  set +f
}

dl_probe_lines() {  # dl_probe_lines <phase> <which>: timeout, probes, settle, reprobe
  local ids= id rule
  dl_p "$1" timeout "$DL_HEALTH_TIMEOUT" interval "$DL_HEALTH_INTERVAL"
  unit_probes "$2" > "$DL_TMP/probes" || dl_die "$DL_RC_REFUSED" "refused: unit_probes $2 failed"
  while read -r id rule; do
    [ -n "$id" ] || continue
    dl_p "$1" probe "$id" "${rule:-none}"
    ids="$ids,$id"
  done < "$DL_TMP/probes"
  dl_p "$1" settle "${DL_SERVICE:-none}" "$DL_SETTLE"
  dl_p "$1" reprobe "${ids#,}"
}

dl_rec() {  # dl_rec <event> <outcome>: the planned record fields (time, schema and dir are placeholders)
  dl_record_fmt '<time>' "$1" "$DL_UNIT" "$DL_COMMIT" "$DL_SET_NEW" "$2" "$DL_SET_OLD" '<s>' '<B>'
}

dl_plan_build() {  # after dl_preflight: $DL_PLAN is the header and every plan line
  local name target mode old new bak sub id value
  DL_PLAN="$DL_TMP/plan"
  DL_N=0
  printf 'plan-header v1 unit=%s commit=%s host=%s current=%s new=%s\n' \
    "$DL_UNIT" "$DL_COMMIT" "${DL_HOSTNAME:-$(hostname -s)}" "$DL_SET_OLD" "$DL_SET_NEW" > "$DL_PLAN"
  dl_p check staged-copy "$DL_STAGE_DIR"
  dl_p check manifest "$(dl_sha256 "$DL_STAGE_DIR/MANIFEST")"
  dl_p check expect-new "$DL_EXP_NEW"
  dl_p check expect-current "$DL_EXP_CUR"
  dl_p check lock free
  while read -r id value; do
    [ -n "$id" ] || continue
    dl_p check "$id" "${value:-ok}"
  done < "$DL_TMP/pre"
  if [ "$DL_SET_OLD" = "$DL_SET_NEW" ]; then
    # shellcheck disable=SC2046
    dl_p record record $(dl_rec install unchanged | sed 's/dir=<B>/dir=none/')
    return 0
  fi
  dl_p prepare mkdir '<B>' 0700
  dl_p prepare write '<B>/MANIFEST.old' "$(dl_sha256 "$DL_TMP/live.MANIFEST")"
  if dl_has unit_plan_prepare; then unit_plan_prepare > "$DL_TMP/sub" || dl_die "$DL_RC_REFUSED" "refused: unit_plan_prepare failed"; dl_p_lines prepare "$DL_TMP/sub"; fi
  : > "$DL_TMP/swapped"
  while read -r name target mode; do
    old=$(grep "  $name\$" "$DL_TMP/live.MANIFEST" | cut -d' ' -f1)
    new=$(grep "  $name\$" "$DL_STAGE_DIR/MANIFEST" | cut -d' ' -f1)
    [ "$old" = "$new" ] && continue
    printf '%s %s %s %s %s\n' "$name" "$target" "$mode" "$old" "$new" >> "$DL_TMP/swapped"
  done < "$DL_TMP/files"
  while read -r name target mode; do
    old=$(grep "  $name\$" "$DL_TMP/live.MANIFEST" | cut -d' ' -f1)
    new=$(grep "  $name\$" "$DL_STAGE_DIR/MANIFEST" | cut -d' ' -f1)
    [ "$old" != absent ] && [ "$old" != "$new" ] || continue
    bak="$target.bak-$old"
    if [ -f "$bak" ] && [ "$(dl_sha256 "$bak")" = "$old" ]; then
      dl_p backup backup-kept "$target" "$bak"
    else
      dl_p backup backup "$target" "$bak"
    fi
  done < "$DL_TMP/files"
  while read -r name target mode; do
    old=$(grep "  $name\$" "$DL_TMP/live.MANIFEST" | cut -d' ' -f1)
    new=$(grep "  $name\$" "$DL_STAGE_DIR/MANIFEST" | cut -d' ' -f1)
    if [ "$old" = "$new" ]; then
      dl_p swap keep "$target" "$new"
    else
      dl_p swap copy "$DL_STAGE_DIR/files/$name" "$target.partial"
      dl_p swap chmod "$mode" "$target.partial"
      dl_p swap rename "$target.partial" "$target" "$old" "$new"
      dl_p swap verify "$target" "$new"
    fi
  done < "$DL_TMP/files"
  if dl_has unit_plan_restart; then unit_plan_restart new > "$DL_TMP/sub" || dl_die "$DL_RC_REFUSED" "refused: unit_plan_restart new failed"; elif [ -n "$DL_SERVICE" ]; then echo "restart $DL_SERVICE" > "$DL_TMP/sub"; else : > "$DL_TMP/sub"; fi
  dl_p_lines restart "$DL_TMP/sub"
  dl_probe_lines health new
  if dl_has unit_plan_after; then unit_plan_after new > "$DL_TMP/sub" || dl_die "$DL_RC_REFUSED" "refused: unit_plan_after new failed"; dl_p_lines after "$DL_TMP/sub"; fi
  # shellcheck disable=SC2046
  dl_p record record $(dl_rec install installed)
  # shellcheck disable=SC2046
  dl_p on-unhealthy record $(dl_rec install health-failed)
  dl_p on-unhealthy forward-only-if 'schema>V<before>'
  sub=$(awk '{a[NR]=$0} END {for (i = NR; i >= 1; i--) print a[i]}' "$DL_TMP/swapped")
  printf '%s\n' "$sub" > "$DL_TMP/swapped.rev"
  while read -r name target mode old new; do
    [ -n "$name" ] || continue
    if [ "$old" = absent ]; then
      dl_p rollback set-aside "$target" "$target.failed-$new"
    else
      dl_p rollback restore "$target.bak-$old" "$target" "$old"
    fi
    dl_p rollback verify "$target" "$old"
  done < "$DL_TMP/swapped.rev"
  if dl_has unit_plan_restart; then unit_plan_restart old > "$DL_TMP/sub" || dl_die "$DL_RC_REFUSED" "refused: unit_plan_restart old failed"; elif [ -n "$DL_SERVICE" ]; then echo "restart $DL_SERVICE" > "$DL_TMP/sub"; else : > "$DL_TMP/sub"; fi
  dl_p_lines rollback "$DL_TMP/sub"
  dl_probe_lines rollback old
  if dl_has unit_plan_after; then unit_plan_after old > "$DL_TMP/sub" || dl_die "$DL_RC_REFUSED" "refused: unit_plan_after old failed"; dl_p_lines rollback "$DL_TMP/sub"; fi
  # shellcheck disable=SC2046
  dl_p rollback record $(dl_rec rollback rolled-back)
  # shellcheck disable=SC2046
  dl_p rollback else-record $(dl_rec rollback rollback-failed) exit 6 banner
}

dl_plan_digest() { dl_sha256 "$DL_PLAN"; }

dl_plan_print() {
  cat "$DL_PLAN"
  printf 'plan-sha256 %s\n' "$(dl_plan_digest)"
}

# -------------------------------------------------------------------------------------------- the executor

dl_sub() {  # <B> and <time> in a planned text
  local t=$1
  t=${t//<B>/${DL_B:-none}}
  t=${t//<time>/$(dl_now)}
  printf '%s' "$t"
}

dl_place() {  # dl_place <file> <target> <mode>: copy next to the target, chmod, one rename
  mkdir -p "$(dirname "$2")" && cp "$1" "$2.partial" && chmod "$3" "$2.partial" && mv -f "$2.partial" "$2"
}

dl_mode_of() { awk -v t="$1" '$2 == t { print $3; exit }' "$DL_TMP/files"; }

dl_live_lines() {  # one line per target: <target> <sha256|absent>
  local name target mode
  while read -r name target mode; do
    if [ -f "$target" ]; then echo "$target $(dl_sha256 "$target")"; else echo "$target absent"; fi
  done < "$DL_TMP/files"
}

dl_sha_or_absent() { if [ -f "$1" ]; then dl_sha256 "$1"; else echo absent; fi; }

dl_do_record() {  # dl_do_record <planned record fields...>
  local text outcome schema s
  text="$*"
  outcome=$(printf '%s' "$text" | tr ' ' '\n' | sed -n 's/^outcome=//p')
  case "$outcome" in
    health-failed|partial) s=$(dl_schema_one "$DL_SCHEMA_BEFORE") ;;
    unchanged) s=$(dl_schema_one "$(dl_read_schema)") ;;
    *)
      [ -n "$DL_SCHEMA_AFTER" ] || DL_SCHEMA_AFTER=$(dl_read_schema)
      s=$(dl_schema_join "$DL_SCHEMA_BEFORE" "$DL_SCHEMA_AFTER")
      ;;
  esac
  text=${text//<s>/$s}
  dl_record "$(dl_sub "$text")"
  return 0
}

dl_expected_lines() {  # one line per target: <target> <old sha256|absent>
  local name target mode
  while read -r name target mode; do
    echo "$target $(grep "  $name\$" "$DL_TMP/live.MANIFEST" | cut -d' ' -f1)"
  done < "$DL_TMP/files"
}

dl_banner() {
  local name target mode
  {
    echo "==================== ROLLBACK FAILED ===================="
    echo "unit=$DL_UNIT commit=$DL_COMMIT host=${DL_HOSTNAME:-$(hostname -s)}"
    echo "the unit is NOT healthy; nothing more will be tried automatically"
    echo "failed at: ${DL_FAILED_AT:-unknown}"
    dl_live_lines | sed 's/^/live: /'
    dl_expected_lines | sed "s/^/expected (old set $DL_SET_OLD): /"
    echo "backups: $(while read -r name target mode; do ls "$target".bak-* 2> /dev/null || true; done < "$DL_TMP/files" | tr '\n' ' ')B=$DL_B"
    echo "manual route: the deploy runbook, Rollback"
    echo "========================================================="
  } >&2
}

dl_rounds() {  # dl_rounds <which>: unit_health rounds until one passes or DL_HT seconds are gone
  local deadline=$((SECONDS + DL_HT))
  while :; do
    if unit_health "$1"; then dl_remember_round; return 0; fi
    [ "$SECONDS" -lt "$deadline" ] || break
    sleep "$DL_HI"
  done
  DL_FAILED_AT="probe: unit_health $1 did not pass within ${DL_HT}s"
  return 1
}

dl_act() {  # dl_act <phase> <action> <field...>: one plan action
  local phase=$1 action=$2 which=new want got
  shift 2
  [ "$phase" != rollback ] || which=old
  DL_FAILED_AT="$action"
  case "$action" in
    check|backup-kept|keep) return 0 ;;
    mkdir)
      mkdir -p "$HOME/.deploy/rollback" && chmod 700 "$HOME/.deploy" && mkdir -p -m 0700 "$(dl_sub "$1")" || return 1
      printf '%s\n' "$DL_SCHEMA_BEFORE" > "$DL_B/SCHEMA_BEFORE" && cp "$DL_PLAN" "$DL_B/PLAN"
      ;;
    write)
      [ "$(dl_sha256 "$DL_TMP/live.MANIFEST")" = "$2" ] || return 1
      cp "$DL_TMP/live.MANIFEST" "$(dl_sub "$1")"
      ;;
    stop)
      dl_stop "$1" || return 1
      if [ "$phase" = prepare ]; then
        DL_SCHEMA_BEFORE=$(dl_read_schema)
        printf '%s\n' "$DL_SCHEMA_BEFORE" > "$DL_B/SCHEMA_BEFORE"
      fi
      ;;
    start) dl_start "$1" ;;
    restart) dl_restart "$1" ;;
    disable) $DL_SYSTEMCTL --user disable --now "$1" ;;
    bootout) $DL_LAUNCHCTL bootout "gui/$(id -u)/$1" ;;
    bootstrap) $DL_LAUNCHCTL bootstrap "gui/$(id -u)" "$2" ;;
    kickstart) $DL_LAUNCHCTL kickstart -k "gui/$(id -u)/$1" ;;
    dump)
      DL_DUMP_CONTAINER=$1
      DL_FAILED_AT="dump $1"
      $DL_DOCKER exec "$1" pg_dump -U "$2" -d "$2" -Fc > "$(dl_sub "$3")"
      ;;
    verify-dump)
      DL_FAILED_AT="verify-dump $1"
      $DL_DOCKER exec -i "$DL_DUMP_CONTAINER" pg_restore --list < "$(dl_sub "$1")" | grep " $2 public " > /dev/null
      ;;
    backup) cp -p "$1" "$2" ;;
    copy) mkdir -p "$(dirname "$2")" && cp "$1" "$2" ;;
    chmod) chmod "$1" "$2" ;;
    rename) mv -f "$1" "$2" ;;
    verify)
      DL_FAILED_AT="verify $1"
      got=$(dl_sha_or_absent "$1")
      [ "$got" = "$2" ]
      ;;
    restore)
      DL_FAILED_AT="restore $2"
      dl_place "$1" "$2" "$(dl_mode_of "$2")"
      ;;
    set-aside)
      DL_FAILED_AT="restore $1"
      mv -f "$1" "$2"
      ;;
    timeout) DL_HT=$1; DL_HI=$3; DL_ROUNDS_DONE=0 ;;
    probe)
      DL_FAILED_AT="probe $1"
      [ "$DL_ROUNDS_DONE" = 1 ] || { DL_ROUNDS_DONE=1; dl_rounds "$which"; }
      ;;
    settle)
      sleep "$2" > /dev/null 2>&1 &
      DL_BG=$!
      wait "$DL_BG"
      DL_BG=
      DL_FAILED_AT="probe settle: $1 crashed or restarted during the settle window"
      [ "$1" = none ] || dl_settled "$1"
      ;;
    reprobe)
      DL_FAILED_AT="probe $1"
      DL_PAST_HEALTH=1
      unit_health "$which"
      ;;
    record) dl_do_record "$@" ;;
    forward-only-if) dl_forward_only ;;
    *) DL_FAILED_AT="unknown plan action $action"; return 1 ;;
  esac
}

dl_forward_only() {  # rc 1 (and the exit-7 outcome recorded) when the schema advanced past SCHEMA_BEFORE
  local dump
  DL_SCHEMA_AFTER=$(dl_read_schema)
  printf '%s\n' "$DL_SCHEMA_AFTER" > "$DL_B/SCHEMA_AFTER" 2> /dev/null || true
  if dl_is_int "$DL_SCHEMA_BEFORE" && dl_is_int "$DL_SCHEMA_AFTER" && [ "$DL_SCHEMA_AFTER" -gt "$DL_SCHEMA_BEFORE" ]; then
    dump=$(ls "$DL_B"/*.dump 2> /dev/null | head -n 1) || true
    echo "FORWARD-ONLY: the schema stays at V$DL_SCHEMA_AFTER (was V$DL_SCHEMA_BEFORE); the old binary $DL_SET_OLD would refuse it at startup (MigrationNotInBuild); the new set stays installed; pre-install dump ${dump:-none}; manual route: the deploy runbook, Rollback" >&2
    # shellcheck disable=SC2046
    dl_do_record $(dl_rec rollback kept-forward-only)
    return 1
  fi
  return 0
}

dl_run_phases() {  # dl_run_phases <phase>...: the plan's lines of those phases, in plan order, to the first failure
  local want=" $* " line n ph act rest rc soft
  DL_FAIL_PHASE=
  while IFS= read -r -u 3 line; do
    case "$line" in 'plan '[0-9]*) ;; *) continue ;; esac
    read -r _ n ph act rest <<EOF
$line
EOF
    case "$want" in *" $ph "*) ;; *) continue ;; esac
    [ "$act" != else-record ] || continue
    [ "$ph" != check ] || act=check  # a check line is `check <id> <value>`: the phase is the action
    soft=0
    [ "$ph" != after ] || soft=1
    [ "$ph" != rollback ] || [ "$DL_PAST_HEALTH" != 1 ] || { [ "$act" = record ] || soft=1; }
    DL_PHASE=$ph
    printf 'do %s\n' "${line#plan }"
    rc=0
    set -f
    # shellcheck disable=SC2086
    dl_act "$ph" "$act" $rest || rc=$?
    set +f
    if [ "$rc" -eq 0 ]; then
      printf 'done %s\n' "$n"
    elif [ "$soft" = 1 ]; then
      printf 'WARNING: %s %s failed (the outcome is unchanged)\n' "$n" "$act" >&2
      printf 'done %s\n' "$n"
    else
      DL_FAIL_PHASE=$ph
      DL_FAIL_ACT=$act
      printf 'FAILED %s %s\n' "$n" "$DL_FAILED_AT"
      printf 'FAILED %s %s\n' "$n" "$DL_FAILED_AT" >&2
      return 1
    fi
  done 3< "$DL_PLAN"
  return 0
}

# ------------------------------------------------------------------------------------------------ install

dl_preflight() {  # steps 1-5 (read-only except the lock); sets DL_STAGE_DIR DL_SET_OLD DL_SET_NEW
  local here want stage_unit stage_commit stage_set computed
  # 1
  here=$(cd -P "$(dirname "$0")" && pwd)
  want="$(cd -P "$HOME" && pwd)/.deploy/stage/$DL_UNIT/$DL_COMMIT"
  [ "$here" = "$want" ] || dl_die "$DL_RC_REFUSED" "refused: install runs the staged copy ($want), not $here"
  DL_STAGE_DIR=$here
  [ -f "$here/STAGE" ] || dl_die "$DL_RC_REFUSED" "refused: $here/STAGE is missing"
  stage_unit=$(sed -n 's/^unit=//p' "$here/STAGE")
  stage_commit=$(sed -n 's/^commit=//p' "$here/STAGE")
  stage_set=$(sed -n 's/^set=//p' "$here/STAGE")
  [ "$stage_unit" = "$DL_UNIT" ] && [ "$stage_commit" = "$DL_COMMIT" ] \
    || dl_die "$DL_RC_REFUSED" "refused: STAGE says unit=$stage_unit commit=$stage_commit, not $DL_UNIT $DL_COMMIT"
  # 2
  dl_dir_manifest "$here" "$DL_TMP/MANIFEST.now"
  cmp -s "$DL_TMP/MANIFEST.now" "$here/MANIFEST" || dl_die "$DL_RC_REFUSED" "refused: the staged files do not hash to their MANIFEST"
  computed=$(dl_set_id "$DL_TMP/MANIFEST.now")
  DL_SET_NEW=$computed
  [ "$computed" = "$stage_set" ] || dl_die "$DL_RC_REFUSED" "refused: the staged set $computed is not STAGE's set= $stage_set"
  [ "$computed" = "$DL_EXP_NEW" ] || dl_die "$DL_RC_REFUSED" "refused: the staged set is $computed, --expect-new says $DL_EXP_NEW"
  if dl_has unit_stage_verify; then
    unit_stage_verify "$here" "$DL_COMMIT" > /dev/null || dl_die "$DL_RC_REFUSED" "refused: unit_stage_verify failed on the staged files"
  fi
  # 3
  dl_live_manifest "$DL_TMP/live.MANIFEST"
  DL_SET_OLD=$(dl_set_id "$DL_TMP/live.MANIFEST")
  if [ "$DL_SET_OLD" != "$DL_EXP_CUR" ]; then
    dl_die "$DL_RC_REFUSED" "refused: the live set is $DL_SET_OLD, --expect-current says $DL_EXP_CUR
$(cat "$DL_TMP/live.MANIFEST")"
  fi
  # 4
  if [ "$DL_DRY" = 1 ]; then
    [ ! -e "$HOME/.deploy/lock" ] || dl_die "$DL_RC_REFUSED" "refused: another install holds ~/.deploy/lock (pid $(cat "$HOME/.deploy/lock/pid" 2> /dev/null || echo "?"))"
  else
    mkdir -p "$HOME/.deploy"
    mkdir -m 0700 "$HOME/.deploy/lock" 2> /dev/null \
      || dl_die "$DL_RC_REFUSED" "refused: another install holds ~/.deploy/lock (pid $(cat "$HOME/.deploy/lock/pid" 2> /dev/null || echo "?"))"
    DL_LOCK_HELD=1
    echo $$ > "$HOME/.deploy/lock/pid"
    dl_shield
  fi
  # 5
  : > "$DL_TMP/pre"
  if dl_has unit_preconditions; then
    unit_preconditions > "$DL_TMP/pre" || dl_die "$DL_RC_REFUSED" "refused: a unit precondition failed"
  fi
}

dl_install() {
  local os
  os=$(uname -s)
  [ "$os" = "$DL_OS" ] || dl_die "$DL_RC_REFUSED" "refused: this unit installs on $DL_OS, this host is $os"
  dl_load_files
  dl_preflight
  dl_plan_build
  if dl_has unit_drift; then unit_drift || true; fi  # D4.1: reported by stage (via live) and by install, never refused
  if [ "$DL_DRY" = 1 ]; then
    dl_plan_print
    return 0
  fi
  if [ "$(dl_plan_digest)" != "$DL_EXP_PLAN" ]; then
    dl_die "$DL_RC_REFUSED" "refused: the plan digest is $(dl_plan_digest), --expect-plan says $DL_EXP_PLAN; a new dry-run and a new clearance are needed
$(dl_plan_print)"
  fi
  printf 'do-header %s\n' "$(head -n 1 "$DL_PLAN" | sed 's/^plan-header //')"
  DL_SCHEMA_BEFORE=unknown  # read after the unit's stop (D4); a plan without one reads it here
  grep -q '^plan [0-9]* prepare stop ' "$DL_PLAN" || DL_SCHEMA_BEFORE=$(dl_read_schema)
  if [ "$DL_SET_OLD" = "$DL_SET_NEW" ]; then
    dl_run_phases check record || true
    return 0
  fi
  [ -z "$DL_SERVICE" ] || { dl_service_state "$DL_SERVICE"; DL_PID_BEFORE=$DL_PID; }
  if dl_run_phases check prepare backup swap restart health after record; then
    trap - INT TERM
    if dl_has unit_manual; then unit_manual; fi
    return 0
  fi
  case "$DL_FAIL_PHASE" in
    restart|health) dl_unhealthy ;;
    *) dl_partial ;;
  esac
}

dl_partial() {
  local name target mode new old
  echo "partial: target state against the staged (MANIFEST) and the previous (MANIFEST.old) set:" >&2
  while read -r name target mode; do
    new=$(grep "  $name\$" "$DL_STAGE_DIR/MANIFEST" | cut -d' ' -f1)
    old=$(grep "  $name\$" "$DL_TMP/live.MANIFEST" | cut -d' ' -f1)
    echo "  $target now=$(dl_sha_or_absent "$target") manifest=$new manifest.old=$old" >&2
  done < "$DL_TMP/files"
  [ -d "$DL_B" ] || DL_B=none
  # shellcheck disable=SC2046
  dl_do_record $(dl_rec install partial)
  if dl_has unit_on_partial; then unit_on_partial || true; fi
  return "$DL_RC_PARTIAL"
}

dl_unhealthy() {
  local rc=0
  DL_PAST_HEALTH=0
  dl_run_phases on-unhealthy || rc=$?
  if [ "$rc" -ne 0 ]; then
    [ "$DL_FAIL_ACT" = forward-only-if ] || { echo "FAILED: the health-failed record step failed" >&2; }
    return "$DL_RC_FORWARD_ONLY"
  fi
  [ -z "$DL_SERVICE" ] || { dl_service_state "$DL_SERVICE"; DL_PID_BEFORE=$DL_PID; }
  if dl_run_phases rollback; then
    echo "ROLLBACK: restored $DL_SET_OLD; the schema is unchanged at $(dl_schema_one "$DL_SCHEMA_BEFORE")"
    return "$DL_RC_ROLLED_BACK"
  fi
  grep '^plan [0-9]* rollback else-record ' "$DL_PLAN" | head -n 1 > "$DL_TMP/else"
  read -r _ _ _ _ rest < "$DL_TMP/else"
  set -f
  # shellcheck disable=SC2086
  dl_do_record ${rest% exit 6 banner}
  set +f
  dl_banner
  return "$DL_RC_ROLLBACK_FAILED"
}

# --------------------------------------------------------------------------------------------- stage-local

dl_stage_local() {
  local os want into name target mode judge out stamp src_name
  os=$(uname -s)
  [ "$os" = "$DL_OS" ] || dl_die "$DL_RC_REFUSED" "refused: this unit stages on $DL_OS, this host is $os"
  [ -n "$DL_SRC" ] && [ -n "$DL_INTO" ] || dl_die "$DL_RC_USAGE" "usage: stage-local --commit <40hex> --src <dir> --into <dir>"
  [ -d "$DL_INTO" ] || dl_die "$DL_RC_REFUSED" "refused: --into $DL_INTO is not a directory"
  into=$(cd -P "$DL_INTO" && pwd)
  want="$(cd -P "$HOME" && pwd)/.deploy/stage/$DL_UNIT/"
  case "$into/" in
    "$want"?*) ;;
    *) dl_die "$DL_RC_REFUSED" "refused: --into $into is not under $want" ;;
  esac
  [ -d "$DL_SRC" ] || dl_die "$DL_RC_REFUSED" "refused: --src $DL_SRC is not a directory"
  dl_load_files
  mkdir -p "$into/files"
  unit_stage_collect "$DL_SRC" "$into" || { rm -rf "$into"; dl_die "$DL_RC_REFUSED" "refused: unit_stage_collect failed"; }
  dl_dir_manifest "$into" "$into/MANIFEST"
  if grep -q '^absent  ' "$into/MANIFEST"; then rm -rf "$into"; dl_die "$DL_RC_REFUSED" "refused: a unit file was not collected"; fi
  judge=none
  if dl_has unit_stage_verify; then
    out=$(unit_stage_verify "$into" "$DL_COMMIT") || { rm -rf "$into"; dl_die "$DL_RC_REFUSED" "refused: unit_stage_verify failed"; }
    judge=$(printf '%s\n' "$out" | sed -n 's/^judge //p' | head -n 1)
    judge=${judge:-none}
  fi
  {
    printf '%s  lib.sh\n' "$(dl_sha256 "$DL_LIB_DIR/lib.sh")"
    printf '%s  deploy-%s.sh\n' "$(dl_sha256 "$0")" "$DL_UNIT"
  } > "$into/SCRIPTS"
  src_name=git-archive
  [ -z "$DL_BUILD_ARGS" ] || src_name=$(basename "$DL_SRC")
  {
    echo "unit=$DL_UNIT"
    echo "commit=$DL_COMMIT"
    echo "judge=$judge"
    echo "set=$(dl_set_id "$into/MANIFEST")"
    echo "staged_at=$(dl_now)"
    echo "src=$src_name"
    echo "build_args=$DL_BUILD_ARGS"
  } > "$into/STAGE"
  stamp=$(dl_set_id "$into/MANIFEST")
  printf 'staged %s set %s\n' "${into%.tmp-*}" "$stamp"  # the final dir: the caller renames the .tmp-<pid> dir into place next
}

dl_live() {  # read-only: the live set id, the live MANIFEST and the unit's drift
  dl_load_files
  dl_live_manifest "$DL_TMP/live.MANIFEST"
  printf 'live %s\n' "$(dl_set_id "$DL_TMP/live.MANIFEST")"
  cat "$DL_TMP/live.MANIFEST"
  if dl_has unit_drift; then unit_drift || true; fi
}

# ------------------------------------------------------------------------------------------------- stage

# Host facts come from the bb host config (~/.config/bb-temper/hosts or BB_TEMPER_HOSTS; format: ops/bb/hosts.example), read
# only by `stage` (the only verb that reaches another host): DL_HOST / DL_BUILD_HOST given as a host name become that host's
# user@address (user@address and `local` are used as given), and the config's key is the ssh key of DL_REMOTE /
# DL_BUILD_REMOTE (when the caller did not set them) and of the owner's install line. No config, or a name it lacks: refused.
dl_host_config() { printf '%s\n' "${BB_TEMPER_HOSTS:-$HOME/.config/bb-temper/hosts}"; }
dl_host_fact() {  # dl_host_fact key | dl_host_fact <name>: the key path as written, or the host's user@address
  tr -d '\r' < "$(dl_host_config)" | sed 's/#.*//' | awk -v want="$1" 'want == "key" && $1 == "key" && NF == 2 { v = $2 } want != "key" && $1 == "host" && $2 == want { v = $3 } END { print v }'
}
dl_resolve_hosts() {
  local h key
  [ -r "$(dl_host_config)" ] || dl_die "$DL_RC_REFUSED" "refused: no host config $(dl_host_config) (format: ops/bb/hosts.example)"
  h=$(tr -d '\r' < "$(dl_host_config)" | sed 's/#.*//' | awk '$1 == "key" && NF == 2 && ++n == 2 { print NR; exit }')
  [ -z "$h" ] || dl_die "$DL_RC_REFUSED" "refused: $(dl_host_config): line $h: key is listed twice"
  case "$DL_HOST" in local|*@*) ;; *) h=$(dl_host_fact "$DL_HOST"); [ -n "$h" ] || dl_die "$DL_RC_REFUSED" "refused: host $DL_HOST is not in $(dl_host_config)"; DL_HOST=$h ;; esac
  case "$DL_BUILD_HOST" in *@*) ;; *) h=$(dl_host_fact "$DL_BUILD_HOST"); [ -n "$h" ] || dl_die "$DL_RC_REFUSED" "refused: host $DL_BUILD_HOST is not in $(dl_host_config)"; DL_BUILD_HOST=$h ;; esac
  DL_SSH_KEY_TEXT=$(dl_host_fact key)
  [ -n "$DL_SSH_KEY_TEXT" ] || dl_die "$DL_RC_REFUSED" "refused: no 'key <path>' line in $(dl_host_config)"
  case "$DL_SSH_KEY_TEXT" in '~/'*) key=$HOME/${DL_SSH_KEY_TEXT#'~/'} ;; *) key=$DL_SSH_KEY_TEXT ;; esac
  : "${DL_REMOTE:=ssh -i $key -o BatchMode=yes -o ConnectTimeout=15 $DL_HOST}"
  : "${DL_BUILD_REMOTE:=ssh -i $key -o BatchMode=yes -o ConnectTimeout=15 $DL_BUILD_HOST}"
}

dl_remote() {  # dl_remote <command string>: on the stage host; 255 (ssh itself) is "unreachable"
  local rc=0
  $DL_REMOTE "$1" || rc=$?
  [ "$rc" -ne 255 ] || dl_die "$DL_RC_UNREACHABLE" "FAILED: the host $DL_HOST did not answer"
  return "$rc"
}

dl_build_remote() { $DL_BUILD_REMOTE "$1"; }
dl_bb() { BB_HOST="$DL_BUILD_HOST" $DL_BB_TEMPER "$@"; }  # always the build host: bb-temper picks one itself when BB_HOST is unset

dl_on_host() {  # dl_on_host <commit> <script args...>: the commit's lib.sh + unit script, run from a temp dir on the host
  local commit=$1
  shift
  $DL_GIT archive "$commit" ops/deploy/lib.sh "ops/deploy/deploy-$DL_UNIT.sh" \
    | dl_remote "d=\$(mktemp -d) && tar -x -C \$d --strip-components=2 -f - && bash \$d/deploy-$DL_UNIT.sh $*; rc=\$?; rm -rf \$d; exit \$rc"
}

dl_stage() {
  local c12=${DL_COMMIT:0:12} i ok=0 name co branch final T src live_out live_id name2 target mode line plan plan_sha owner_line cmd
  # 1
  for i in 1 2 3 4 5 6; do
    if $DL_GIT fetch -q origin; then ok=1; break; fi
    [ "$i" -eq 6 ] || sleep "$DL_FETCH_SLEEP"
  done
  [ "$ok" = 1 ] || dl_die "$DL_RC_UNREACHABLE" "FAILED: git fetch origin failed 6 times"
  $DL_GIT merge-base --is-ancestor "$DL_COMMIT" origin/main 2> /dev/null \
    || dl_die "$DL_RC_REFUSED" "refused: $DL_COMMIT is not on origin/main (Q8: stage only commits that landed)"
  final="~/.deploy/stage/$DL_UNIT/$DL_COMMIT"
  name="$BB_OWNER-deploy-$DL_UNIT-$c12"
  branch="deploy-stage/$DL_UNIT-$c12"
  # 2
  if [ "$DL_DRY" = 1 ]; then
    echo "stage-plan commit $DL_COMMIT"
    echo "stage-plan unit $DL_UNIT"
    echo "stage-plan host $DL_HOST"
    if [ -n "$DL_BUILD_ARGS" ]; then
      echo "stage-plan checkout $name"
      echo "stage-plan build-args $DL_BUILD_ARGS -Dtemper.judge=$DL_COMMIT"
    else
      echo "stage-plan source git-archive"
    fi
    echo "stage-plan stage-dir $final"
    dl_load_files
    while read -r name2 target mode; do echo "stage-plan file $name2 ~/${target#$HOME/} $mode"; done < "$DL_TMP/files"
    live_out=$(dl_on_host "$DL_COMMIT" live) || dl_die "$DL_RC_REFUSED" "refused: could not read the live set"
    printf '%s\n' "$live_out"
    return 0
  fi
  T="~/.deploy/stage/$DL_UNIT/$DL_COMMIT.tmp-$$"
  # 3
  if [ -n "$DL_BUILD_ARGS" ]; then
    [ "$DL_HOST" = "$DL_BUILD_HOST" ] || dl_die "$DL_RC_REFUSED" "refused: building on $DL_BUILD_HOST for stage host $DL_HOST needs artifact streaming (not in WP-1a)"
    co="~/work/co/$name"
    $DL_GIT branch -f "$branch" "$DL_COMMIT" > /dev/null 2>&1 || dl_die "$DL_RC_REFUSED" "refused: cannot create $branch"
    if ! dl_bb checkout "$name" "$branch"; then
      $DL_GIT branch -D "$branch" > /dev/null 2>&1 || true
      dl_die "$DL_RC_REFUSED" "refused: bb-temper checkout $name failed (exists? remove it: $DL_BB_TEMPER rm $name)"
    fi
    $DL_GIT branch -D "$branch" > /dev/null 2>&1 || true
    [ "$(dl_build_remote "git -C $co rev-parse HEAD")" = "$DL_COMMIT" ] || dl_die "$DL_RC_REFUSED" "refused: $name is not at $DL_COMMIT (remove: $DL_BB_TEMPER rm $name)"
    [ -z "$(dl_build_remote "git -C $co status --porcelain")" ] || dl_die "$DL_RC_REFUSED" "refused: $name is dirty before the build (remove: $DL_BB_TEMPER rm $name)"
    # shellcheck disable=SC2086
    dl_bb build "$name" $DL_BUILD_ARGS "-Dtemper.judge=$DL_COMMIT" || dl_die "$DL_RC_BUILD" "FAILED: bb-temper build $name did not start (remove: $DL_BB_TEMPER rm $name)"
    if ! dl_bb wait "$name"; then
      dl_die "$DL_RC_BUILD" "FAILED: the build of $name failed; the checkout is kept: $DL_BB_TEMPER fetch $name <dir>  /  $DL_BB_TEMPER rm $name"
    fi
    dl_bb report "$name" > "$DL_TMP/BUILD_REPORT" || true
    [ -z "$(dl_build_remote "git -C $co status --porcelain")" ] || dl_die "$DL_RC_REFUSED" "refused: the build wrote tracked files in $name (kept: $DL_BB_TEMPER rm $name)"
    src=$co
  else
    src="$T.src"
    dl_has unit_git_paths || dl_die "$DL_RC_REFUSED" "refused: a git-only unit must define unit_git_paths"
    # shellcheck disable=SC2046
    $DL_GIT archive "$DL_COMMIT" $(unit_git_paths) | dl_remote "mkdir -p $src && tar -x -C $src -f -" || dl_die "$DL_RC_REFUSED" "refused: git archive to the host failed"
  fi
  # 4
  dl_remote "mkdir -p ~/.deploy && chmod 700 ~/.deploy && mkdir -p ~/.deploy/stage/$DL_UNIT && mkdir $T && chmod 755 $T" || dl_die "$DL_RC_REFUSED" "refused: cannot create $T"
  for line in lib.sh "deploy-$DL_UNIT.sh"; do
    $DL_GIT show "$DL_COMMIT:ops/deploy/$line" | dl_remote "cat > $T/$line && chmod 755 $T/$line" || dl_die "$DL_RC_REFUSED" "refused: cannot place $line"
  done
  [ -z "$DL_BUILD_ARGS" ] || { [ ! -s "$DL_TMP/BUILD_REPORT" ] || dl_remote "cat > $T/BUILD_REPORT" < "$DL_TMP/BUILD_REPORT" || true; }
  # 5
  if ! dl_remote "$T/deploy-$DL_UNIT.sh stage-local --commit $DL_COMMIT --src $src --into $T"; then
    dl_remote "rm -rf $T $T.src" || true
    dl_die "$DL_RC_REFUSED" "refused: stage-local refused the staged files (the stage dir was removed)"
  fi
  # 6
  dl_remote "rm -rf $final && mv $T $final && rm -rf $T.src" || dl_die "$DL_RC_REFUSED" "refused: cannot move $T into place"
  [ -z "$DL_BUILD_ARGS" ] || dl_bb rm "$name" || true
  # 7
  printf 'commit %s\n' "$DL_COMMIT"
  printf 'judge %s\n' "$(dl_remote "sed -n 's/^judge=//p' $final/STAGE")"
  dl_remote "cat $final/MANIFEST"
  DL_SET_NEW=$(dl_remote "sed -n 's/^set=//p' $final/STAGE")
  printf 'set %s\n' "$DL_SET_NEW"
  dl_remote "cat $final/SCRIPTS"
  live_out=$(dl_remote "$final/deploy-$DL_UNIT.sh live") || dl_die "$DL_RC_REFUSED" "refused: could not read the live set"
  live_id=$(printf '%s\n' "$live_out" | sed -n 's/^live //p' | head -n 1)
  printf '%s\n' "$live_out"
  cmd="install --commit $DL_COMMIT --expect-current $live_id --expect-new $DL_SET_NEW"
  # shellcheck disable=SC2086
  plan=$(dl_remote "$final/deploy-$DL_UNIT.sh $cmd --dry-run") || {
    printf '%s\n' "$plan"
    dl_die "$DL_RC_REFUSED" "refused: the staged script's install --dry-run refused (the stage dir stays; fix the cause and re-run only the dry-run)"
  }
  printf '%s\n' "$plan" | grep -v '^drift ' || true  # drift is printed once, from the live fetch-back above
  plan_sha=$(printf '%s\n' "$plan" | sed -n 's/^plan-sha256 //p')
  if [ "$DL_HOST" = local ]; then
    owner_line="! $final/deploy-$DL_UNIT.sh $cmd --expect-plan $plan_sha"
  else
    owner_line="! ssh -i $DL_SSH_KEY_TEXT -o BatchMode=yes $DL_HOST '$final/deploy-$DL_UNIT.sh $cmd --expect-plan $plan_sha'"
  fi
  printf 'install %s\n' "$owner_line"
}

# ---------------------------------------------------------------------------------------------------- main

dl_main() {
  local verb flag rc=0
  DL_LIB_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
  [ $# -gt 0 ] || dl_die "$DL_RC_USAGE" "usage: $0 stage|stage-local|install|live ..."
  verb=$1
  shift
  DL_COMMIT= DL_DRY=0 DL_EXP_CUR= DL_EXP_NEW= DL_EXP_PLAN= DL_SRC= DL_INTO=
  while [ $# -gt 0 ]; do
    flag=$1
    case "$flag" in
      --commit|--host|--expect-current|--expect-new|--expect-plan|--src|--into)
        [ $# -ge 2 ] || dl_die "$DL_RC_USAGE" "usage: $flag needs a value"
        case "$flag" in
          --commit) DL_COMMIT=$2 ;;
          --host) DL_HOST=$2 ;;
          --expect-current) DL_EXP_CUR=$2 ;;
          --expect-new) DL_EXP_NEW=$2 ;;
          --expect-plan) DL_EXP_PLAN=$2 ;;
          --src) DL_SRC=$2 ;;
          --into) DL_INTO=$2 ;;
        esac
        shift 2
        ;;
      --dry-run) DL_DRY=1; shift ;;
      *)
        if dl_has unit_flag && unit_flag "$flag"; then shift; else dl_die "$DL_RC_USAGE" "usage: unknown flag $flag"; fi
        ;;
    esac
  done
  case "$DL_UNIT" in ''|[!a-z]*|*[!a-z-]*) dl_die "$DL_RC_USAGE" "usage: DL_UNIT '$DL_UNIT' is not [a-z][a-z-]*" ;; esac
  : "${DL_HEALTH_TIMEOUT:=60}"
  : "${DL_SETTLE:=5}"
  : "${DL_SERVICE:=}"
  : "${DL_BUILD_ARGS:=}"
  if [ -z "${DL_BB_TEMPER:-}" ]; then
    if [ -x "$DL_LIB_DIR/../bb/bb-temper" ]; then DL_BB_TEMPER="$DL_LIB_DIR/../bb/bb-temper"; else DL_BB_TEMPER="$HOME/temper-wt/bb-temper"; fi
  fi
  DL_TMP=$(mktemp -d "${TMPDIR:-/tmp}/dl.XXXXXX")
  trap dl_cleanup EXIT
  case "$verb" in
    stage|stage-local|install)
      dl_is_hex40 "$DL_COMMIT" || dl_die "$DL_RC_USAGE" "usage: --commit needs 40 lowercase hex"
      ;;
  esac
  case "$verb" in
    stage)
      [ -z "$DL_BUILD_ARGS" ] || [ -n "${BB_OWNER:-}" ] || dl_die "$DL_RC_USAGE" "usage: BB_OWNER must be set (bb-temper's rule)"
      dl_resolve_hosts
      BB_OWNER=${BB_OWNER:-none}
      dl_stage || rc=$?
      ;;
    stage-local) dl_stage_local || rc=$? ;;
    live) dl_live || rc=$? ;;
    install)
      if [ "$DL_DRY" = 1 ]; then
        [ -z "$DL_EXP_PLAN" ] || dl_die "$DL_RC_USAGE" "usage: --expect-plan is refused with --dry-run"
      else
        dl_is_hex64 "$DL_EXP_PLAN" || dl_die "$DL_RC_USAGE" "usage: install needs --expect-plan <sha256> (from the dry-run)"
      fi
      [ -n "$DL_EXP_CUR" ] && [ -n "$DL_EXP_NEW" ] || dl_die "$DL_RC_USAGE" "usage: install needs --expect-current and --expect-new"
      dl_install || rc=$?
      ;;
    *) dl_die "$DL_RC_USAGE" "usage: unknown verb $verb" ;;
  esac
  exit "$rc"
}
