#!/bin/bash
#
# Patched fork of aws-samples/pix-proxy-samples, now on CloudHSM Client SDK 5.
#
# Changes vs. upstream (see VERIFICATION.md and upstream issue #17):
#   1. Configure EVERY ACTIVE HSM in the cluster, not just Hsms[0].
#   2. exec the JVM so it becomes PID 1 and receives SIGTERM (graceful shutdown).
#   3. SDK 5: no client daemon to start, so no readiness poll and no settle delay.
#
# WHAT WENT AWAY WITH SDK 5, and why its absence is correct rather than an omission.
#
# SDK 3 needed a cloudhsm_client daemon: this script started it, tailed its log for
# 'libevmulti_init: Ready !', bounded that wait with a timeout, and then slept a configurable
# "settle" delay because the Ready line alone was not a sufficient signal. All of that existed to
# manage a process that SDK 5 does not have - the JCE provider connects to the HSMs itself. There is
# therefore nothing to wait for here, and a readiness poll would have nothing to poll.
#
# The failure modes those guards caught have not disappeared, they have MOVED: a wrong security group
# or a malformed customerCA.crt now surfaces when the provider is constructed, as a
# ProviderInitializationException from the application, with the cause in the application log rather
# than in a daemon log this script had to scrape.
set -uo pipefail

