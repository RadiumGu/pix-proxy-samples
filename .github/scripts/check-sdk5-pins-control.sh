#!/usr/bin/env bash
#
# Negative control for the two SDK 5 gates added to the cloudhsm CI job.
#
# A gate that passes on a correct tree proves nothing on its own - it has to be shown FAILING on a
# broken one, and failing FOR THE RIGHT REASON. Each mutation below is undone from a file copy verified
# with cmp, never with git checkout: git checkout would also silently revert unrelated work in progress.
set -uo pipefail
cd "$(dirname "$0")/../.."

POM=proxy/pom.xml
DOCKERFILE=proxy/cloudhsm/proxy/src/main/docker/Dockerfile
JAR=proxy/cloudhsm/proxy/target/pix-cloudhsm-proxy-1.0.0-runner.jar
WORK=$(mktemp -d)
FAILED=0
CHECKS=0

cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

# ---------------------------------------------------------------- the gates, as functions

# Gate 1: the compiled-against pin and the shipped pin must agree.
gate_pin() {
  local pom_rpm docker_rpm
  pom_rpm=$(sed -n 's#.*<cloudhsm\.sdk5\.rpm\.version>\(.*\)</cloudhsm\.sdk5\.rpm\.version>.*#\1#p' "$POM")
  docker_rpm=$(sed -n 's#^ARG CLOUDHSM_SDK5_RPM_VERSION=\(.*\)$#\1#p' "$DOCKERFILE")
  [ -n "$pom_rpm" ]    || { echo "could not read the SDK 5 rpm pin from $POM"; return 1; }
  [ -n "$docker_rpm" ] || { echo "could not read CLOUDHSM_SDK5_RPM_VERSION from the Dockerfile"; return 1; }
  if [ "$pom_rpm" != "$docker_rpm" ]; then
    echo "SDK 5 pin drift: build compiles against ${pom_rpm} but the image installs ${docker_rpm}"
    return 1
  fi
  return 0
}

# Gate 2: the provider must not be inside the uber-jar.
gate_nobundle() {
  local found
  [ -f "$JAR" ] || { echo "runner jar not built"; return 1; }
  found=$(unzip -l "$JAR" | grep -cE 'com/amazonaws/cloudhsm/|libcloudhsm_jce\.so|META-INF/AMAZONCL' || true)
  if [ "$found" != "0" ]; then
    echo "The CloudHSM JCE provider was bundled into the uber-jar"
    return 1
  fi
  return 0
}

expect_pass() {
  local what="$1" fn="$2" out
  CHECKS=$((CHECKS + 1))
  if out=$("$fn" 2>&1); then
    echo "  $what PASS: gate is green on the unmodified tree"
  else
    echo "  $what FAIL: gate is RED on an unmodified tree - the control cannot test anything"
    printf '    %s\n' "$out"
    FAILED=1
  fi
}

expect_fail() {
  local what="$1" fn="$2" expected="$3" out
  CHECKS=$((CHECKS + 1))
  out=$("$fn" 2>&1) && {
    echo "  $what FAIL: the gate did NOT catch the mutation"
    FAILED=1
    return
  }
  if printf '%s' "$out" | grep -q "$expected"; then
    echo "  $what PASS: caught, named as '$expected'"
  else
    echo "  $what FAIL: caught, but for the wrong reason - expected '$expected'"
    printf '    %s\n' "$out"
    FAILED=1
  fi
}

# ---------------------------------------------------------------- baseline
echo "baseline (both gates must be green before any mutation):"
expect_pass "gate 1 (pin agreement)" gate_pin
expect_pass "gate 2 (not bundled)"   gate_nobundle

# ---------------------------------------------------------------- control 1: pom pin drifts
echo "control 1 - the build's pin is bumped and the Dockerfile is not:"
cp "$POM" "$WORK/pom.bak"
sed -i 's#<cloudhsm\.sdk5\.rpm\.version>5\.18\.0-1</cloudhsm\.sdk5\.rpm\.version>#<cloudhsm.sdk5.rpm.version>5.19.0-1</cloudhsm.sdk5.rpm.version>#' "$POM"
expect_fail "control 1" gate_pin "pin drift"
cp "$WORK/pom.bak" "$POM"
cmp -s "$WORK/pom.bak" "$POM" || { echo "  RESTORE FAILED for $POM"; FAILED=1; }

# ---------------------------------------------------------------- control 2: Dockerfile pin drifts
echo "control 2 - the image's pin is bumped and the build is not:"
cp "$DOCKERFILE" "$WORK/docker.bak"
sed -i 's#^ARG CLOUDHSM_SDK5_RPM_VERSION=5\.18\.0-1$#ARG CLOUDHSM_SDK5_RPM_VERSION=5.19.0-1#' "$DOCKERFILE"
expect_fail "control 2" gate_pin "pin drift"
cp "$WORK/docker.bak" "$DOCKERFILE"
cmp -s "$WORK/docker.bak" "$DOCKERFILE" || { echo "  RESTORE FAILED for $DOCKERFILE"; FAILED=1; }

# ---------------------------------------------------------------- control 3: the pin becomes unreadable
#
# Not the same failure as drift. If the property is renamed or reformatted, sed returns an EMPTY string
# and a naive comparison of "" against "" would COMPARE EQUAL and pass - a gate that silently stops
# checking. This control exists because that is the more dangerous of the two failures.
echo "control 3 - the pin is renamed so the gate cannot read it:"
cp "$POM" "$WORK/pom2.bak"
sed -i 's#cloudhsm\.sdk5\.rpm\.version#cloudhsm.sdk5.rpm.versionRENAMED#g' "$POM"
expect_fail "control 3" gate_pin "could not read"
cp "$WORK/pom2.bak" "$POM"
cmp -s "$WORK/pom2.bak" "$POM" || { echo "  RESTORE FAILED for $POM"; FAILED=1; }

# ---------------------------------------------------------------- control 4: the provider gets bundled
#
# Injects a provider-looking entry into a COPY of the runner jar rather than rebuilding with compile
# scope, because the point is to test the gate's detection, not Maven's scope handling. The entry name
# is one the gate greps for.
echo "control 4 - a provider class is present inside the uber-jar:"
if [ -f "$JAR" ]; then
  cp "$JAR" "$WORK/jar.bak"
  mkdir -p "$WORK/inject/com/amazonaws/cloudhsm/jce/provider"
  echo "not a real class" > "$WORK/inject/com/amazonaws/cloudhsm/jce/provider/CloudHsmProvider.class"
  ( cd "$WORK/inject" && zip -q -r "$OLDPWD/$JAR" com ) || { echo "  could not inject"; FAILED=1; }
  expect_fail "control 4" gate_nobundle "bundled into the uber-jar"
  cp "$WORK/jar.bak" "$JAR"
  cmp -s "$WORK/jar.bak" "$JAR" || { echo "  RESTORE FAILED for $JAR"; FAILED=1; }
else
  echo "  SKIPPED: runner jar not built - run 'mvn -f proxy/pom.xml -pl core,cloudhsm/jce5,cloudhsm/proxy package -DskipTests' first"
  echo "  A SKIPPED control is not a passed control."
  FAILED=1
fi

# ---------------------------------------------------------------- restored state
echo "after restore (both gates must be green again):"
expect_pass "restored gate 1" gate_pin
expect_pass "restored gate 2" gate_nobundle

if [ "$FAILED" -ne 0 ]; then
  echo "NEGATIVE CONTROL FAILED - the SDK 5 gates do not actually guard what they claim"
  exit 1
fi
echo "OK: both SDK 5 gates catch drift and bundling (${CHECKS} checks), each for the right reason"
