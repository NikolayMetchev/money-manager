#!/usr/bin/env bash
# Renames a GitHub macOS runner out of `.local` (#1596), for anything that starts the JVM launchers repeatedly.
#
# log4j looks the machine's name up on its first start (LoggerContext's ${hostName}, InetAddress.getLocalHost()).
# GitHub's macOS runners are named `<…>.local`, which macOS resolves over multicast DNS, and there only the runner's
# first lookup is answered: every later JVM launch that logs waits ~35 s for it to time out. Neither an /etc/hosts
# entry for that name nor flushing the DNS cache helps (probed on 4 runners, 2026-11-25); a name outside .local, in
# /etc/hosts, resolves at once. A Mac's own name resolves, so this is the runner's setting: it does nothing off CI or
# off macOS.
set -euo pipefail
[ "$(uname -s)" = Darwin ] && [ -n "${GITHUB_ACTIONS:-}" ] || exit 0
echo "Host name was $(hostname)"
sudo scutil --set HostName mm-ci-runner
printf '127.0.0.1 mm-ci-runner\n::1 mm-ci-runner\n' | sudo tee -a /etc/hosts >/dev/null
sudo dscacheutil -flushcache
sudo killall -HUP mDNSResponder || true
echo "Host name now $(hostname)"
