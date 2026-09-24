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
TARGETS=("VERIFICATION.en.md" "README-CloudHSM.zh-CN.md")

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
expect_failure() {
  local what="$1" expected="$2" out
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

  # ---- control 2: a command inside a code block altered ----
  # Commands are evidence. A reader who copies one from the translation must run the same thing the
  # authoritative copy recorded.
  TARGET_FILE="$T" python3 - <<'PY'
import os, re, sys
p = os.environ['TARGET_FILE']
lines = open(p).read().split('\n')
in_code = False
for i, l in enumerate(lines):
    if l.startswith('```'):
        in_code = not in_code
        continue
    # A substantive command line: not a comment, not blank.
    if in_code and l.strip() and not l.lstrip().startswith(('#', '//')):
        lines[i] = l + ' --perturbed-by-the-negative-control'
        print(f'  perturbed a command on line {i + 1}')
        break
else:
    sys.exit(f'control 2 found no command line in {p} - update the control')
open(p, 'w').write('\n'.join(lines))
PY
  expect_failure "control 2 (command)" "differs outside comments"
  restore_one "$T" "$B"

  # ---- control 3: a whole section dropped from one copy ----
  TARGET_FILE="$T" python3 - <<'PY'
import os, re, sys
p = os.environ['TARGET_FILE']
lines = open(p).read().split('\n')
idx = [i for i, l in enumerate(lines) if re.match(r'^### ', l)]
if len(idx) < 2:
    sys.exit(f'control 3 needs two H3 headings in {p} - update the control')
n = idx[1] - idx[0]
del lines[idx[0]:idx[1]]
open(p, 'w').write('\n'.join(lines))
print(f'  dropped a section: {n} lines')
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
echo "OK: all four drift modes are caught on both pairs, each for the right reason"
