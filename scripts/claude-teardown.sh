#!/usr/bin/env bash
set -uo pipefail
apt-get remove -y verilator >/dev/null 2>&1
/home/claude/.local/bin/scala-cli bloop exit >/dev/null 2>&1
pkill -f bloop 2>/dev/null
rm -rf /home/claude/.cache/scalacli/bsp-sockets
rm -rf /home/claude/spac-chisel/{.scala-build,.bloop}
rm -rf /home/claude/.cache/{scala-cli,coursier}
rm -f /home/claude/.local/bin/scala-cli /home/claude/.local/bin/scala-cli.real
rm -f /usr/lib/jvm/java-21-openjdk-amd64/lib/security/cacerts
echo "Teardown complete."
