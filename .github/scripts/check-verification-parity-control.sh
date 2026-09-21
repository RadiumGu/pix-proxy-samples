#!/usr/bin/env bash
# NEGATIVE CONTROL for check-verification-parity.py.
#
# A gate that passes proves nothing. This script perturbs the English rendering in three ways that
# each represent a REAL drift mode, and requires the gate to fail on each one - and to name the
# right cause, not merely to exit non-zero. Then it restores the file and requires the gate to pass
# again, so a control that "succeeds" by leaving the tree broken is caught.
#
# Restoration is from a FILE COPY verified with cmp, never `git checkout --`: this repository has
# been bitten by using git to undo a deliberate mutation, which silently reverts unrelated work in
# the same file.
#
# ONE TRAP WORTH RECORDING, because the first version of this script fell into it. Do NOT write
#     if python3 "$GATE" | grep -q 'expected message'; then
# under `set -o pipefail`. The gate is SUPPOSED to exit non-zero here, and pipefail propagates that
# to the pipeline, so the `if` reads the gate's intended failure as the test failing. The first run
# reported all three controls FAIL while the gate had in fact caught all three and named each one
# correctly - a broken control masquerading as a broken gate. Capture the output first, then match
# against the variable.
set -uo pipefail

GATE=.github/scripts/check-verification-parity.py
TARGET=VERIFICATION.en.md
BACKUP="$(mktemp)"
FAILED=0

cp "$TARGET" "$BACKUP"

restore() {
  cp "$BACKUP" "$TARGET"
  if ! cmp -s "$TARGET" "$BACKUP"; then
    echo "FATAL: could not restore $TARGET from the backup copy"
    exit 1
  fi
}
trap 'restore; rm -f "$BACKUP"' EXIT

# Runs the gate and requires it to fail WITH the expected diagnosis.
expect_failure() {
  local what="$1" expected="$2" out
  out="$(python3 "$GATE" 2>&1)"
  if [ -n "$out" ] && printf '%s' "$out" | grep -q "$expected"; then
    echo "$what PASS: caught, and named as '$expected'"
  else
    echo "$what FAIL: the gate did not report '$expected'"
    printf '%s\n' "$out" | head -5
    FAILED=1
  fi
}

# The gate must pass before any perturbation, or the controls below prove nothing.
if ! python3 "$GATE" >/dev/null 2>&1; then
  echo "FATAL: the gate does not pass on the unmodified tree - fix that before trusting a control"
  exit 1
fi
echo "baseline: gate passes on the unmodified tree"

# ---- control 1: a measured value silently changed in one copy only ----
# The drift that matters most: a measured latency was recorded once, and a copy carrying a different
# figure is wrong in a way no reader can detect by reading either file alone.
python3 - <<'PY'
import re, sys
p = 'VERIFICATION.en.md'
t = open(p).read()
m = re.search(r'\b(\d+\.\d)\b', t)
if not m:
    sys.exit('control 1 found no decimal measurement to perturb - update the control')
orig = m.group(1)
bumped = orig[:-1] + str((int(orig[-1]) + 1) % 10)
open(p, 'w').write(t[:m.start(1)] + bumped + t[m.end(1):])
print(f'  perturbed a measured value: {orig} -> {bumped}')
PY
expect_failure "control 1" "measured numbers diverge"
restore

# ---- control 2: a command inside a code block altered ----
# Commands are evidence. A reader who copies one from the English file must run the same thing the
# original recorded.
python3 - <<'PY'
import sys
p = 'VERIFICATION.en.md'
t = open(p).read()
old = 'mvn -f proxy/pom.xml -pl core test'
if old not in t:
    sys.exit('control 2 anchor missing - update the control')
open(p, 'w').write(t.replace(old, 'mvn -f proxy/pom.xml -pl core verify', 1))
print('  perturbed a command: core test -> core verify')
PY
expect_failure "control 2" "differs outside comments"
restore

# ---- control 3: a whole section dropped from one copy ----
python3 - <<'PY'
import re, sys
p = 'VERIFICATION.en.md'
lines = open(p).read().split('\n')
idx = [i for i, l in enumerate(lines) if re.match(r'^### ', l)]
if len(idx) < 2:
    sys.exit('control 3 needs at least two H3 headings - update the control')
del lines[idx[0]:idx[1]]
open(p, 'w').write('\n'.join(lines))
print(f'  dropped a section: {idx[1] - idx[0]} lines')
PY
expect_failure "control 3" "heading count differs"
restore

# ---- the tree must be clean again, or a passing control means nothing ----
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
echo "OK: all three drift modes are caught, each for the right reason"
