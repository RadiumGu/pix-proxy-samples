#!/usr/bin/env bash
# NEGATIVE CONTROL for check-doc-parity.py.
#
# A gate that passes proves nothing. This script perturbs EACH guarded pair in four ways that each
# represent a real drift mode, and requires the gate to fail on each one AND to name the right cause -
# not merely to exit non-zero. Then it restores and requires the gate to pass again, so a control that
# "succeeds" by leaving the tree broken is caught.
#
# Both pairs are controlled, not just the first. The pairs are checked by the same code but with
# different options (VERIFICATION has a bilingual double lead-in, README-CloudHSM does not), and an
# option that is only ever exercised on one pair is an option whose behaviour on the other is
# untested.
#
# Restoration is from a FILE COPY verified with cmp, never `git checkout --`: this repository has been
# bitten by using git to undo a deliberate mutation, which silently reverts unrelated work in the same
# file.
#
# ONE TRAP WORTH RECORDING, because the first version of this script fell into it. Do NOT write
#     if python3 "$GATE" | grep -q 'expected message'; then
# under `set -o pipefail`. The gate is SUPPOSED to exit non-zero here, and pipefail propagates that to
# the pipeline, so the `if` reads the gate's intended failure as the test failing. That first run
# reported every control FAIL while the gate had in fact caught them all and named each correctly - a
# broken control masquerading as a broken gate. Capture the output first, then match the variable.
set -uo pipefail

GATE=.github/scripts/check-doc-parity.py
FAILED=0
declare -a BACKUPS=()

# Each guarded translation, perturbed in turn.
# Each guarded translation, perturbed in turn. ALL of them: the pairs share code but take different
# options, and an option exercised on only one pair is an option whose behaviour on the others is
# untested.
TARGETS=("VERIFICATION.en.md"
         "README-CloudHSM.zh-CN.md"
         "CLOUDHSM_BCB_V2_HANDOFF.zh-CN.md"
         "README.zh-CN.md"
         "CLOUDHSM_ADD_HSM_FAQ.zh-CN.md"
         "PIX_CLOUDHSM_ASSESSMENT.zh-CN.md")

cleanup() {
  local i=0
  for t in "${TARGETS[@]}"; do
    if [ -n "${BACKUPS[$i]:-}" ] && [ -f "${BACKUPS[$i]}" ]; then
      cp "${BACKUPS[$i]}" "$t"
      cmp -s "${BACKUPS[$i]}" "$t" || { echo "FATAL: could not restore $t"; exit 1; }
      rm -f "${BACKUPS[$i]}"
    fi
    i=$((i + 1))
  done
}
trap cleanup EXIT

for i in "${!TARGETS[@]}"; do
  BACKUPS[$i]="$(mktemp)"
  cp "${TARGETS[$i]}" "${BACKUPS[$i]}"
done

restore_one() {
  local t="$1" b="$2"
  cp "$b" "$t"
  cmp -s "$b" "$t" || { echo "FATAL: could not restore $t from its backup copy"; exit 1; }
}

# Runs the gate and requires it to fail WITH the expected diagnosis.
# CHECKS counts every perturbation actually attempted, so the summary line reports a measured number
# instead of a hardcoded one. The previous summary said "on both pairs" and stayed that way after the
# control was generalised to every pair - it claimed less coverage than it had, which is the same class
# of drift this control exists to catch.
CHECKS=0
expect_failure() {
  local what="$1" expected="$2" out
  CHECKS=$((CHECKS + 1))
  out="$(python3 "$GATE" 2>&1)"
  if [ -n "$out" ] && printf '%s' "$out" | grep -q "$expected"; then
    echo "  $what PASS: caught, named as '$expected'"
  else
    echo "  $what FAIL: the gate did not report '$expected'"
    printf '%s\n' "$out" | head -6
    FAILED=1
  fi
}

if ! python3 "$GATE" >/dev/null 2>&1; then
  echo "FATAL: the gate does not pass on the unmodified tree - fix that before trusting a control"
  exit 1
fi
echo "baseline: gate passes on the unmodified tree"

for i in "${!TARGETS[@]}"; do
  T="${TARGETS[$i]}"
  B="${BACKUPS[$i]}"
  echo "=== controls for $T ==="

  # ---- control 1: a measured value silently changed in one copy only ----
  # The drift that matters most: a measurement was recorded once, and a copy carrying a different
  # figure is wrong in a way no reader can detect by reading either file alone.
  TARGET_FILE="$T" python3 - <<'PY'
import os, re, sys
p = os.environ['TARGET_FILE']
t = open(p).read()
m = re.search(r'(?<![\d.])(\d+\.\d)(?![\d.])', t)
if not m:
    sys.exit(f'control 1 found no decimal measurement in {p} - update the control')
