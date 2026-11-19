#!/usr/bin/env python3
# bb-modules.py <checkout> <base-ref>  (2026-11-19) -- prints the module set an incremental build must cover, on ONE stdout line:
#   NONE            no changed path at all (the caller builds nothing)
#   FULL <reason>   run the whole reactor; the reason: root-pom | mvn-config:<path> | parent:<pom> | unmapped:<path> | unreadable-reactor:<E> | unresolved-coordinate:<pom>:<text>
#   a/b,c/d         the module paths for an explicit `-pl`, with no -am/-amd; sorted lexicographically by module path (the single sorted() in select(),
#                   the only producer), so `build-parent` sorts after `bdep`
# Changed paths = `git diff --name-only <base>...HEAD` + tracked dirty files + untracked non-ignored files. Each path maps to its nearest
# module (pom.xml directory in the reactor). With C = changed modules: S = am(C u amd(C)): the upstream closure covers the WHOLE set, so a
# dependent's other upstreams are built from source, never resolved from .m2-local (the F4 stale-jar trap).
# FULL (fail toward more) for: a change to the root pom or .mvn/, to any aggregator or parent pom, or to any packaging=pom module (BOM
# imports are not modelled); a path outside every non-root module (unmapped); a pom that cannot be read or parsed; a dependency, plugin or extension whose groupId/artifactId still holds `${` after the pom's own
# <properties> (and project.groupId/artifactId) are substituted: its edge cannot be matched, so it is never dropped silently. S is never empty
# (a changed module is always in its own closure and '.' returns FULL first), so there is no empty-set branch: unreachable code no test can pin is deleted.
# The pom walk and the closures are bb-portgroup.py's (imported, not copied); the extra reactor edges (profiles, plugins, extensions, parent,
# any <dependency> anywhere) are added HERE only, so bb-portgroup.py's decisions are unchanged: edges are a superset of maven's. Exit 0 with one of the lines above; 2 on a bad base or no git.
import importlib.util, os, re, subprocess, sys

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

def clean(x):
    return re.sub(r'[^A-Za-z0-9._/:@+-]', '?', x)

def extra_edges(root):
    # every artifactId maven could order the reactor by, beyond top-level <dependencies>: dependencies anywhere (all profiles, active or not;
    # plugin dependencies; dependencyManagement too: fail toward more), build plugins and extensions, and the parent. Types and classifiers
    # (test-jar, tests) never narrow a match: the artifactId alone is the edge.
    # Returns (artifactIds, unresolved coordinate texts): a groupId or artifactId that keeps `${` after substituting the pom's own <properties>.
    ns = pg.NS
    props = {p.tag[len(ns):]: (p.text or '').strip() for p in root.findall(ns + 'properties/*')}
    props.setdefault('project.artifactId', root.findtext(ns + 'artifactId') or '')
    props.setdefault('project.groupId', root.findtext(ns + 'groupId') or root.findtext(ns + 'parent/' + ns + 'groupId') or '')
    def sub(text):
        text = (text or '').strip()
        for _ in range(10):
            new = re.sub(r'\$\{([^}]*)\}', lambda m: props.get(m.group(1), m.group(0)), text)
            if new == text:
                break
            text = new
        return text
    ids, bad = set(), []
    for t in ('dependency', 'plugin', 'extension'):
        for x in root.iter(ns + t):
            g, a = sub(x.findtext(ns + 'groupId')), sub(x.findtext(ns + 'artifactId'))
            bad += [v for v in (g, a) if '${' in v]
            if a:
                ids.add(a)
    parent = root.findtext(ns + 'parent/' + ns + 'artifactId')
    if parent:
        ids.add(parent)
    return ids, bad

def select(co, base):
    """(selection, reason): selection is NONE, FULL or a -pl list; reason names the cause of a FULL, else ''."""
    paths = changed_paths(co, base)
    if not paths:
        return 'NONE', ''
    facts = {}   # module path -> (has <modules>, packaging, parent artifactId)
    extras = {}  # module path -> artifactIds it may be ordered after
    unresolved = []  # (module path, text)
    def visit(d, r):
        ns = pg.NS
        facts[d] = (r.find(ns + 'modules') is not None or r.find(ns + 'profiles/' + ns + 'profile/' + ns + 'modules') is not None,
                    r.findtext(ns + 'packaging') or 'jar', r.findtext(ns + 'parent/' + ns + 'artifactId'))
        extras[d], bad = extra_edges(r)
        unresolved.extend((d, b) for b in bad)
    try:
        mods = pg.load_reactor(co, visit)
    except Exception as e:
        return 'FULL', 'unreadable-reactor:' + type(e).__name__
    if unresolved:
        d, text = unresolved[0]
        return 'FULL', 'unresolved-coordinate:' + clean(os.path.normpath(d + '/pom.xml') + ':' + text)
    parents = {f[2] for f in facts.values() if f[2]}
    wide = {d for d, f in facts.items() if d == '.' or f[0] or f[1] == 'pom' or mods[d][1] in parents}
    changed = set()
    for p in paths:
        if p == '.mvn' or p.startswith('.mvn/'):
            return 'FULL', 'mvn-config:' + clean(p)
        if p == 'pom.xml':
            return 'FULL', 'root-pom'
        d = os.path.dirname(p) or '.'
        while d != '.' and d not in mods:
            d = os.path.dirname(d) or '.'
        if d == '.':
            return 'FULL', 'unmapped:' + clean(p)
        if d in wide:
            return 'FULL', 'parent:' + clean(d + '/pom.xml')
        changed.add(d)
    by_aid = {aid: d for d, (_, aid, _) in mods.items()}
    ups = pg.upstreams(mods)
    for d, ids in extras.items():
        ups[d] |= {by_aid[i] for i in ids if i in by_aid and by_aid[i] != d}
    down = pg.close(changed, lambda d: [x for x in mods if d in ups[x]])
    s = sorted(x for x in pg.close(down, lambda d: ups[d]) if x != '.')
    return ','.join(s), ''

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print('usage: bb-modules.py <checkout> <base-ref>', file=sys.stderr); sys.exit(2)
    try:
        out, why = select(os.path.abspath(sys.argv[1]), sys.argv[2])
    except Exception as e:
        print('bb-modules: %s' % e, file=sys.stderr); sys.exit(2)
    print('FULL ' + why if out == 'FULL' else out)
