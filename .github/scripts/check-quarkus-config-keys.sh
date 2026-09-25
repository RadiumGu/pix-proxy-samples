#!/usr/bin/env bash
#
# Fails the build if Quarkus reports ANY unrecognized configuration key.
#
# WHY THIS EXISTS, and it is not hypothetical - it happened during the 1.7 -> 2.13 upgrade.
#
# Quarkus does not fail on a configuration key it does not know. It logs a WARNING and carries on:
#
#   [WARNING] [io.quarkus.config] Unrecognized configuration key "quarkus.package.uber-jar" was
#             provided; it will be ignored
#   [WARNING] [io.quarkus.config] Unrecognized configuration key
#             "quarkus.camel.main.routes-discovery.enabled" was provided; it will be ignored
#   [INFO] [io.quarkus.deployment.QuarkusAugmentor] Quarkus augmentation completed in 1039ms
#   BUILD SUCCESS
#
# Exit code 0. Both keys had been renamed in 2.x, and both were silently inert:
#
#   - quarkus.package.uber-jar stopped producing an uber-jar. The build emitted a quarkus-app/
#     directory instead of the *-runner.jar the Dockerfile copies. That one would have failed later,
#     at image build time, pointing at the Dockerfile rather than at the cause.
#   - quarkus.camel.main.routes-discovery.enabled stopped disabling route discovery. Nothing would
#     have failed at all - the application would just have started discovering routes again, in
#     production, with a green build behind it.
#
# The second is the reason this gate is worth its weight: a config key that fails OPEN changes runtime
# behaviour with no artifact to notice. A build that "succeeds" while ignoring instructions is worse
# than one that breaks.
#
# This gate is also what makes the NEXT leg (2.13 -> 3.x) safe to attempt, since that is another round
# of renamed keys.
set -uo pipefail
cd "$(dirname "$0")/../.."

LOG=${1:-}
if [ -z "$LOG" ]; then
  echo "usage: $0 <maven-build-log>" >&2
  echo "  Run a build first, e.g.:" >&2
  echo "    mvn -B -f proxy/pom.xml clean package -DskipTests > /tmp/build.log 2>&1" >&2
  echo "    bash $0 /tmp/build.log" >&2
  exit 2
fi
[ -f "$LOG" ] || { echo "ERROR: no such log file: $LOG" >&2; exit 2; }

# The log must actually contain a Quarkus augmentation, or this gate would pass on a log where Quarkus
# never ran - a gate that can silently skip itself is worse than no gate. Learned the hard way on a
# different control in this repository.
if ! grep -q 'QuarkusAugmentor' "$LOG"; then
  echo "ERROR: $LOG contains no Quarkus augmentation, so this gate would be checking nothing." >&2
  echo "       Build the Quarkus modules before running it:" >&2
  echo "         mvn -B -f proxy/pom.xml clean package -DskipTests > \"$LOG\" 2>&1" >&2
  exit 1
fi

MODULES=$(grep -c 'QuarkusAugmentor.*augmentation completed' "$LOG" || true)
echo "Quarkus augmentations found in the log: ${MODULES}"

UNRECOGNIZED=$(grep -oE 'Unrecognized configuration key "[^"]+"' "$LOG" | sort -u || true)

# DEPRECATED is checked separately from UNRECOGNIZED, because they are different strings and the first
# version of this gate only looked for the second one.
#
# That gap was found rather than foreseen. Research said quarkus.package.type is renamed to
# quarkus.package.jar.type in Quarkus 3.x, with a DEPRECATION warning rather than an "unrecognized" one -
# so a renamed-but-still-working key would have sailed past this gate while being on a removal path.
# (Measured on 3.33.3.3 the key is in fact still current: neither warning appears and the uber-jar is
# still produced. The gap in the gate was real even though that particular claim was not.)
#
# A deprecated key still WORKS, so this is reported as a failure that names the key rather than a vague
# warning: the whole point is to act on it while it works, not after it stops.
DEPRECATED=$(grep -oE "'quarkus\.[a-z0-9.\-]+' has been deprecated[^\"]*" "$LOG" | sort -u || true)
DEPRECATED="$DEPRECATED$(grep -oE 'Configuration key "quarkus\.[a-z0-9.\-]+" is deprecated[^"]*' "$LOG" | sort -u || true)"

if [ -n "$UNRECOGNIZED" ]; then
  echo "ERROR: Quarkus ignored one or more configuration keys. It does NOT fail on these - it warns" >&2
  echo "       and continues, so the build reports success while the instruction has no effect." >&2
  echo "" >&2
  printf '%s\n' "$UNRECOGNIZED" | sed 's/^/       /' >&2
  echo "" >&2
  echo "       Either the key was renamed (check the migration guide for the Quarkus version in" >&2
  echo "       proxy/pom.xml) or the extension providing it is not on the classpath. Fix the key;" >&2
  echo "       do not silence this gate - a key that fails open changes runtime behaviour with" >&2
  echo "       nothing to notice." >&2
  exit 1
fi

if [ -n "${DEPRECATED// /}" ]; then
  echo "ERROR: Quarkus reports one or more configuration keys as DEPRECATED." >&2
  echo "       These still work today, which is exactly why they are worth fixing now: the next" >&2
  echo "       upgrade is where a deprecated key becomes an ignored one, and an ignored key changes" >&2
  echo "       behaviour with a green build behind it." >&2
  echo "" >&2
  printf '%s\n' "$DEPRECATED" | sed 's/^/       /' >&2
  exit 1
fi

echo "OK: no unrecognized or deprecated Quarkus configuration keys across ${MODULES} augmented module(s)"
