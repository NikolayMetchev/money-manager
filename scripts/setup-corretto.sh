#!/usr/bin/env bash
set -euo pipefail

CORRETTO_APT_LIST="/etc/apt/sources.list.d/corretto.list"
CORRETTO_KEYRING="/usr/share/keyrings/corretto-keyring.gpg"
CORRETTO_REPO="deb [signed-by=${CORRETTO_KEYRING}] https://apt.corretto.aws stable main"
CORRETTO_JDK_PACKAGE="java-25-amazon-corretto-jdk"
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

add_corretto_repo() {
  apt-get update -y

  if [[ ! -f "${CORRETTO_KEYRING}" ]]; then
    wget -qO- https://apt.corretto.aws/corretto.key | gpg --dearmor -o "${CORRETTO_KEYRING}"
  fi

  if [[ ! -f "${CORRETTO_APT_LIST}" ]] || ! grep -Fq "${CORRETTO_REPO}" "${CORRETTO_APT_LIST}"; then
    echo "${CORRETTO_REPO}" > "${CORRETTO_APT_LIST}"
  fi

  apt-get update -y
}

install_corretto() {
  apt-get install -y "${CORRETTO_JDK_PACKAGE}"
}

import_ca_cert() {
  if [[ ! -f "${CA_CERT_PATH}" ]]; then
    echo "CA cert not found at ${CA_CERT_PATH}; skipping keytool import." >&2
    return 0
  fi

  if keytool -list -keystore "${CACERTS_PATH}" -storepass changeit -alias "${CA_ALIAS}" >/dev/null 2>&1; then
    echo "CA alias ${CA_ALIAS} already present in Corretto cacerts."
    return 0
  fi

  keytool -importcert \
    -noprompt \
    -trustcacerts \
    -alias "${CA_ALIAS}" \
    -file "${CA_CERT_PATH}" \
    -keystore "${CACERTS_PATH}" \
    -storepass changeit
}

print_java_home_hint() {
  cat <<HINT
Corretto setup complete.

Set these in your shell/environment:
  export JAVA_HOME=${JAVA_HOME_PATH}
  export PATH="\$JAVA_HOME/bin:\$PATH"
HINT
}

main() {
  require_root
  add_corretto_repo
  install_corretto
  import_ca_cert
  print_java_home_hint
}

main "$@"
