-- temper M2: the known-failure registry (spec §9, m2-design §4.2). Applied once by MigrationRunner on top of every
-- earlier version (V5 is fix-supersede-key's, V7 is #26's; the number is fixed at merge); never edit after release.
--
-- Rows are never deleted: an entry is expired (expired_at set once, the first expiry kept), as refusals are. One
-- active entry per test_id (known_active), so a failure maps to one issue; an expired entry does not block a new one.
-- Finishing reads the active rows inside the completing transaction, after the job lock, and marks the record once
-- (results is write-once): no existing row changes here.
--
-- The CHECKs are KnownEntry's rules, so every row the table holds parses back and `known list` never meets a row it
-- cannot read or expire. test_id is `class` or `class#name` (the report's classname, nested `Outer$Inner`). The case
-- name's 1..512 bound is a length check, not m2-design §4.2's `#.{1,512}`: Postgres refuses a regex repetition count
-- above 255 when the CHECK first runs, which would fail every insert. A class has no `#`, so the first `#` starts the
-- case name; an id without one (position 0) has no case name and no bound. reason and until_text must hold a
-- character that Java's `Character.isWhitespace` (KnownEntry's `present`) does not count as blank. The class lists
-- exactly that set, spelled out rather than Postgres's locale-dependent [:space:]: U+0009..U+000D, U+001C..U+0020,
-- and the space, line and paragraph separators except the non-breaking U+00A0, U+2007, U+202F. KnownStoreIT derives
-- the set from `isWhitespace` over every code point and checks the table against it. created_by is the OwnerTag
-- alphabet.
CREATE TABLE known (
  id bigserial PRIMARY KEY,
  test_id text NOT NULL CHECK (test_id ~ '^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*(#.+)?$'
                               AND (position('#' in test_id) = 0
                                    OR length(test_id) - position('#' in test_id) <= 512)),
  issue int NOT NULL CHECK (issue > 0),
  reason text NOT NULL CHECK (reason ~ '[^\x09-\x0d\x1c-\x20\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]'),
  until_text text NOT NULL CHECK (until_text ~ '[^\x09-\x0d\x1c-\x20\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]'),
  created_by text NOT NULL CHECK (created_by ~ '^[A-Za-z0-9_]{1,64}$'),
  created_at timestamptz NOT NULL DEFAULT now(),
  expired_at timestamptz);
CREATE UNIQUE INDEX known_active ON known (test_id) WHERE expired_at IS NULL;
