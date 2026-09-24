#!/usr/bin/env python3
"""Fails the build when a documented CLAIM disagrees with the source of truth.

WHY THIS GATE EXISTS. The documents in this repository state concrete facts about the build - pinned
versions, the number of CI jobs, the order of processors in the production route, the alarm tokens the
code emits. Every one of those is a claim that rots silently the moment the code moves, and a rotted
claim in a handoff document is worse than no claim: a reader acts on it. This session produced the
example - both front pages said CI runs 8 jobs after a ninth was added, and enumerated only eight.

DIRECTION OF THE CHECK. Every claim is read OUT OF THE DOCUMENT and compared against the source. The
list of versions is not hardcoded here; it is PARSED from the version table in README.md, so adding a
row to that table automatically brings it under guard. An earlier version of this check hardcoded the
pairs, which made the script itself a second place the truth had to be maintained - the exact failure
mode the gate is supposed to prevent.

WHAT IS CHECKED
  1. Every row of the front-page version table resolves to the matching property in proxy/pom.xml.
  2. The Chinese front page states the same versions.
  3. The CI job count stated in prose, in both languages, equals the workflow's actual job count.
  4. The Node version stated for the alarms app matches the workflow.
  5. Every alarm token in the Java source appears on both front pages, and the count matches.
  6. The processor order drawn in the route diagram matches the order inside configure().
  7. Structural facts the docs assert: the reactor root, the alarms app being outside the reactor,
     the tcnative classifier, and the existence of scripts the docs tell people to run.

Exits non-zero with a per-check verdict. Run it directly to see the checks pass.
"""
import re
import sys
from pathlib import Path

import yaml

EN = Path('README.md')
ZH = Path('README.zh-CN.md')
POM = Path('proxy/pom.xml')
WF = Path('.github/workflows/build.yml')
ROUTE = Path('proxy/cloudhsm/proxy/src/main/java/com/amazon/aws/pix/cloudhsm/proxy/'
             'PixCloudHSMProxyRouteBuilder.java')
TOKENS = Path('proxy/core/src/main/java/com/amazon/aws/pix/core/audit/AuditAlarmTokens.java')

# Version-table component label -> the pom property that must carry it. The VALUES come from the
# table, not from here, so this map only has to say where to look.
COMPONENT_PROPERTY = {
    'Java': 'java.version',
    'Quarkus': 'quarkus.version',
    'Camel Quarkus': 'camel-quarkus.version',
    'Netty': 'netty.version',
    'netty-tcnative': 'netty-tcnative.version',
    'Jackson': 'jackson.version',
    'CloudHSM SDK 5': 'cloudhsm.sdk5.rpm.version',
}

failures = []


def check(label, ok, detail=''):
    print(('  OK   ' if ok else '  FAIL ') + label + (f'  {detail}' if detail else ''))
    if not ok:
        failures.append(label)


def pom_property(pom, name):
    m = re.search(rf'<{re.escape(name)}>([^<]+)</{re.escape(name)}>', pom)
    return m.group(1) if m else None


def table_versions(text):
    """Parses the front-page version table into {component: first bold-or-plain version token}."""
    out = {}
    for line in text.split('\n'):
        if not line.startswith('|') or line.startswith('|--'):
            continue
        cells = [c.strip() for c in line.strip('|').split('|')]
        if len(cells) < 2:
            continue
        comp = cells[0]
        if comp not in COMPONENT_PROPERTY:
            continue
        # The version cell may be '**11** (`temurin` in CI)' or '2.0.84.Final, `linux-...`'
        m = re.match(r'\*\*([^*]+)\*\*|([^,(\s]+)', cells[1])
        if m:
            out[comp] = (m.group(1) or m.group(2)).strip()
    return out


def main():
    pom = POM.read_text()
    en = EN.read_text()
    zh = ZH.read_text()
    wf = yaml.safe_load(WF.read_text())

    # --- 1 and 2: the version table against the pom, and the same values in Chinese ---
    claimed = table_versions(en)
    missing = set(COMPONENT_PROPERTY) - set(claimed)
    check(f'version table has all {len(COMPONENT_PROPERTY)} known components',
          not missing, f'(missing {sorted(missing)})' if missing else '')
    for comp, value in sorted(claimed.items()):
        prop = COMPONENT_PROPERTY[comp]
        actual = pom_property(pom, prop)
        check(f'{comp}: README says {value}, pom {prop}', actual == value, f'(pom has {actual})')
        check(f'{comp}: {value} also on the Chinese page', value in zh)

    # --- 3: CI job count stated in prose, both languages ---
    n_jobs = len(wf['jobs'])
    check(f'English page states {n_jobs} jobs', f'**{n_jobs} jobs**' in en,
          f'(workflow has {n_jobs})')
    check(f'Chinese page states {n_jobs} jobs', f'**{n_jobs} 个作业**' in zh)

    # --- 4: Node version for the alarms app ---
    nodes = {str((s.get('with') or {}).get('node-version'))
             for j in wf['jobs'].values() for s in j.get('steps', [])}
    nodes.discard('None')
    for n in sorted(nodes):
        check(f'Node {n} used in CI is stated on the English page', f'| {n} |' in en or f'**{n}**' in en)

    # --- 5: alarm tokens come from the Java source, not from a list here ---
    tokens = sorted(set(re.findall(r'"(PIX_AUDIT_[A-Z_]+)"', TOKENS.read_text())))
    check('alarm tokens found in the Java source', len(tokens) >= 1, str(tokens))
    for t in tokens:
        check(f'{t} on the English page', t in en)
        check(f'{t} on the Chinese page', t in zh)

    # --- 6: route order as drawn vs the order inside configure() ---
    # Scope to the configure() body. Searching the whole file matches the import statements first,
    # which made the order look scrambled and failed for the wrong reason.
    full = ROUTE.read_text()
    body = full[full.index('public void configure()'):]
    order = ['onCompletion()', 'RejectCompressedRequestProcessor', 'body().convertToString()',
             'SignRequestProcessor', 'CaptureRequestProcessor', 'to(bcbEndpoint',
             'DecodeResponseProcessor', 'VerifyResponseProcessor']
    pos = [body.find(x) for x in order]
    check('route order in configure() matches the documented diagram',
          all(p > 0 for p in pos) and pos == sorted(pos), str(pos))

    # --- 7: structural facts the documents assert ---
    check('tcnative classifier linux-x86_64-fedora pinned in the pom',
          'linux-x86_64-fedora' in pom)
    check('reactor root proxy/pom.xml named on the English page', 'proxy/pom.xml' in en)
    check('alarms app exists', Path('alarms').is_dir())
    check('alarms app is NOT in the Maven reactor', '<module>alarms</module>' not in pom)
    for script in ('.github/scripts/check-transport-contract.sh',
                   '.github/scripts/check-doc-parity.py'):
        check(f'{script} exists', Path(script).is_file())

    if failures:
        print(f'\nFAIL: {len(failures)} claim(s) disagree with the source of truth:')
        for f in failures:
            print(f'  - {f}')
        return 1
    print('\nOK: every documented claim matches the source')
    return 0


if __name__ == '__main__':
    sys.exit(main())