orig = m.group(1)
bumped = orig[:-1] + str((int(orig[-1]) + 1) % 10)
open(p, 'w').write(t[:m.start(1)] + bumped + t[m.end(1):])
print(f'  perturbed a measured value: {orig} -> {bumped}')
PY
  expect_failure "control 1 (measured value)" "measured values diverge"
  restore_one "$T" "$B"

  # ---- control 2: a command inside an EVIDENCE code block altered ----
  # Commands are evidence. A reader who copies one from the translation must run the same thing the
  # authoritative copy recorded.
  #
  # Two things this control has to get right, both learned by getting them wrong:
  #
  # It must pick a TAGGED fence. An untagged or `text` fence is a diagram, which the gate deliberately
  # compares only on its numbering - so perturbing a line there proves nothing about the strict check
  # this control exercises. The first version picked the first line in ANY fence and reported FAIL on
  # every pair once diagram handling was added: the control was testing the wrong thing.
  #
  # It must perturb the COMMAND, not append at end of line. Appending lands after any trailing
  # comment, and the gate strips trailing comments before comparing - so the perturbation was invisible
  # and the control read as the gate failing. Insert before the comment instead.
  TARGET_FILE="$T" python3 - <<'PY'
import os, re, sys
p = os.environ['TARGET_FILE']
lines = open(p).read().split('\n')
TRAILING = re.compile(r'\s{2,}(#|//)\s')
tag, in_code = '', False
for i, l in enumerate(lines):
    if l.startswith('```'):
        if not in_code:
            tag = l[3:].strip().lower()
        in_code = not in_code
        continue
    if in_code and tag not in ('', 'text', 'txt') and l.strip() \
            and not l.lstrip().startswith(('#', '//')):
        m = TRAILING.search(l)
        if m:
            # Insert INSIDE the command, before the trailing comment the gate strips.
            lines[i] = l[:m.start()] + ' --perturbed-by-the-negative-control' + l[m.start():]
            where = 'before the trailing comment'
        else:
            lines[i] = l + ' --perturbed-by-the-negative-control'
            where = 'at end of line'
        print(f'  perturbed a command on line {i + 1} of a {tag!r} block, {where}')
        break
else:
    sys.exit(f'control 2 found no evidence-fence command line in {p} - update the control')
open(p, 'w').write('\n'.join(lines))
PY
  expect_failure "control 2 (command)" "differs outside comments"
  restore_one "$T" "$B"

  # ---- control 3: a whole section dropped from one copy ----
  # The heading level is CHOSEN from the file rather than hardcoded. An earlier version looked for
  # `### ` and simply exited on the customer FAQ, which has only H1 and H2 - so the control silently
  # did not run at all while appearing to be present. A control that can skip itself is worse than no
  # control, because it reports nothing while guarding nothing.
  TARGET_FILE="$T" python3 - <<'PY'
import os, re, sys
p = os.environ['TARGET_FILE']
lines = open(p).read().split('\n')
in_code = False
by_level = {}
for i, l in enumerate(lines):
    if l.startswith('```'):
        in_code = not in_code
        continue
    if in_code:
        continue
    m = re.match(r'^(#{2,6}) ', l)
    if m:
        by_level.setdefault(len(m.group(1)), []).append(i)
# Deepest level that has at least two headings, so removing one is a clean section drop.
usable = [lv for lv, idx in sorted(by_level.items(), reverse=True) if len(idx) >= 2]
if not usable:
    sys.exit(f'control 3 found no level with two headings in {p} - update the control')
lv = usable[0]
idx = by_level[lv]
n = idx[1] - idx[0]
del lines[idx[0]:idx[1]]
open(p, 'w').write('\n'.join(lines))
print(f'  dropped an h{lv} section: {n} lines')
PY
  expect_failure "control 3 (dropped section)" "heading count differs"
  restore_one "$T" "$B"

  # ---- control 4: a link quietly repointed ----
  TARGET_FILE="$T" python3 - <<'PY'
import os, re, sys
p = os.environ['TARGET_FILE']
t = open(p).read()
m = re.search(r'\]\((?!#)([^)]+\.md)\)', t)
if not m:
    sys.exit(f'control 4 found no file link in {p} - update the control')
open(p, 'w').write(t[:m.start(1)] + 'SOMEWHERE-ELSE.md' + t[m.end(1):])
print(f'  repointed a link: {m.group(1)} -> SOMEWHERE-ELSE.md')
PY
  # The perturbation lands wherever the first file link happens to be - body or lead-in - so
  # accept either diagnosis. Both are the link check firing; which half reported it is not the point.
  expect_failure "control 4 (link target)" "file link targets differ"
  restore_one "$T" "$B"
done

if python3 "$GATE" >/dev/null 2>&1; then
  echo "restored: gate passes again on the unmodified tree"
else
  echo "FAIL: the tree was left broken after the controls"
  FAILED=1
fi

if [ "$FAILED" -ne 0 ]; then
  echo "NEGATIVE CONTROL FAILED - the parity gate does not actually guard anything"
  exit 1
fi
echo "OK: all four drift modes are caught on every pair ($CHECKS checks), each for the right reason"
