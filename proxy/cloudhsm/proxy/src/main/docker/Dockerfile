# CloudHSM Client SDK 5, on a JDK the SDK 5 JCE provider actually supports.
#
# JDK 17, not 11, and that is forced. The SDK 5 JCE provider supports OpenJDK 17, 21 and 25 only;
# 5.17.1 was the last release to support OpenJDK 11. Corretto 11 is still patched until 2032, so this
# is not the JDK running out of support - it is the HSM provider dropping it.
FROM amazoncorretto:17

WORKDIR /tmp/

# SDK 5 is ONE package here, where SDK 3 was three.
#
# SDK 3 needed cloudhsm-client (the daemon), cloudhsm-client-jce (the provider) and cloudhsm-client-dyn.
# SDK 5 is daemon-less: the JCE provider connects to the HSMs itself, so there is no background process
# to install, start, or wait for. The migration path AWS documents for an SDK 3 host is to stop and
# remove cloudhsm-client precisely because SDK 5 has no equivalent.
#
# The version is PINNED and must match cloudhsm.sdk5.rpm.version in proxy/pom.xml, which is what the
# build compiles against. CI compares the two - see .github/workflows/build.yml. A mutable "-latest"
# URL here would let the runtime provider drift away from the compiled-against API with nothing
# recording it, and would also mean two builds of the same commit could ship different providers.
#
# Amzn2023 is capitalised in the path and lowercase in the filename. That is S3's actual layout, not a
# typo: the lowercase path returns HTTP 403.
ARG CLOUDHSM_SDK5_RPM_VERSION=5.18.0-1
RUN curl -fsSL -O "https://s3.amazonaws.com/cloudhsmv2-software/CloudHsmClient/Amzn2023/cloudhsm-jce-${CLOUDHSM_SDK5_RPM_VERSION}.amzn2023.$(uname -m).rpm" \
    && yum -y install "./cloudhsm-jce-${CLOUDHSM_SDK5_RPM_VERSION}.amzn2023.$(uname -m).rpm" \
        awscli \
    && yum clean all \
    && rm -rf /var/cache/yum /tmp/*.rpm

# openssl and apr are NOT installed any more.
#
# They were here for netty-tcnative, the OpenSSL-dynamic build. The BCB-facing client leg no longer
# uses OpenSSL at all - it cannot: OpenSSL needs the private key BYTES and an HSM key has none, so that
# path could never present the client certificate. The client now uses SslProvider.JDK with
# HsmX509KeyManager. If a server-side listener is later configured for OpenSSL, add them back
# deliberately and say why.

# $(uname -m) above, rather than a hardcoded x86_64, because the jar is ARCHITECTURE-SPECIFIC: it
# carries native/libcloudhsm_jce.so inside itself, and the x86_64 and aarch64 packages ship different
# jars under the same filename and version. Measured: cmp reports them as differing, and file(1) says
# "x86-64" for one and "ARM aarch64" for the other. This is also why the build does NOT bundle the jar
# into the runner jar - it would pin the image to the build host's architecture, silently.

RUN chown -R 1001 /opt/cloudhsm \
  && chmod -R "g+rwX" /opt/cloudhsm \
  && chown -R 1001:root /opt/cloudhsm

ENV PATH="/opt/cloudhsm/bin:${PATH}"

# LD_LIBRARY_PATH is gone on purpose. SDK 3's provider loaded .so files from /opt/cloudhsm/lib; the
# SDK 5 JCE jar carries its own native library inside itself and does not read that directory. Setting
# it would be inert at best and misleading at worst.

WORKDIR /work/
COPY proxy/cloudhsm/proxy/src/main/docker/wrapper_script.sh /work/wrapper_script.sh
COPY proxy/cloudhsm/proxy/target/*-runner.jar /work/application.jar

# set up permissions for user `1001`
RUN chmod 775 /work /work/wrapper_script.sh \
  && chown -R 1001 /work \
  && chmod -R "g+rwX" /work \
  && chown -R 1001:root /work

EXPOSE 8080
EXPOSE 9090
USER 1001

CMD ["./wrapper_script.sh"]
