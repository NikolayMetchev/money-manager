#!/usr/bin/env bash
set -euo pipefail

JAVA_HOME_PATH="/usr/lib/jvm/java-25-amazon-corretto"
CACERTS_PATH="${JAVA_HOME_PATH}/lib/security/cacerts"
CA_CERT_PATH="/usr/local/share/ca-certificates/envoy-mitmproxy-ca-cert.crt"
CA_ALIAS="envoy-mitmproxy-ca"

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    echo "Please run as root (sudo)." >&2
    exit 1
  fi
}

ensure_corretto_installed() {
  if [[ ! -x "${JAVA_HOME_PATH}/bin/java" ]]; then
    echo "Corretto 25 not found at ${JAVA_HOME_PATH}. Run scripts/setup-corretto.sh first." >&2
    exit 1
  fi
}

refresh_ca_cert() {
  if [[ ! -f "${CA_CERT_PATH}" ]]; then
    echo "CA cert not found at ${CA_CERT_PATH}." >&2
    exit 1
  fi

  if keytool -list -keystore "${CACERTS_PATH}" -storepass changeit -alias "${CA_ALIAS}" >/dev/null 2>&1; then
    keytool -delete -alias "${CA_ALIAS}" -keystore "${CACERTS_PATH}" -storepass changeit
  fi

  keytool -importcert \
    -noprompt \
    -trustcacerts \
    -alias "${CA_ALIAS}" \
    -file "${CA_CERT_PATH}" \
    -keystore "${CACERTS_PATH}" \
    -storepass changeit

  keytool -list -keystore "${CACERTS_PATH}" -storepass changeit -alias "${CA_ALIAS}" >/dev/null
}

print_java_home_hint() {
  cat <<HINT
Corretto cacerts updated.

JAVA_HOME:
  export JAVA_HOME=${JAVA_HOME_PATH}
  export PATH="\$JAVA_HOME/bin:\$PATH"
HINT
}

main() {
  require_root
  ensure_corretto_installed
  refresh_ca_cert
  print_java_home_hint
}

main "$@"