die() { echo "FATAL: $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Trust anchor for the cluster (the customer CA you signed the cluster CSR with)
# ---------------------------------------------------------------------------
[ -n "${HSM_CUSTOMER_CA:-}" ] || die "HSM_CUSTOMER_CA is empty - map the CloudHSMCustomerCA parameter into the task"
echo "$HSM_CUSTOMER_CA" > /opt/cloudhsm/etc/customerCA.crt

# ---------------------------------------------------------------------------
# 2. Discover and configure ALL active HSMs
#
#    Upstream used --query "Clusters[0].Hsms[0].EniIp", i.e. only the FIRST HSM.
#    That silently defeats multi-AZ high availability: every task pins itself to one
#    HSM, the others serve no traffic while still being billed, and an HSM
#    replacement (which changes the ENI IP) takes the fleet down until restarted.
#
#    configure-jce, not configure. SDK 5 configures EACH COMPONENT SEPARATELY - configure-jce for the
#    JCE provider, configure-cli for CloudHSM CLI - and running one does not configure the other. This
#    container only runs the JCE provider, so only configure-jce is called; if you add the CLI to this
#    image for debugging, it needs its own configure-cli or it will hold an unsubstituted
#    %%HSM_IP_ADDRESS%% placeholder, which is what ships in the package.
#
#    All IPs go in ONE invocation: -a is variadic in SDK 5, and calling it once per IP would leave the
#    config holding only the last one.
# ---------------------------------------------------------------------------
[ -n "${HSM_CLUSTER_ID:-}" ] || die "HSM_CLUSTER_ID is empty - map the CloudHSMClusterId parameter into the task"
[ -n "${AWS_DEFAULT_REGION:-}" ] || die "AWS_DEFAULT_REGION is empty"

HSM_IPS=$(aws cloudhsmv2 describe-clusters \
            --region "$AWS_DEFAULT_REGION" \
            --filter clusterIds="$HSM_CLUSTER_ID" \
            --query "Clusters[0].Hsms[?State=='ACTIVE'].EniIp" \
            --output text) || die "describe-clusters failed (check the cloudhsmv2:DescribeClusters IAM permission)"

[ -n "$HSM_IPS" ] || die "no ACTIVE HSM found in cluster $HSM_CLUSTER_ID"

HSM_COUNT=$(echo "$HSM_IPS" | wc -w)
echo "* configuring $HSM_COUNT active HSM(s): $HSM_IPS"

# --disable-key-availability-check is NOT set here, and that is deliberate.
#
# SDK 5 refuses to use a key that is present on fewer than two HSMs. On a single-HSM cluster that makes
# every signing call fail, and the flag switches the check off. It is not set because a PSP signing key
# on one HSM has no durability: measured, an HSM reports cluster-coverage "full" on a single-HSM
# cluster, because coverage is relative to CURRENT MEMBERSHIP and says nothing about durability. If you
# genuinely need a one-HSM cluster for a non-production environment, add the flag explicitly and record
# why - see README-CloudHSM.md.
# shellcheck disable=SC2086
/opt/cloudhsm/bin/configure-jce -a $HSM_IPS \
    --hsm-ca-cert /opt/cloudhsm/etc/customerCA.crt \
    || die "configure-jce failed for: $HSM_IPS"
echo "* configured $HSM_COUNT active HSM(s)"

# ---------------------------------------------------------------------------
# 3. Hand over to the JVM
#
#    `exec` makes the JVM PID 1 so that SIGTERM from ECS reaches it directly.
#    Without it the signal goes to this shell and graceful shutdown can be lost,
#    meaning in-flight payment requests are killed abruptly.
# ---------------------------------------------------------------------------
echo "* starting application"

# DNS cache bound, and it MUST be here rather than in application code.
#
# Manual de Seguranca do Pix section 2 requires clients to respect the DNS TTL, and the
# JVM does not: it applies its own fixed cache, defaulting to 30s with no security
# manager and to -1 (CACHE FOREVER) when one is installed. A stale address then
# outlives a BCB endpoint move for the life of the process, which is the "loss of
# access" the manual warns about.
#
# MEASURED: the JDK reads networkaddress.cache.ttl ONCE, in the static initializer of
# sun.net.InetAddressCachePolicy. The proxy resolves names during startup (SSM, Secrets
# Manager, the Firehose client) before its Camel routes are configured, so a
# Security.setProperty call from application code runs after the policy has frozen and
# changes nothing - it sets the property and the JVM keeps using the old value.
# sun.net.inetaddr.ttl is the system-property fallback the JDK consults when the
# security property is unset, and -D is the only way to get a value in before the first
# lookup. DnsCachePolicy.apply() still runs and now VERIFIES the effective value,
# logging loudly if it disagrees, but this line is what actually does the work.
#
# --add-exports lets that verification read the effective policy; without it the log
# says "UNVERIFIED" rather than falsely confirming.
PIX_DNS_TTL="${PIX_DNS_TTL:-30}"
PIX_DNS_OPTS="-Dsun.net.inetaddr.ttl=${PIX_DNS_TTL} -Dsun.net.inetaddr.negative.ttl=1"
PIX_DNS_OPTS="${PIX_DNS_OPTS} --add-exports java.base/sun.net=ALL-UNNAMED"

# The two XMLDSig exports are MANDATORY on this image, not defensive.
#
# They were declared as compiler arguments in proxy/core/pom.xml only, which compiles and then throws
# at runtime from JDK 17 onward. Measured on JDK 17: three signing tests fail with
#
#   IllegalAccessError: class Iso20022URIDereferencer cannot access class
#   com.sun.org.apache.xml.internal.security.signature.XMLSignatureInput (in module java.xml.crypto)
#
# On JDK 11 the same code runs without them because that release still permitted the reflective
# access. This image is now JDK 17 - forced by the SDK 5 JCE provider - so omitting them means every
# signing path fails at the first ISO 20022 message, in production, having passed the build.
PIX_XMLDSIG_OPTS="--add-exports java.xml.crypto/com.sun.org.apache.xml.internal.security.signature=ALL-UNNAMED"
PIX_XMLDSIG_OPTS="${PIX_XMLDSIG_OPTS} --add-exports java.xml.crypto/org.jcp.xml.dsig.internal.dom=ALL-UNNAMED"

# -cp, not -jar. `java -jar` IGNORES -cp entirely, and the SDK 5 provider jar must be ON the classpath
# beside the application: it is deliberately NOT bundled into the runner jar, because the jar is
# code-signed (repackaging breaks the signature) and architecture-specific (bundling would pin the
# image to the build host's architecture). Taking it from the image's own package install is what keeps
# the provider matched to both the architecture and the pinned version.
CLOUDHSM_JCE_JAR=$(find /opt/cloudhsm/java -maxdepth 1 -name 'cloudhsm-jce-*.jar' -print -quit 2>/dev/null)
[ -n "$CLOUDHSM_JCE_JAR" ] || die "no CloudHSM JCE jar under /opt/cloudhsm/java - the cloudhsm-jce package is not installed in this image"
echo "* CloudHSM JCE provider: $CLOUDHSM_JCE_JAR"

# Unquoted on purpose: each variable holds MULTIPLE options that must word-split into separate
# arguments. Quoting them would pass one argument containing spaces, and the JVM would reject it.
# shellcheck disable=SC2086
exec java ${PIX_DNS_OPTS} ${PIX_XMLDSIG_OPTS} ${JAVA_OPTS:-} \
    -cp "application.jar:${CLOUDHSM_JCE_JAR}" \
    io.quarkus.runner.GeneratedMain
