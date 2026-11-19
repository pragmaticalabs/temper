#!/usr/bin/env python3
# bb-modules.py <checkout> <base-ref>  (2026-11-19) -- prints the module set an incremental build must cover, on ONE stdout line:
#   NONE            no changed path at all (the caller builds nothing)
#   FULL            run the whole reactor (reason on stderr)
#   a/b,c/d         the module paths for an explicit `-pl`, with no -am/-amd
# Changed paths = `git diff --name-only <base>...HEAD` + tracked dirty files + untracked non-ignored files. Each path maps to its nearest
# module (pom.xml directory in the reactor). With C = changed modules: S = am(C u amd(C)): the upstream closure covers the WHOLE set, so a
# dependent's other upstreams are built from source, never resolved from .m2-local (the F4 stale-jar trap).
# FULL (fail toward more) for: a change to the root pom or .mvn/, to any aggregator or parent pom, or to any packaging=pom module (BOM
# imports are not modelled); a path outside every non-root module; a pom that cannot be read or parsed; an empty S with changes.
# The pom walk and the closures are bb-portgroup.py's (imported, not copied). Exit 0 with one of the lines above; 2 on a bad base or no git.
import importlib.util, os, subprocess, sys

sys.dont_write_bytecode = True   # importing bb-portgroup.py must not drop a __pycache__ beside the installed tools
_here = os.path.dirname(os.path.realpath(__file__))
_spec = importlib.util.spec_from_file_location('bb_portgroup', os.path.join(_here, 'bb-portgroup.py'))
pg = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pg)

def git(co, *args):
    p = subprocess.run(['git', '-C', co] + list(args), capture_output=True)
    if p.returncode != 0:
        raise RuntimeError('git %s: %s' % (' '.join(args), p.stderr.decode(errors='replace').strip()))
    return [x.decode() for x in p.stdout.split(b'\0') if x]

def changed_paths(co, base):
    out = set(git(co, 'diff', '--name-only', '--no-renames', '-z', base + '...HEAD'))
    out |= set(git(co, 'diff', '--name-only', '--no-renames', '-z', 'HEAD'))
    out |= set(git(co, 'ls-files', '--others', '--exclude-standard', '-z'))
    return sorted(out)

def select(co, base):
    paths = changed_paths(co, base)
    if not paths:
        return 'NONE', ''
    facts = {}   # module path -> (has <modules>, packaging, parent artifactId)
    def visit(d, r):
        ns = pg.NS
        facts[d] = (r.find(ns + 'modules') is not None or r.find(ns + 'profiles/' + ns + 'profile/' + ns + 'modules') is not None,
                    r.findtext(ns + 'packaging') or 'jar', r.findtext(ns + 'parent/' + ns + 'artifactId'))
    try:
        mods = pg.load_reactor(co, visit)
    except Exception as e:
        return 'FULL', 'cannot read the reactor (%s)' % type(e).__name__
    parents = {f[2] for f in facts.values() if f[2]}
    wide = {d for d, f in facts.items() if d == '.' or f[0] or f[1] == 'pom' or mods[d][1] in parents}
    changed = set()
    for p in paths:
        if p == '.mvn' or p.startswith('.mvn/'):
            return 'FULL', 'change under .mvn/ (%s)' % p
        d = os.path.dirname(p) or '.'
        while d != '.' and d not in mods:
            d = os.path.dirname(d) or '.'
        if d == '.':
            return 'FULL', 'path outside every module (%s)' % p
        if d in wide:
            return 'FULL', 'change in aggregator/parent/pom-packaging module %s (%s)' % (d, p)
        changed.add(d)
    ups = pg.upstreams(mods)
    down = pg.close(changed, lambda d: [x for x in mods if d in ups[x]])
    s = sorted(x for x in pg.close(down, lambda d: ups[d]) if x != '.')
    if not s:
        return 'FULL', 'empty module set for a non-empty change'
    return ','.join(s), ''

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print('usage: bb-modules.py <checkout> <base-ref>', file=sys.stderr); sys.exit(2)
    try:
        out, why = select(os.path.abspath(sys.argv[1]), sys.argv[2])
    except Exception as e:
        print('bb-modules: %s' % e, file=sys.stderr); sys.exit(2)
    if why:
        print('bb-modules: FULL: %s' % why, file=sys.stderr)
    print(out)
