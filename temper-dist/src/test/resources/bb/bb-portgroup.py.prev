#!/usr/bin/env python3
# bb-portgroup.py <modules-file> <checkout> [mvn args...]  (2026-09-29) -- prints "1 <reason>" or "0 <reason>".
# Decides PORT-GROUP membership: 1 when the maven run will execute tests of a module that binds FIXED ports (the
# module paths listed in <modules-file>, one per line). Derived from the checkout's OWN poms, so it follows the SHA:
# -pl/--projects (paths, [groupId]:artifactId, !/- exclusions), -am (upstream closure) and -amd (downstream closure).
# No -pl = the whole reactor = member. -DskipTests / -Dmaven.test.skip(=true) = no tests run = not a member.
# Anything it cannot model (-f, unresolvable selector, unparsable pom) answers 1: a wrong 1 costs only waiting,
# a wrong 0 costs a void red.
import os, sys, xml.etree.ElementTree as ET

def main(mods_file, co, args):
    try:
        port = [l.strip().strip('/') for l in open(mods_file) if l.strip() and not l.lstrip().startswith('#')]
    except OSError:
        port = ['aether/ember', 'aether/forge/forge-tests']
    try:
        cfg = [l.strip() for l in open(os.path.join(co, '.mvn', 'maven.config')) if l.strip()]
    except OSError:
        cfg = []
    argv = cfg + args
    sel, am, amd = [], False, False
    i = 0
    while i < len(argv):
        a = argv[i]
        if a in ('-pl', '--projects'):
            i += 1
            sel += argv[i].split(',') if i < len(argv) else []
        elif a.startswith('--projects='):
            sel += a.split('=', 1)[1].split(',')
        elif a.startswith('-pl') and a != '-pl':
            sel += a[3:].lstrip('=').split(',')
        elif a in ('-am', '--also-make'):
            am = True
        elif a in ('-amd', '--also-make-dependents'):
            amd = True
        elif a in ('-f', '--file') or a.startswith('--file='):
            return '1 -f/--file is not modelled'
        elif a in ('-DskipTests', '-DskipTests=true', '-Dmaven.test.skip', '-Dmaven.test.skip=true'):
            return '0 tests skipped (%s)' % a
        i += 1
    sel = [s.strip().lstrip('?') for s in sel if s.strip()]
    inc = [s for s in sel if s[0] not in '!-']
    exc = [s[1:] for s in sel if s[0] in '!-']
    if not inc:
        return '1 whole reactor (no -pl inclusion)'

    ns = '{http://maven.apache.org/POM/4.0.0}'
    mods = {}   # path -> (groupId, artifactId, [dep artifactIds])
    def walk(d):
        r = ET.parse(os.path.join(co, d, 'pom.xml')).getroot()
        aid = r.findtext(ns + 'artifactId')
        gid = r.findtext(ns + 'groupId') or r.findtext(ns + 'parent/' + ns + 'groupId')
        deps = [x.findtext(ns + 'artifactId') for x in r.findall(ns + 'dependencies/' + ns + 'dependency')]
        mods[d] = (gid, aid, deps)
        for m in r.findall(ns + 'modules/' + ns + 'module') + r.findall(ns + 'profiles/' + ns + 'profile/' + ns + 'modules/' + ns + 'module'):
            walk(os.path.normpath(os.path.join(d, m.text.strip())))
    try:
        walk('.')
    except Exception as e:
        return '1 could not read the reactor (%s)' % type(e).__name__

    def resolve(s):
        s = os.path.normpath(s.rstrip('/'))
        if s in mods:
            return s
        if ':' in s:
            g, a = s.split(':', 1)
            hit = [d for d, (gid, aid, _) in mods.items() if aid == a and (not g or g == gid)]
            return hit[0] if len(hit) == 1 else None
        return None
    chosen = set()
    for s in inc:
        d = resolve(s)
        if d is None:
            return '1 cannot resolve -pl selector %r' % s
        chosen.add(d)
    by_aid = {aid: d for d, (_, aid, _) in mods.items()}
    ups = {d: {by_aid[x] for x in deps if x in by_aid} for d, (_, _, deps) in mods.items()}
    def close(start, edges):
        out, todo = set(start), list(start)
        while todo:
            for n in edges(todo.pop()):
                if n not in out:
                    out.add(n); todo.append(n)
        return out
    final = set(chosen)
    if am:
        final |= close(chosen, lambda d: ups[d])
    if amd:
        final |= close(chosen, lambda d: [x for x in mods if d in ups[x]])
    if not am and not amd:
        final -= {resolve(e) for e in exc}
    hit = sorted(final & set(port))
    if hit:
        return '1 selects %s' % ','.join(hit)
    return '0 selects none of %s' % ','.join(port)

if __name__ == '__main__':
    try:
        print(main(sys.argv[1], sys.argv[2], sys.argv[3:]))
    except Exception as e:
        print('1 classifier error (%s)' % type(e).__name__)
